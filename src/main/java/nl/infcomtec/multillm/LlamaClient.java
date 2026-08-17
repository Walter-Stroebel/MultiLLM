/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.multillm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Plain OpenAI-compatible chat-completions client, per {@code ttok}'s
 * sibling class of the same name. Works against any backend speaking
 * that wire format — local llama-server/Ollama or a remote paid API —
 * since that has become the de-facto standard regardless of who is
 * serving the model. One-shot, stateless calls only, matching the
 * "each call is an ant: local, ignorant, disposable" model.
 * <p>
 * Uses {@link HttpURLConnection} (opened fresh per call via
 * {@link URL#openConnection()}), not {@code java.net.http.HttpClient}.
 * Checked, not assumed: {@code HttpClient.newHttpClient().version()}
 * reports {@code HTTP_2} by default — it attempts HTTP/2 upgrade
 * negotiation against every endpoint, including plain HTTP/1.1-only
 * local servers like llama-server. Real, observed symptom under
 * concurrent multi-endpoint load: llama-server's own log showed every
 * request processed in under two seconds once it arrived, but requests
 * sat for gaps of up to several minutes before arriving at all — the
 * delay was entirely client-side, before the request was even sent
 * over the wire, consistent with protocol negotiation overhead rather
 * than anything server-side. {@code HttpURLConnection} predates
 * HTTP/2 entirely — no negotiation, no shared connection pool or
 * executor to reason about — while still handling chunked encoding,
 * Content-Length framing, and TLS correctly instead of that being
 * hand-rolled here.
 */
final class LlamaClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    private static final int READ_TIMEOUT_MILLIS = 120_000;

    private LlamaClient() {
    }

    static final class Reply {

        final String content;
        final int completionTokens;
        final long millis;
        final String servedBy;
        final String servedModel;

        Reply(String content, int completionTokens, long millis, String servedBy, String servedModel) {
            this.content = content;
            this.completionTokens = completionTokens;
            this.millis = millis;
            this.servedBy = servedBy;
            this.servedModel = servedModel;
        }
    }

    /**
     * A backend connection whose response body is being streamed rather
     * than buffered — the caller (the gateway's chat handler) copies raw
     * bytes from {@code body} straight to the HTTP client as they arrive,
     * so the backend's own SSE ({@code data: {...}\n\n} ... {@code data:
     * [DONE]\n\n}) framing reaches the client unmodified. {@code body}
     * must be closed by the caller once fully drained.
     */
    static final class StreamingReply {

        final InputStream body;
        final String servedBy;
        final String servedModel;

        StreamingReply(InputStream body, String servedBy, String servedModel) {
            this.body = body;
            this.servedBy = servedBy;
            this.servedModel = servedModel;
        }
    }

    /**
     * Sends one chat-completion request to the given endpoint for the
     * given model. Adds a bearer auth header only when the endpoint
     * declares an API key — local backends send none.
     */
    static Reply ask(Endpoint endpoint, String model, String prompt, boolean json)
            throws IOException, InterruptedException {
        return ask(endpoint, model, prompt, json, null, null);
    }

    /**
     * Same as {@link #ask(Endpoint, String, String, boolean)}, but when
     * {@code imageBase64} or {@code imageUrl} is non-null the message
     * content becomes the OpenAI-compatible multimodal array (text part +
     * an image_url part) instead of a plain string — the shape both
     * llama-server's vision-capable gemma-vision alias and Ollama's
     * OpenAI-compatible endpoint expect. At most one of the two image
     * arguments should be set; a plain URL is preferred by callers that
     * have one (e.g. the gateway's own {@code /v1/files/{id}} upload
     * endpoint) so the backend fetches it itself instead of the caller
     * paying base64's ~33% size inflation on top of round-tripping the
     * bytes through this process.
     */
    static Reply ask(Endpoint endpoint, String model, String prompt, boolean json, String imageBase64, String imageUrl)
            throws IOException, InterruptedException {
        byte[] bodyBytes = buildRequestBody(model, prompt, json, false, imageBase64, imageUrl);

        long start = System.currentTimeMillis();
        String responseBody;
        int statusCode;
        try {
            HttpURLConnection conn = openConnection(endpoint, bodyBytes);
            statusCode = conn.getResponseCode();
            InputStream in = (200 <= statusCode && statusCode < 300) ? conn.getInputStream() : conn.getErrorStream();
            responseBody = (null == in) ? "" : readAll(in);
            conn.disconnect();
        } catch (java.net.SocketTimeoutException e) {
            throw new EndpointUnreachableException("Timed out waiting for " + endpoint.name + " at " + endpoint.url, e);
        } catch (java.net.ConnectException | java.net.UnknownHostException | java.net.NoRouteToHostException
                | javax.net.ssl.SSLException e) {
            throw new EndpointUnreachableException("Cannot reach " + endpoint.name + " at " + endpoint.url, e);
        }
        long elapsed = System.currentTimeMillis() - start;

        checkStatus(endpoint, statusCode, responseBody);

        JsonNode root = MAPPER.readTree(responseBody);
        JsonNode choices = root.get("choices");
        if (null == choices || 0 == choices.size()) {
            throw new IOException("No choices in response from " + endpoint.name + ": " + responseBody);
        }
        String content = choices.get(0).get("message").get("content").asText();
        int completionTokens = 0;
        JsonNode usage = root.get("usage");
        if (null != usage && usage.has("completion_tokens")) {
            completionTokens = usage.get("completion_tokens").asInt();
        }
        return new Reply(content, completionTokens, elapsed, endpoint.name, model);
    }

    /**
     * Same request shape as {@link #ask}, but with {@code stream: true} —
     * returns as soon as the backend's status line/headers confirm
     * success, without reading the body. The caller pumps
     * {@link StreamingReply#body} straight through to its own HTTP
     * client, so this is the last point at which a failure can still be
     * handled by falling back to a different endpoint: once any response
     * bytes have been relayed downstream, the client has already seen
     * output attributed to this endpoint and switching would corrupt the
     * stream.
     */
    static StreamingReply askStreaming(Endpoint endpoint, String model, String prompt, boolean json,
            String imageBase64, String imageUrl) throws IOException {
        byte[] bodyBytes = buildRequestBody(model, prompt, json, true, imageBase64, imageUrl);

        int statusCode;
        HttpURLConnection conn;
        try {
            conn = openConnection(endpoint, bodyBytes);
            statusCode = conn.getResponseCode();
        } catch (java.net.SocketTimeoutException e) {
            throw new EndpointUnreachableException("Timed out waiting for " + endpoint.name + " at " + endpoint.url, e);
        } catch (java.net.ConnectException | java.net.UnknownHostException | java.net.NoRouteToHostException
                | javax.net.ssl.SSLException e) {
            throw new EndpointUnreachableException("Cannot reach " + endpoint.name + " at " + endpoint.url, e);
        }

        if (200 != statusCode) {
            InputStream errIn = conn.getErrorStream();
            String responseBody = (null == errIn) ? "" : readAll(errIn);
            conn.disconnect();
            checkStatus(endpoint, statusCode, responseBody);
        }
        return new StreamingReply(conn.getInputStream(), endpoint.name, model);
    }

    private static void checkStatus(Endpoint endpoint, int statusCode, String responseBody) throws IOException {
        if (500 <= statusCode) {
            throw new EndpointUnreachableException("Chat completion failed on " + endpoint.name
                    + ": HTTP " + statusCode + " " + responseBody);
        }
        if (200 != statusCode) {
            throw new IOException("Chat completion failed on " + endpoint.name
                    + ": HTTP " + statusCode + " " + responseBody);
        }
    }

    private static HttpURLConnection openConnection(Endpoint endpoint, byte[] bodyBytes) throws IOException {
        URI uri = URI.create(endpoint.url + "/v1/chat/completions");
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        conn.setReadTimeout(READ_TIMEOUT_MILLIS);
        conn.setRequestProperty("Content-Type", "application/json");
        if (null != endpoint.apiKey) {
            conn.setRequestProperty("Authorization", "Bearer " + endpoint.apiKey);
        }
        try (OutputStream out = conn.getOutputStream()) {
            out.write(bodyBytes);
        }
        return conn;
    }

    private static byte[] buildRequestBody(String model, String prompt, boolean json, boolean stream,
            String imageBase64, String imageUrl) throws IOException {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        ObjectNode message = MAPPER.createObjectNode();
        message.put("role", "user");
        if (null == imageBase64 && null == imageUrl) {
            message.put("content", prompt);
        } else {
            var content = message.putArray("content");
            ObjectNode textPart = content.addObject();
            textPart.put("type", "text");
            textPart.put("text", prompt);
            ObjectNode imagePart = content.addObject();
            imagePart.put("type", "image_url");
            String url = null != imageUrl ? imageUrl : "data:image/png;base64," + imageBase64;
            imagePart.putObject("image_url").put("url", url);
        }
        body.putArray("messages").add(message);
        if (json) {
            body.putObject("response_format").put("type", "json_object");
        }
        if (stream) {
            body.put("stream", true);
        }
        if (null != imageBase64 || null != imageUrl) {
            // llama-server's default --slot-prompt-similarity (0.10) can route two
            // unrelated image requests to the same cached slot on as little as 10%
            // prompt-token overlap, reusing that slot's cached KV state built around
            // a *different* image — observed in practice as the model confidently
            // answering as if no image were given. cache_prompt:false opts this
            // request out of slot-cache reuse entirely; text-only requests keep
            // caching since they don't carry this correctness risk.
            body.put("cache_prompt", false);
        }
        return MAPPER.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while (-1 != (n = in.read(chunk))) {
            buf.write(chunk, 0, n);
        }
        return buf.toString(StandardCharsets.UTF_8);
    }
}
