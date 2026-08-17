/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.multillm;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds an ordered candidate endpoint list for one request and tries
 * them in order, falling through on connectivity failure — replaces the
 * earlier shared-work-queue {@code Router}. That design existed to
 * self-balance throughput across many concurrent items; this gateway
 * instead handles each HTTP request on its own thread and just needs a
 * deterministic "who do I ask, in what order" decision per request, so a
 * plain synchronous walk down a candidate list is all that's needed —
 * no queue, no worker threads.
 * <p>
 * Candidate order, matching OpenRouter's {@code provider} preferences:
 * a {@code host/model}-prefixed request ({@link ChatRequest#requestedHost})
 * names exactly one endpoint — the candidate list is that endpoint alone,
 * or empty if it doesn't exist/doesn't serve the model/lacks vision. For a
 * bare model name, an explicit {@code provider.order} list of endpoint
 * names wins outright (in that order); otherwise endpoints are ordered
 * local-first ({@link EndpointKind#LOCAL} before {@link EndpointKind#REMOTE})
 * since that's this gateway's whole reason to exist, then by declaration
 * order. {@code provider.ignore} removes names outright.
 * {@code provider.allow_fallbacks == false} truncates the list to just
 * the first candidate — try it, and if it fails, fail the request rather
 * than trying anyone else.
 */
final class RoutePlanner {

    private static final long UNREACHABLE_COOLDOWN_MILLIS = 30_000L;

    private final List<Endpoint> endpoints;

    RoutePlanner(List<Endpoint> endpoints) {
        this.endpoints = endpoints;
    }

    LlamaClient.Reply route(ChatRequest request) throws IOException, InterruptedException {
        List<Endpoint> candidates = buildCandidates(request);
        if (candidates.isEmpty()) {
            throw new IOException("No endpoint available for model " + request.model);
        }

        IOException lastFailure = null;
        for (Endpoint endpoint : candidates) {
            if (endpoint.isCoolingDown()) {
                continue;
            }
            String modelToUse = endpoint.modelToUse(request.requestedModel);
            try {
                return LlamaClient.ask(endpoint, modelToUse, request.prompt, request.json,
                        request.imageBase64, request.imageUrl);
            } catch (EndpointUnreachableException e) {
                endpoint.coolDown(UNREACHABLE_COOLDOWN_MILLIS);
                lastFailure = e;
                if (!request.allowFallbacks) {
                    break;
                }
            }
        }
        throw null != lastFailure ? lastFailure : new IOException("No reachable endpoint for model " + request.model);
    }

    /**
     * Same candidate walk as {@link #route}, but for a streaming request:
     * falls back to the next candidate only on a connection/status
     * failure that happens before any response bytes exist to relay —
     * exactly what {@link LlamaClient#askStreaming} guarantees by
     * returning only after the backend's status line confirms success.
     * Once this method returns, the caller is committed to that one
     * endpoint for the rest of the stream.
     */
    LlamaClient.StreamingReply routeStreaming(ChatRequest request) throws IOException {
        List<Endpoint> candidates = buildCandidates(request);
        if (candidates.isEmpty()) {
            throw new IOException("No endpoint available for model " + request.model);
        }

        IOException lastFailure = null;
        for (Endpoint endpoint : candidates) {
            if (endpoint.isCoolingDown()) {
                continue;
            }
            String modelToUse = endpoint.modelToUse(request.requestedModel);
            try {
                return LlamaClient.askStreaming(endpoint, modelToUse, request.prompt, request.json,
                        request.imageBase64, request.imageUrl);
            } catch (EndpointUnreachableException e) {
                endpoint.coolDown(UNREACHABLE_COOLDOWN_MILLIS);
                lastFailure = e;
                if (!request.allowFallbacks) {
                    break;
                }
            }
        }
        throw null != lastFailure ? lastFailure : new IOException("No reachable endpoint for model " + request.model);
    }

    private List<Endpoint> buildCandidates(ChatRequest request) {
        boolean needsImage = null != request.imageBase64 || null != request.imageUrl;

        List<Endpoint> eligible = new ArrayList<>();
        for (Endpoint e : endpoints) {
            if (null != request.requestedHost && !e.name.equals(request.requestedHost)) {
                continue;
            }
            if (needsImage && !e.vision) {
                continue;
            }
            if (request.providerIgnore.contains(e.name)) {
                continue;
            }
            if (!servesAnyOf(e, request)) {
                continue;
            }
            eligible.add(e);
        }

        if (null != request.requestedHost) {
            // Exactly one endpoint could possibly match a host/model request —
            // no policy ordering to apply, no other candidate to fall through to.
            return eligible;
        }

        List<Endpoint> ordered = new ArrayList<>();
        if (!request.providerOrder.isEmpty()) {
            for (String name : request.providerOrder) {
                for (Endpoint e : eligible) {
                    if (e.name.equals(name) && !ordered.contains(e)) {
                        ordered.add(e);
                    }
                }
            }
            for (Endpoint e : eligible) {
                if (!ordered.contains(e)) {
                    ordered.add(e);
                }
            }
        } else {
            for (Endpoint e : eligible) {
                if (EndpointKind.LOCAL == e.kind) {
                    ordered.add(e);
                }
            }
            for (Endpoint e : eligible) {
                if (EndpointKind.REMOTE == e.kind) {
                    ordered.add(e);
                }
            }
        }
        return ordered;
    }

    /** True if the endpoint serves the requested model or any of the request's fallback models. */
    private boolean servesAnyOf(Endpoint e, ChatRequest request) {
        if (e.servesModel(request.requestedModel)) {
            return true;
        }
        for (String fallback : request.fallbackModels) {
            if (e.servesModel(fallback)) {
                return true;
            }
        }
        return false;
    }
}
