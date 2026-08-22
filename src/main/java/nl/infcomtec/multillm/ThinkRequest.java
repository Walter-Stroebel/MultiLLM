/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.multillm;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One parsed {@code POST /v1/think} request: a prompt plus the two named
 * {@link Persona}s to run it through — a divergent (high-variance) pass
 * and a convergent (judging) pass told explicitly what it's looking at.
 * See {@link ThinkOrchestrator}.
 */
final class ThinkRequest {

    static final String DEFAULT_DIVERGENT_PERSONA = "drunk-gemma4";
    static final String DEFAULT_CONVERGENT_PERSONA = "sober-gemma4";

    final String prompt;
    final String divergentPersona;
    final String convergentPersona;

    ThinkRequest(String prompt, String divergentPersona, String convergentPersona) {
        this.prompt = prompt;
        this.divergentPersona = divergentPersona;
        this.convergentPersona = convergentPersona;
    }

    static ThinkRequest parse(JsonNode root) {
        String prompt = root.has("prompt") ? root.get("prompt").asText() : "";
        String divergentPersona = root.has("divergentPersona")
                ? root.get("divergentPersona").asText() : DEFAULT_DIVERGENT_PERSONA;
        String convergentPersona = root.has("convergentPersona")
                ? root.get("convergentPersona").asText() : DEFAULT_CONVERGENT_PERSONA;
        return new ThinkRequest(prompt, divergentPersona, convergentPersona);
    }
}
