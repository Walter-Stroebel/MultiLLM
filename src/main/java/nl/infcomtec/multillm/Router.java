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
 * there. Policy, in order:
 * <ol>
 * <li>Filter to endpoints that serve the requested model.</li>
 * <li>Filter by cost: free endpoints first; only fall through to
 * cheap, then expensive, when no cheaper tier has a candidate — an
 * expensive endpoint is never picked while a free one could serve the
 * same model.</li>
 * <li>Among the surviving candidates, pick least-busy (fewest
 * in-flight requests), tie-broken by highest observed tokens/second.</li>
 * </ol>
 * The in-flight counter is the live self-balancing signal; tokens/sec
 * is a self-measured quality tiebreak, not a user-supplied number —
 * neither requires the caller to evaluate hardware.
 */
final class Router {

    private final List<Endpoint> endpoints;

    Router(List<Endpoint> endpoints) {
        this.endpoints = endpoints;
    }

    LlamaClient.Reply route(String model, String prompt, boolean json) throws IOException, InterruptedException {
        Endpoint chosen = pick(model);
        chosen.inFlight.incrementAndGet();
        try {
            LlamaClient.Reply reply = LlamaClient.ask(chosen, model, prompt, json);
            if (reply.millis > 0) {
                chosen.recordTokPerSec(1000.0 * reply.completionTokens / reply.millis);
            }
            return reply;
        } finally {
            chosen.inFlight.decrementAndGet();
        }
    }

    private Endpoint pick(String model) {
        for (CostTier tier : CostTier.values()) {
            List<Endpoint> candidates = new ArrayList<>();
            for (Endpoint e : endpoints) {
                if (e.costTier == tier && e.servesModel(model)) {
                    candidates.add(e);
                }
            }
            if (!candidates.isEmpty()) {
                candidates.sort(Comparator
                        .<Endpoint>comparingInt(e -> e.inFlight.get())
                        .thenComparing(Comparator.<Endpoint>comparingDouble(e -> e.tokPerSecEma).reversed()));
                return candidates.get(0);
            }
        }
        throw new IllegalArgumentException("No endpoint serves model: " + model);
    }
}
