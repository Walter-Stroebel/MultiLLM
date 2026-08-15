/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.multillm;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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
     * Requests currently in flight against this endpoint. Incremented
     * before the HTTP call, decremented after — the router's
     * least-busy-first signal.
     */
    final AtomicInteger inFlight = new AtomicInteger(0);

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
        return name + " (" + url + ", " + costTier + ", inFlight=" + inFlight.get()
                + ", tokPerSec=" + String.format("%.1f", tokPerSecEma) + ")";
    }
}
