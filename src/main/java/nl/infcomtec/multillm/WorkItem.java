/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.multillm;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One unit of routed work: a request waiting to be picked up by whichever
 * worker becomes free next, plus the future the original caller of
 * {@link Router#route} blocks on for the result. Plain data holder — the
 * queueing and retry logic live in {@link Router}, this just carries the
 * request across that boundary.
 */
final class WorkItem {

    final String model;
    final String prompt;
    final boolean json;
    final String imageBase64;
    final CompletableFuture<LlamaClient.Reply> future = new CompletableFuture<>();

    /**
     * Epoch millis of the last failure on each endpoint, kept only briefly
     * (checked against a short grace window in {@code Router.scanFor}) so
     * the endpoint that just failed this item doesn't immediately re-grab
     * it before another idle endpoint gets a look — not a permanent
     * blacklist. A permanent per-endpoint blacklist was a real, observed
     * deadlock: with only two vision-capable endpoints, an item that
     * failed on both once each could never be retried by anyone again,
     * even after both endpoints' cooldowns lapsed, since nothing ever
     * cleared the "already tried this one" record.
     */
    final Map<Endpoint, Long> recentlyFailedBy = new ConcurrentHashMap<>();

    /** Total attempts across all endpoints, for the give-up ceiling. */
    final AtomicInteger attemptCount = new AtomicInteger(0);

    WorkItem(String model, String prompt, boolean json, String imageBase64) {
        this.model = model;
        this.prompt = prompt;
        this.json = json;
        this.imageBase64 = imageBase64;
    }
}
