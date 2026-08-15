/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.multillm;

import java.io.IOException;
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
 * shortest at assignment time — that punished fast workers for finishing
 * quickly, since queue length at assignment time says nothing about how
 * long a worker takes to drain each item. With one shared queue, a
 * worker only ever takes its next item once it's actually free, so
 * throughput self-balances by real completion speed.
 * <p>
 * {@code model} is a soft preference, not a hard requirement: a worker
 * checks for a preferred-model item first, but an idle worker with
 * nothing matching takes any unclaimed item and answers using its own
 * model instead of standing idle — verified live via GPU monitoring
 * across three real machines: an idle worker previously could never help
 * drain another model's backlog, so two boxes sat at baseline temperature
 * while a third alone worked through a growing queue. Vision is the one
 * hard capability boundary this preference doesn't cross — an
 * image-bearing item is only ever eligible for a worker whose endpoint
 * declares {@code vision}.
 * <p>
 * A worker that fails an item (endpoint unreachable, or a corrupted
 * vision reply) cools that endpoint down and puts the item back on the
 * shared queue for a different worker to pick up. Two cooldown lengths,
 * not one: a confirmed connectivity failure is a hard signal worth a
 * real ({@link #UNREACHABLE_COOLDOWN_MILLIS}) penalty, but a detected
 * corrupted reply is a much softer one — real, measured incident:
 * applying the same 30s penalty to both endpoints in a 2-endpoint vision
 * pool, after two corrupted replies landed a second apart, zeroed out
 * all vision capacity for the full cooldown window, since no other
 * endpoint could take over. {@link #CORRUPTED_REPLY_COOLDOWN_MILLIS} is
 * far shorter, and {@link Worker#isLastVisionEndpointStanding()} skips
 * the cooldown entirely rather than ever drop a capability to zero.
 * <p>
 * A failed attempt is remembered only briefly per endpoint
 * ({@link WorkItem#recentlyFailedBy}), not permanently — a real, observed
 * deadlock came from an earlier version that blacklisted an endpoint from
 * an item forever after one failure: with only two vision-capable
 * endpoints, an item that failed on both once each could never be
 * retried by anyone again, even after both cooldowns lapsed, since
 * nothing ever cleared the "already tried this one" record. The give-up
 * ceiling is a genuine attempt counter instead.
 */
final class Router {

    /**
     * Cooldown after a confirmed connectivity failure (box down, refused,
     * timed out) — a hard signal the endpoint is genuinely unreachable
     * right now, worth a real penalty before retrying it.
     */
    private static final long UNREACHABLE_COOLDOWN_MILLIS = 30_000L;

    /**
     * Cooldown after a detected corrupted reply — much softer than an
     * outage; a few seconds is enough to let a contaminated slot clear
     * without freezing an entire capability class.
     */
    private static final long CORRUPTED_REPLY_COOLDOWN_MILLIS = 3_000L;

    /** Grace window during which an endpoint that just failed an item won't re-grab it. */
    private static final long RECENT_FAILURE_GRACE_MILLIS = 1_000L;

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
        if (endpoints.isEmpty()) {
            throw new IllegalArgumentException("No endpoints configured");
        }
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
                || lower.contains("i do not see an image")
                || lower.contains("cannot") && lower.contains("see") && lower.contains("image")
                || lower.contains("unable to") && lower.contains("see") && lower.contains("image")
                || lower.contains("as a text-based") && (lower.contains("model") || lower.contains("ai"))
                || lower.contains("i can only process the") && lower.contains("text");
    }

    /**
     * One endpoint's processing loop against the single shared queue.
     * Scans for the first item it's eligible for each time it wakes,
     * rather than blindly taking the queue head — the head might be a
     * model this worker doesn't serve, or an item it just failed.
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
                        // Preferred pass: an item whose requested model this endpoint
                        // actually serves. Checked first so a naturally-matching idle
                        // worker isn't beaten to its own preferred item by a
                        // cross-model helper on the same wake.
                        WorkItem preferred = scanFor(candidate -> endpoint.servesModel(candidate.model));
                        if (null != preferred) {
                            queue.remove(preferred);
                            return preferred;
                        }
                        // Fallback pass: this worker serves nothing the remaining
                        // items asked for, but it's idle and they're waiting — take
                        // any unclaimed item rather than stand around. It'll answer
                        // using its own primaryModel(), not the item's request. Image
                        // items are excluded unless this endpoint actually has vision
                        // — "model is a preference" stops at capability.
                        WorkItem any = scanFor(candidate -> null == candidate.imageBase64 || endpoint.vision);
                        if (null != any) {
                            queue.remove(any);
                            return any;
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

        /**
         * True if no other vision-capable endpoint in the whole pool is
         * currently able to serve a vision request — meaning cooling this
         * one down too would drop vision capacity to zero rather than
         * merely reduce it.
         */
        private boolean isLastVisionEndpointStanding() {
            for (Endpoint e : endpoints) {
                if (e != endpoint && e.vision && !e.isCoolingDown()) {
                    return false;
                }
            }
            return true;
        }

        /** Must be called while holding queueLock. */
        private WorkItem scanFor(java.util.function.Predicate<WorkItem> modelMatch) {
            for (WorkItem candidate : queue) {
                Long failedAt = candidate.recentlyFailedBy.get(endpoint);
                boolean recentlyFailedHere = null != failedAt
                        && System.currentTimeMillis() - failedAt < RECENT_FAILURE_GRACE_MILLIS;
                if (!recentlyFailedHere && modelMatch.test(candidate)) {
                    return candidate;
                }
            }
            return null;
        }

        private void process(WorkItem item) {
            String modelToUse = endpoint.servesModel(item.model) ? item.model : endpoint.primaryModel();
            try {
                LlamaClient.Reply reply = LlamaClient.ask(endpoint, modelToUse, item.prompt, item.json, item.imageBase64);
                if (null != item.imageBase64 && looksLikeMissingImage(reply.content)) {
                    if (!isLastVisionEndpointStanding()) {
                        endpoint.coolDown(CORRUPTED_REPLY_COOLDOWN_MILLIS);
                    }
                    requeueOrFail(item, new IOException(endpoint.name + " returned a reply indicating no image "
                            + "was received despite one being sent — likely concurrent-request context contamination"));
                    return;
                }
                if (reply.millis > 0) {
                    endpoint.recordTokPerSec(1000.0 * reply.completionTokens / reply.millis);
                }
                item.future.complete(reply);
            } catch (EndpointUnreachableException e) {
                endpoint.coolDown(UNREACHABLE_COOLDOWN_MILLIS);
                requeueOrFail(item, e);
            } catch (IOException | InterruptedException e) {
                // A malformed response or other non-connectivity error is a real bug,
                // not a transient outage — fail the caller immediately rather than
                // burning through other endpoints for something that isn't their fault.
                item.future.completeExceptionally(e);
            }
        }

        private void requeueOrFail(WorkItem item, IOException failure) {
            item.recentlyFailedBy.put(endpoint, System.currentTimeMillis());
            if (item.attemptCount.incrementAndGet() >= MAX_ATTEMPTS_PER_ITEM) {
                item.future.completeExceptionally(failure);
                return;
            }
            enqueue(item);
        }
    }
}
