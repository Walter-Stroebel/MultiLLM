/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.multillm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads the endpoint pool from a JSON config file. Deliberately dumb:
 * the file states only what a non-expert user already knows (name, URL,
 * which models are loaded, whether it's local or remote, whether it can
 * accept images, a coarse free/cheap/expensive cost tier, and an optional
 * API key) — nothing measured or scored goes in this file, since that's
 * the route planner's job at request time. Models must be listed
 * explicitly even for a pass-through gateway like OpenRouter — no
 * wildcard, since that would let any request silently route to a paid
 * model nobody approved spending on.
 */
final class EndpointConfig {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EndpointConfig() {
    }

    static List<Endpoint> load(File file) throws IOException {
        JsonNode root = MAPPER.readTree(file);
        List<Endpoint> endpoints = new ArrayList<>();
        for (JsonNode node : root) {
            String name = node.get("name").asText();
            String url = node.get("url").asText();
            List<String> models = new ArrayList<>();
            for (JsonNode m : node.get("models")) {
                models.add(m.asText());
            }
            CostTier tier = node.has("costTier")
                    ? CostTier.valueOf(node.get("costTier").asText().toUpperCase())
                    : CostTier.FREE;
            String apiKey = node.has("apiKey") ? node.get("apiKey").asText() : null;
            boolean vision = node.has("vision") && node.get("vision").asBoolean();
            EndpointKind kind = node.has("kind")
                    ? EndpointKind.valueOf(node.get("kind").asText().toUpperCase())
                    : EndpointKind.REMOTE;
            endpoints.add(new Endpoint(name, url, models, tier, apiKey, vision, kind));
        }
        return endpoints;
    }

    /**
     * Loads named sampling-override aliases (e.g. {@code "drunk-gemma4"})
     * from an optional {@code config/personas.json}, keyed by name. Absent
     * file means an empty map — personas are an opt-in experimentation
     * feature, not required config.
     */
    static Map<String, Persona> loadPersonas(File file) throws IOException {
        Map<String, Persona> personas = new LinkedHashMap<>();
        if (!file.isFile()) {
            return personas;
        }
        JsonNode root = MAPPER.readTree(file);
        for (JsonNode node : root) {
            String name = node.get("name").asText();
            String hostEndpoint = node.get("hostEndpoint").asText();
            String model = node.get("model").asText();
            Double temperature = node.has("temperature") ? node.get("temperature").asDouble() : null;
            Double topP = node.has("topP") ? node.get("topP").asDouble() : null;
            Integer topK = node.has("topK") ? node.get("topK").asInt() : null;
            Double minP = node.has("minP") ? node.get("minP").asDouble() : null;
            Double repeatPenalty = node.has("repeatPenalty") ? node.get("repeatPenalty").asDouble() : null;
            SamplingOverride sampling = new SamplingOverride(temperature, topP, topK, minP, repeatPenalty);
            personas.put(name, new Persona(name, hostEndpoint, model, sampling));
        }
        return personas;
    }
}
