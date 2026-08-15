/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.multillm;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Picks one endpoint per request from the pool and routes the call
 * there, falling back to the next-ranked candidate if the chosen
 * endpoint is unreachable, busy, or returns a corrupted reply. Ranking,
 * in order:
 * <ol>
 * <li>Filter to endpoints that serve the requested model and are not
 * in cooldown.</li>
 * <li>Sort by cost tier: free before cheap before expensive — a free
 * endpoint is always tried before a paid one, even as a fallback.</li>
 * <li>Within a tier, tie-broken by highest observed tokens/second.</li>
 * </ol>
 * At most one request is ever in flight against a given endpoint at a
 * time ({@link Endpoint#tryAcquire()}) — ganging up concurrent requests
 * on a single local llama-server instance (typically one parallel slot)
 * has been observed to cross-contaminate context between requests, and
 * a cloud endpoint risks a rate-limit ban under the same pattern. If no
 * candidate is currently free, this blocks on the best-ranked one
 * rather than double-booking a different one.
 * <p>
 * On {@link EndpointUnreachableException}, or a reply that looks
 * corrupted (a vision request came back claiming no image was given),
 * the endpoint is cooled down and the next candidate is tried. A
 * malformed response or client error is not retried — that's a real
 * bug, not a transient condition, and gets surfaced immediately.
 */
final class Router {

    private static final long COOLDOWN_MILLIS = 30_000L;

    private final List<Endpoint> endpoints;

    Router(List<Endpoint> endpoints) {
        this.endpoints = endpoints;
    }

    LlamaClient.Reply route(String model, String prompt, boolean json) throws IOException, InterruptedException {
        return route(model, prompt, json, null);
    }

    LlamaClient.Reply route(String model, String prompt, boolean json, String imageBase64)
            throws IOException, InterruptedException {
        List<Endpoint> candidates = rank(model);
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("No endpoint currently available for model: " + model);
        }
        IOException lastFailure = null;
        for (int i = 0; i < candidates.size(); i++) {
            Endpoint chosen = candidates.get(i);
            boolean lastCandidate = (i == candidates.size() - 1);
            boolean acquired = chosen.tryAcquire();
            if (!acquired) {
                if (!lastCandidate) {
                    // A busier endpoint further down the ranking might be free right
                    // now — try it before falling back to blocking on this one.
                    continue;
                }
                chosen.acquireUninterruptibly();
            }
            try {
                LlamaClient.Reply reply = LlamaClient.ask(chosen, model, prompt, json, imageBase64);
                if (null != imageBase64 && looksLikeMissingImage(reply.content)) {
                    // Model answered but ignored the image, almost always a sign this
                    // endpoint's single parallel slot got context-contaminated by a
                    // request that arrived around the same time. Treat like an outage.
                    chosen.coolDown(COOLDOWN_MILLIS);
                    lastFailure = new IOException(chosen.name + " returned a reply indicating no image was "
                            + "received despite one being sent — likely concurrent-request context contamination");
                    continue;
                }
                if (reply.millis > 0) {
                    chosen.recordTokPerSec(1000.0 * reply.completionTokens / reply.millis);
                }
                return reply;
            } catch (EndpointUnreachableException e) {
                chosen.coolDown(COOLDOWN_MILLIS);
                lastFailure = e;
            } finally {
                chosen.release();
            }
        }
        throw lastFailure;
    }

    /**
     * Heuristic for the "answered but ignored the image" failure mode:
     * cheap substring check on a small set of phrases models use when
     * they believe no image was attached. False negatives (a genuinely
     * image-blind reply phrased differently) just mean no requeue —
     * acceptable, since this is a best-effort corruption detector, not
     * a guarantee.
     */
    private boolean looksLikeMissingImage(String content) {
        String lower = content.toLowerCase();
        return lower.contains("you have not provided")
                || lower.contains("please provide the") && lower.contains("image")
                || lower.contains("no image") && lower.contains("provided")
                || lower.contains("i don't see an image")
                || lower.contains("i do not see an image");
    }

    private List<Endpoint> rank(String model) {
        List<Endpoint> candidates = new ArrayList<>();
        for (Endpoint e : endpoints) {
            if (e.servesModel(model) && !e.isCoolingDown()) {
                candidates.add(e);
            }
        }
        candidates.sort(Comparator
                .<Endpoint, CostTier>comparing(e -> e.costTier)
                // Idle endpoints must outrank busy ones, or a single fast box that
                // answered first ends up as the permanent top pick for every future
                // request the instant it frees up — starving every other box even
                // though it's just as available. tokPerSec only tie-breaks among
                // candidates that are equally idle (or equally busy).
                .thenComparing(e -> e.isBusy())
                .thenComparing(Comparator.<Endpoint>comparingDouble(e -> e.tokPerSecEma).reversed()));
        return candidates;
    }
}
