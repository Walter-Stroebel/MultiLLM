/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.multillm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Plain OpenAI-compatible chat-completions client, per {@code ttok}'s
 * sibling class of the same name. Works against any backend speaking
 * that wire format — local llama-server/Ollama or a remote paid API —
 * since that has become the de-facto standard regardless of who is
 * serving the model. One-shot, stateless calls only, matching the
 * "each call is an ant: local, ignorant, disposable" model.
 */
final class LlamaClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private LlamaClient() {
    }

    static final class Reply {

        final String content;
        final int completionTokens;
        final long millis;
        final String servedBy;

        Reply(String content, int completionTokens, long millis, String servedBy) {
            this.content = content;
            this.completionTokens = completionTokens;
            this.millis = millis;
            this.servedBy = servedBy;
        }
    }

    /**
     * Sends one chat-completion request to the given endpoint for the
     * given model. Adds a bearer auth header only when the endpoint
     * declares an API key — local backends send none.
     */
    static Reply ask(Endpoint endpoint, String model, String prompt, boolean json)
            throws IOException, InterruptedException {
        return ask(endpoint, model, prompt, json, null);
    }

    /**
     * Same as {@link #ask(Endpoint, String, String, boolean)}, but when
     * {@code imageBase64} is non-null the message content becomes the
     * OpenAI-compatible multimodal array (text part + a
     * {@code data:image/png;base64,...} image_url part) instead of a
     * plain string — the shape both llama-server's vision-capable
     * gemma-vision alias and Ollama's OpenAI-compatible endpoint expect.
     */
    static Reply ask(Endpoint endpoint, String model, String prompt, boolean json, String imageBase64)
            throws IOException, InterruptedException {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        ObjectNode message = MAPPER.createObjectNode();
        message.put("role", "user");
        if (null == imageBase64) {
            message.put("content", prompt);
        } else {
            var content = message.putArray("content");
            ObjectNode textPart = content.addObject();
            textPart.put("type", "text");
            textPart.put("text", prompt);
            ObjectNode imagePart = content.addObject();
            imagePart.put("type", "image_url");
            imagePart.putObject("image_url").put("url", "data:image/png;base64," + imageBase64);
        }
        body.putArray("messages").add(message);
        if (json) {
            body.putObject("response_format").put("type", "json_object");
        }
        if (null != imageBase64) {
            // llama-server's default --slot-prompt-similarity (0.10) can route two
            // unrelated image requests to the same cached slot on as little as 10%
            // prompt-token overlap, reusing that slot's cached KV state built around
            // a *different* image — observed in practice as the model confidently
            // answering as if no image were given. cache_prompt:false opts this
            // request out of slot-cache reuse entirely; text-only requests keep
            // caching since they don't carry this correctness risk.
            body.put("cache_prompt", false);
        }

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint.url + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .timeout(Duration.ofMinutes(2));
        if (null != endpoint.apiKey) {
            reqBuilder.header("Authorization", "Bearer " + endpoint.apiKey);
        }

        long start = System.currentTimeMillis();
        HttpResponse<String> resp;
        try {
            resp = HTTP.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            // HttpClient throws IOException (ConnectException, HttpTimeoutException,
            // etc.) when the endpoint itself can't be reached — distinct from a
            // successful connection that returns a bad payload.
            throw new EndpointUnreachableException("Cannot reach " + endpoint.name + " at " + endpoint.url, e);
        }
        long elapsed = System.currentTimeMillis() - start;
        if (500 <= resp.statusCode()) {
            throw new EndpointUnreachableException("Chat completion failed on " + endpoint.name
                    + ": HTTP " + resp.statusCode() + " " + resp.body());
        }
        if (200 != resp.statusCode()) {
            throw new IOException("Chat completion failed on " + endpoint.name
                    + ": HTTP " + resp.statusCode() + " " + resp.body());
        }
        JsonNode root = MAPPER.readTree(resp.body());
        JsonNode choices = root.get("choices");
        if (null == choices || 0 == choices.size()) {
            throw new IOException("No choices in response from " + endpoint.name + ": " + resp.body());
        }
        String content = choices.get(0).get("message").get("content").asText();
        int completionTokens = 0;
        JsonNode usage = root.get("usage");
        if (null != usage && usage.has("completion_tokens")) {
            completionTokens = usage.get("completion_tokens").asInt();
        }
        return new Reply(content, completionTokens, elapsed, endpoint.name);
    }
}
