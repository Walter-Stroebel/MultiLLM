/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.multillm;

import java.util.List;

/**
 * One OpenAI-compatible chat-completions backend: a local llama-server or
 * Ollama instance, or a remote paid API. Plain data holder read by
 * {@link RoutePlanner} to build the candidate list for a request.
 */
final class Endpoint {

    final String name;
    final String url;
    final List<String> models;
    final CostTier costTier;
    final String apiKey;
    final EndpointKind kind;

    /**
     * Whether this endpoint can accept image content at all. Declared
     * explicitly in config rather than inferred from the model name,
     * matching the same "state what you know, don't make the code guess"
     * config philosophy as costTier.
     */
    final boolean vision;

    /**
     * Epoch millis until which this endpoint should be skipped by the
     * planner, set after a connection failure (box unreachable, service
     * down) — not after an application-level error, which is a real bug
     * worth surfacing rather than hiding behind a retry. Zero means no
     * active cooldown.
     */
    volatile long cooldownUntilMillis = 0L;

    Endpoint(String name, String url, List<String> models, CostTier costTier, String apiKey,
            boolean vision, EndpointKind kind) {
        this.name = name;
        this.url = url;
        this.models = models;
        this.costTier = costTier;
        this.apiKey = apiKey;
        this.vision = vision;
        this.kind = kind;
    }

    boolean servesModel(String model) {
        return models.contains(model);
    }

    /**
     * The model name to send when the caller's requested model isn't one
     * this endpoint declares — its own first declared model, since that's
     * the one it's actually configured to run. Every model an endpoint can
     * be asked for must be listed explicitly, even for a pass-through
     * gateway like OpenRouter: a wildcard would let any request silently
     * route to a paid model nobody approved spending on.
     */
    String modelToUse(String requestedModel) {
        if (models.contains(requestedModel)) {
            return requestedModel;
        }
        return models.get(0);
    }

    boolean isCoolingDown() {
        return System.currentTimeMillis() < cooldownUntilMillis;
    }

    /**
     * Marks this endpoint unreachable for the given duration — called
     * after a connection failure so a genuinely-down box doesn't keep
     * getting tried first on every subsequent request until it recovers.
     */
    void coolDown(long durationMillis) {
        cooldownUntilMillis = System.currentTimeMillis() + durationMillis;
    }

    @Override
    public String toString() {
        return name + " (" + url + ", " + kind + ", " + costTier + ")";
    }
}
