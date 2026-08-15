/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.multillm;

import java.util.concurrent.CompletableFuture;

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
     * Endpoints already tried and failed for this item, so a requeue
     * never lands back on a worker that just failed it — that worker
     * would either fail it again for the same reason (a real, sustained
     * outage) or, for a corrupted-reply detection, needlessly cool down
     * further before the next legitimate use.
     */
    final java.util.Set<Endpoint> triedAndFailed = java.util.concurrent.ConcurrentHashMap.newKeySet();

    WorkItem(String model, String prompt, boolean json, String imageBase64) {
        this.model = model;
        this.prompt = prompt;
        this.json = json;
        this.imageBase64 = imageBase64;
    }
}
