/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.multillm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads the endpoint pool from a JSON config file. Deliberately dumb:
 * the file states only what a non-expert user already knows (name,
 * URL, which models are loaded, a coarse free/cheap/expensive cost
 * tier, and an optional API key) — nothing measured or scored goes in
 * this file, since that's the router's job at runtime.
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
            endpoints.add(new Endpoint(name, url, models, tier, apiKey));
        }
        return endpoints;
    }
}
