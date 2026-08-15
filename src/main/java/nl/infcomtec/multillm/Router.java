/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.multillm;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * True single shared work queue: one dedicated worker thread per
 * {@link Endpoint}, all pulling from the same queue. A worker takes the
 * next item it's eligible for the moment it's free — there is no
 * per-worker queue and no up-front assignment decision to get wrong.
 * <p>
 * This replaced two earlier designs. The first had every caller thread
 * compute its own ranking and race to acquire a per-endpoint permit
 * directly; that produced two distinct, hard-to-diagnose deadlocks under
 * real concurrent load. The second gave each worker its own private
 * queue and assigned each new item to whichever worker's queue was
 * shortest at assignment time — that looked reasonable but was still
 * wrong: queue length at assignment time says nothing about how long a
 * worker takes to drain each item. A slow worker (Ollama's mistral on
 * legion, several seconds per request) got the same share of requests
 * as fast ones (predator/victus's gemma-vision, often under a second)
 * and ended up doing ~87% of total GPU-time under real 60-second load
 * while its queue rarely looked backed up — fast workers were
 * effectively punished for finishing quickly by getting assigned more
 * work up front to "balance" a queue-length snapshot that didn't
 * reflect actual throughput. With one shared queue, a worker only ever
 * takes its next item once it's actually free, so throughput
 * self-balances by real completion speed — no bookkeeping needed.
 * <p>
 * Cost tier is a soft preference, not a guaranteed ordering: any
 * eligible idle worker may claim any item it serves. A strict
 * free-before-paid guarantee would require re-introducing central
 * assignment coordination, which is exactly the source of the two
 * earlier bugs — free/local endpoints are the common case and tend to
 * be faster and more available, so they end up serving most traffic in
 * practice without that being enforced as a hard rule.
 * <p>
 * A worker that fails an item (endpoint unreachable, or a corrupted
 * vision reply) cools that endpoint down and puts the item back on the
 * shared queue for a different worker to pick up, remembering which
 * endpoints have already failed it so it never lands back on the same
 * one. The caller only sees a hard failure once every endpoint serving
 * the model has been tried and failed, or a hard attempt cap is hit.
 * <p>
 * "First idle worker to see the queue wins" already produces a fair
 * split by request count, verified under real 60s concurrent load
 * (near-even three-way split across predator/victus/legion). A slow
 * worker showing a much larger share of total GPU-time despite an equal
 * request count (~75% for legion in that same run) is not something
 * this router corrects for on purpose — it's the honest, correct
 * consequence of that worker's hardware taking longer per request. Three
 * workers, one queue, whoever finishes first grabs the next item; a
 * consistently slow worker naturally ends up idle less and busy longer,
 * exactly as it should.
 */
final class Router {

    private static final long COOLDOWN_MILLIS = 30_000L;
    private static final int MAX_ATTEMPTS_PER_ITEM = 8;

    private final List<Endpoint> endpoints;
    private final Object queueLock = new Object();
    private final LinkedList<WorkItem> queue = new LinkedList<>();

    Router(List<Endpoint> endpoints) {
        this.endpoints = endpoints;
        for (Endpoint e : endpoints) {
            Thread t = new Thread(new Worker(e), "router-worker-" + e.name);
            t.setDaemon(true);
            t.start();
        }
    }

    LlamaClient.Reply route(String model, String prompt, boolean json) throws IOException, InterruptedException {
        return route(model, prompt, json, null);
    }

    LlamaClient.Reply route(String model, String prompt, boolean json, String imageBase64)
            throws IOException, InterruptedException {
        requireModelServed(model);
        WorkItem item = new WorkItem(model, prompt, json, imageBase64);
        enqueue(item);
        try {
            return item.future.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new IOException(cause);
        }
    }

    private void requireModelServed(String model) {
        for (Endpoint e : endpoints) {
            if (e.servesModel(model)) {
                return;
            }
        }
        throw new IllegalArgumentException("No endpoint configured for model: " + model);
    }

    private void enqueue(WorkItem item) {
        synchronized (queueLock) {
            queue.add(item);
            queueLock.notifyAll();
        }
    }

    /**
     * Heuristic for the "answered but ignored the image" failure mode:
     * cheap substring check on a small set of phrases models use when
     * they believe no image was attached. False negatives (a genuinely
     * image-blind reply phrased differently) just mean no requeue —
     * acceptable, since this is a best-effort corruption detector, not
     * a guarantee.
     */
    private static boolean looksLikeMissingImage(String content) {
        String lower = content.toLowerCase();
        return lower.contains("you have not provided")
                || lower.contains("please provide the") && lower.contains("image")
                || lower.contains("no image") && lower.contains("provided")
                || lower.contains("i don't see an image")
                || lower.contains("i do not see an image");
    }

    /**
     * One endpoint's processing loop against the single shared queue.
     * Scans for the first item it's eligible for (serves the model,
     * hasn't already failed this item, endpoint not cooling down) each
     * time it wakes, rather than blindly taking the queue head — the
     * head might be a model this worker doesn't serve.
     */
    private final class Worker implements Runnable {

        final Endpoint endpoint;

        Worker(Endpoint endpoint) {
            this.endpoint = endpoint;
        }

        @Override
        public void run() {
            while (true) {
                WorkItem item = takeEligibleItem();
                if (null == item) {
                    continue;
                }
                process(item);
            }
        }

        private WorkItem takeEligibleItem() {
            synchronized (queueLock) {
                while (true) {
                    if (!endpoint.isCoolingDown()) {
                        var it = queue.iterator();
                        while (it.hasNext()) {
                            WorkItem candidate = it.next();
                            if (endpoint.servesModel(candidate.model) && !candidate.triedAndFailed.contains(endpoint)) {
                                it.remove();
                                return candidate;
                            }
                        }
                    }
                    try {
                        // No eligible item right now — wait to be woken by a new
                        // enqueue/requeue rather than busy-polling. A cooling-down
                        // worker also parks here; it's woken by the next enqueue and
                        // simply re-checks isCoolingDown(), naturally picking back up
                        // once its cooldown lapses without a separate timer.
                        queueLock.wait(1000L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
            }
        }

        private void process(WorkItem item) {
            try {
                LlamaClient.Reply reply = LlamaClient.ask(endpoint, item.model, item.prompt, item.json, item.imageBase64);
                if (null != item.imageBase64 && looksLikeMissingImage(reply.content)) {
                    endpoint.coolDown(COOLDOWN_MILLIS);
                    requeueOrFail(item, new IOException(endpoint.name + " returned a reply indicating no image "
                            + "was received despite one being sent — likely concurrent-request context contamination"));
                    return;
                }
                if (reply.millis > 0) {
                    endpoint.recordTokPerSec(1000.0 * reply.completionTokens / reply.millis);
                }
                item.future.complete(reply);
            } catch (EndpointUnreachableException e) {
                endpoint.coolDown(COOLDOWN_MILLIS);
                requeueOrFail(item, e);
            } catch (IOException | InterruptedException e) {
                // A malformed response or other non-connectivity error is a real bug,
                // not a transient outage — fail the caller immediately rather than
                // burning through other endpoints for something that isn't their fault.
                item.future.completeExceptionally(e);
            }
        }

        private void requeueOrFail(WorkItem item, IOException failure) {
            item.triedAndFailed.add(endpoint);
            if (item.triedAndFailed.size() >= MAX_ATTEMPTS_PER_ITEM || item.triedAndFailed.size() >= endpoints.size()) {
                item.future.completeExceptionally(failure);
                return;
            }
            enqueue(item);
        }
    }
}
