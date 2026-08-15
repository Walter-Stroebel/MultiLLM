/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.multillm;

import java.util.List;

/**
 * One OpenAI-compatible chat-completions backend: a local llama-server or
 * Ollama instance, or a remote paid API. Plain data holder plus the
 * mutable stats {@link Router} reads (never races on) to decide which
 * worker gets the next work item — this is a struct with behavior
 * attached, not a bean. Concurrency safety (never two requests in flight
 * against the same endpoint at once) is structural in {@link Router}'s
 * one-worker-thread-per-endpoint design, not enforced by a mutex here.
 */
final class Endpoint {

    final String name;
    final String url;
    final List<String> models;
    final CostTier costTier;
    final String apiKey;

    /**
     * Exponential moving average of observed tokens/second, updated after
     * every completed call. Zero until the first call returns.
     */
    volatile double tokPerSecEma = 0.0;

    /**
     * Epoch millis until which this endpoint should be skipped by the
     * router, set after a connection failure (box unreachable, service
     * down) or a detected corrupted reply — not after an application-level
     * error, which is a real bug worth surfacing rather than hiding behind
     * a retry. Zero means no active cooldown.
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
        return name + " (" + url + ", " + costTier
                + ", tokPerSec=" + String.format("%.1f", tokPerSecEma) + ")";
    }
}
