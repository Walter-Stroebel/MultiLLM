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
 * endpoint is unreachable. Ranking, in order:
 * <ol>
 * <li>Filter to endpoints that serve the requested model and are not
 * in cooldown.</li>
 * <li>Sort by cost tier: free before cheap before expensive — a free
 * endpoint is always tried before a paid one, even as a fallback.</li>
 * <li>Within a tier, least-busy (fewest in-flight requests) first,
 * tie-broken by highest observed tokens/second.</li>
 * </ol>
 * On {@link EndpointUnreachableException} the failed endpoint is put
 * into cooldown and the next candidate in rank order is tried. A
 * malformed response or client error is not retried — that's a real
 * bug, not a transient outage, and gets surfaced immediately.
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
        for (Endpoint chosen : candidates) {
            chosen.inFlight.incrementAndGet();
            try {
                LlamaClient.Reply reply = LlamaClient.ask(chosen, model, prompt, json, imageBase64);
                if (reply.millis > 0) {
                    chosen.recordTokPerSec(1000.0 * reply.completionTokens / reply.millis);
                }
                return reply;
            } catch (EndpointUnreachableException e) {
                chosen.coolDown(COOLDOWN_MILLIS);
                lastFailure = e;
            } finally {
                chosen.inFlight.decrementAndGet();
            }
        }
        throw lastFailure;
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
                .thenComparingInt(e -> e.inFlight.get())
                .thenComparing(Comparator.<Endpoint>comparingDouble(e -> e.tokPerSecEma).reversed()));
        return candidates;
    }
}
