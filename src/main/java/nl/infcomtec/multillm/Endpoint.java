/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.multillm;

import java.util.List;
import java.util.concurrent.Semaphore;

/**
 * One OpenAI-compatible chat-completions backend: a local llama-server or
 * Ollama instance, or a remote paid API. Plain data holder plus the
 * mutable live-load counters the {@link Router} needs for least-busy
 * tie-breaking — this is a struct with behavior attached, not a bean.
 */
final class Endpoint {

    final String name;
    final String url;
    final List<String> models;
    final CostTier costTier;
    final String apiKey;

    /**
     * Hard concurrency gate: exactly one request in flight against this
     * endpoint at a time, never more. Most local llama-server instances
     * run with a single parallel slot (no {@code --parallel} override),
     * and sending a second concurrent multimodal request has been
     * observed to cross-contaminate context between requests — the model
     * answers as if no image were given even though one was sent. A
     * cloud endpoint has the same practical constraint from the other
     * direction: hammering it with concurrent requests risks a rate-limit
     * ban. So this is a real mutex, not a load-balancing heuristic.
     */
    private final Semaphore singleFlight = new Semaphore(1);

    boolean tryAcquire() {
        return singleFlight.tryAcquire();
    }

    void acquireUninterruptibly() {
        singleFlight.acquireUninterruptibly();
    }

    void release() {
        singleFlight.release();
    }

    boolean isBusy() {
        return 0 == singleFlight.availablePermits();
    }

    /**
     * Exponential moving average of observed tokens/second, updated after
     * every completed call. Zero until the first call returns, at which
     * point the router falls back to in-flight count alone.
     */
    volatile double tokPerSecEma = 0.0;

    /**
     * Epoch millis until which this endpoint should be skipped by the
     * router, set after a connection failure (box unreachable, service
     * down) — not after an application-level error, which is a real bug
     * worth surfacing rather than hiding behind a retry. Zero means no
     * active cooldown.
     */
    volatile long cooldownUntilMillis = 0L;

    Endpoint(String name, String url, List<String> models, CostTier costTier, String apiKey) {
        this.name = name;
        this.url = url;
        this.models = models;
        this.costTier = costTier;
        this.apiKey = apiKey;
    }

    boolean servesModel(String model) {
        return models.contains(model);
    }

    boolean isCoolingDown() {
        return System.currentTimeMillis() < cooldownUntilMillis;
    }

    /**
     * Marks this endpoint unreachable for the given duration — called
     * after a connection failure so a genuinely-down box doesn't keep
     * consuming the router's first-choice slot on every subsequent
     * request until it recovers.
     */
    void coolDown(long durationMillis) {
        cooldownUntilMillis = System.currentTimeMillis() + durationMillis;
    }

    /**
     * Folds one observed tokens/second sample into the running average.
     * Weight of 0.3 for the new sample: responsive to real change without
     * one slow outlier swinging the estimate wildly.
     */
    void recordTokPerSec(double sample) {
        if (0.0 == tokPerSecEma) {
            tokPerSecEma = sample;
        } else {
            tokPerSecEma = 0.7 * tokPerSecEma + 0.3 * sample;
        }
    }

    @Override
    public String toString() {
        return name + " (" + url + ", " + costTier + ", busy=" + isBusy()
                + ", tokPerSec=" + String.format("%.1f", tokPerSecEma) + ")";
    }
}
