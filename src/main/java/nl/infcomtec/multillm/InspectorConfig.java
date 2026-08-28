/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.multillm;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The optional {@code "inspector"} block of the gateway config — the
 * key-holder's explicit authorisation to surface the intimate detail of
 * every LLM call (assembled URL, headers, request and response bodies) in
 * a local GUI window. Absent block, or {@code "enabled": false}, means the
 * gateway runs headless exactly as before.
 * <p>
 * This is a D&amp;T feature, A at the most, never P: enabling it turns a
 * headless backend into one with a Swing window, and {@code revealSecrets}
 * additionally un-masks credentials in that window. Both are deliberate,
 * owner-authorised choices, not defaults.
 *
 * <pre>{@code
 * {
 *   "endpoints": [ ... ],
 *   "inspector": { "enabled": true, "maxCalls": 50, "revealSecrets": false }
 * }
 * }</pre>
 */
final class InspectorConfig {

    /** Launch the call-inspector window and start capturing transcripts. */
    final boolean enabled;

    /** Ring size — the inspector retains at most this many recent calls. */
    final int maxCalls;

    /**
     * Show real credential values in the inspector instead of
     * {@code <redacted>}. Only meaningful with {@link #enabled}; the
     * owner is asserting this screen is theirs alone.
     */
    final boolean revealSecrets;

    private InspectorConfig(boolean enabled, int maxCalls, boolean revealSecrets) {
        this.enabled = enabled;
        this.maxCalls = maxCalls;
        this.revealSecrets = revealSecrets;
    }

    /** The default when no {@code "inspector"} block is present: fully off. */
    static InspectorConfig disabled() {
        return new InspectorConfig(false, 0, false);
    }

    static InspectorConfig from(JsonNode node) {
        if (null == node || !node.isObject()) {
            return disabled();
        }
        boolean enabled = node.has("enabled") && node.get("enabled").asBoolean();
        int maxCalls = node.has("maxCalls") ? node.get("maxCalls").asInt(50) : 50;
        boolean revealSecrets = node.has("revealSecrets") && node.get("revealSecrets").asBoolean();
        return new InspectorConfig(enabled, maxCalls, revealSecrets);
    }
}
