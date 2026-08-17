/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.multillm;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/**
 * One parsed {@code POST /v1/chat/completions} request: the plain
 * OpenAI-compatible fields this gateway actually uses (model, the last
 * user message's text and optional image, response_format), plus the
 * OpenRouter-style routing extensions ({@code models}, {@code route},
 * {@code provider.order}/{@code ignore}/{@code allow_fallbacks}) that
 * {@link RoutePlanner} reads to decide candidate order. Only single-turn
 * text(+image) prompts are supported — matching {@link LlamaClient}'s
 * one-shot, stateless call shape; multi-message conversation history in
 * {@code messages} is deliberately not reconstructed.
 * <p>
 * {@code model} may optionally carry a {@code host/model} prefix (e.g.
 * {@code "predator/gemma-vision"}) naming a specific configured
 * {@link Endpoint} by name — the sharp tool for "ask that box, not
 * whichever one policy would pick." A bare model name with no prefix
 * (e.g. just {@code "mistral:latest"}) keeps today's policy-based
 * behavior: route by local-first / {@code provider.*} among every
 * endpoint that declares the model, first match wins.
 */
final class ChatRequest {

    final String model;
    final String requestedHost;
    final String requestedModel;
    final String prompt;
    final String imageBase64;
    final String imageUrl;
    final boolean json;
    final boolean stream;

    /** Explicit fallback model list from an OpenRouter-style {@code models} array; may be empty. */
    final List<String> fallbackModels;

    final List<String> providerOrder;
    final List<String> providerIgnore;
    final boolean allowFallbacks;

    ChatRequest(String model, String prompt, String imageBase64, String imageUrl, boolean json, boolean stream,
            List<String> fallbackModels, List<String> providerOrder, List<String> providerIgnore,
            boolean allowFallbacks) {
        this.model = model;
        int slash = null == model ? -1 : model.indexOf('/');
        this.requestedHost = slash < 0 ? null : model.substring(0, slash);
        this.requestedModel = slash < 0 ? model : model.substring(slash + 1);
        this.prompt = prompt;
        this.imageBase64 = imageBase64;
        this.imageUrl = imageUrl;
        this.json = json;
        this.stream = stream;
        this.fallbackModels = fallbackModels;
        this.providerOrder = providerOrder;
        this.providerIgnore = providerIgnore;
        this.allowFallbacks = allowFallbacks;
    }

    static ChatRequest parse(JsonNode root) {
        String model = root.has("model") ? root.get("model").asText() : null;

        String prompt = "";
        String imageBase64 = null;
        String imageUrl = null;
        JsonNode messages = root.get("messages");
        if (null != messages && messages.isArray() && messages.size() > 0) {
            JsonNode last = messages.get(messages.size() - 1);
            JsonNode content = last.get("content");
            if (null != content) {
                if (content.isTextual()) {
                    prompt = content.asText();
                } else if (content.isArray()) {
                    StringBuilder text = new StringBuilder();
                    for (JsonNode part : content) {
                        String type = part.has("type") ? part.get("type").asText() : "";
                        if ("text".equals(type)) {
                            if (text.length() > 0) {
                                text.append(' ');
                            }
                            text.append(part.get("text").asText());
                        } else if ("image_url".equals(type)) {
                            String url = part.get("image_url").get("url").asText();
                            if (url.startsWith("data:")) {
                                int comma = url.indexOf(',');
                                imageBase64 = comma < 0 ? url : url.substring(comma + 1);
                            } else {
                                imageUrl = url;
                            }
                        }
                    }
                    prompt = text.toString();
                }
            }
        }

        boolean json = root.has("response_format")
                && "json_object".equals(root.get("response_format").path("type").asText());

        boolean stream = root.has("stream") && root.get("stream").asBoolean();

        List<String> fallbackModels = new ArrayList<>();
        if (root.has("models") && root.get("models").isArray()) {
            for (JsonNode m : root.get("models")) {
                fallbackModels.add(m.asText());
            }
        }

        List<String> providerOrder = new ArrayList<>();
        List<String> providerIgnore = new ArrayList<>();
        boolean allowFallbacks = true;
        JsonNode provider = root.get("provider");
        if (null != provider) {
            if (provider.has("order") && provider.get("order").isArray()) {
                for (JsonNode p : provider.get("order")) {
                    providerOrder.add(p.asText());
                }
            }
            if (provider.has("ignore") && provider.get("ignore").isArray()) {
                for (JsonNode p : provider.get("ignore")) {
                    providerIgnore.add(p.asText());
                }
            }
            if (provider.has("allow_fallbacks")) {
                allowFallbacks = provider.get("allow_fallbacks").asBoolean();
            }
        }

        return new ChatRequest(model, prompt, imageBase64, imageUrl, json, stream,
                fallbackModels, providerOrder, providerIgnore, allowFallbacks);
    }
}
