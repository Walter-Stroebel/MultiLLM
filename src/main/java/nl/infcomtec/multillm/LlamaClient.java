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

        Reply(String content, int completionTokens, long millis) {
            this.content = content;
            this.completionTokens = completionTokens;
            this.millis = millis;
        }
    }

    /**
     * Sends one chat-completion request to the given endpoint for the
     * given model. Adds a bearer auth header only when the endpoint
     * declares an API key — local backends send none.
     */
    static Reply ask(Endpoint endpoint, String model, String prompt, boolean json)
            throws IOException, InterruptedException {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        ObjectNode message = MAPPER.createObjectNode();
        message.put("role", "user");
        message.put("content", prompt);
        body.putArray("messages").add(message);
        if (json) {
            body.putObject("response_format").put("type", "json_object");
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
        return new Reply(content, completionTokens, elapsed);
    }
}
