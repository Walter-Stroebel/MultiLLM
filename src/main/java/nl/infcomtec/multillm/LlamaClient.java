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
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.NoRouteToHostException;
import java.net.URI;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import nl.infcomtec.nethttp.Rest;
import nl.infcomtec.nethttp.Transcript;

/**
 * Plain OpenAI-compatible chat-completions client, per {@code ttok}'s
 * sibling class of the same name. Works against any backend speaking
 * that wire format — local llama-server/Ollama or a remote paid API —
 * since that has become the de-facto standard regardless of who is
 * serving the model. One-shot, stateless calls only, matching the
 * "each call is an ant: local, ignorant, disposable" model.
 * <p>
 * <b>Transport.</b> The buffered {@link #ask} path runs on the vendored
 * {@code nl.infcomtec.nethttp.Rest} — a non-throwing {@code java.net.http}
 * wrapper that captures a full {@link Transcript} (request as sent,
 * response as received, timing, errors) of every call as a side effect.
 * That transcript is what the config-gated call inspector renders; see
 * {@link CallLog}.
 * <p>
 * {@code Rest} pins the underlying {@code HttpClient} to <b>HTTP/1.1</b>
 * ({@code Rest.useHttp2} left {@code false}). This matters and must not
 * be "modernized" away: {@code HttpClient} defaults to {@code HTTP_2},
 * which attempts an HTTP/2 upgrade negotiation against every endpoint,
 * including plain HTTP/1.1-only local servers like llama-server. Observed
 * symptom under concurrent multi-endpoint load: llama-server's own log
 * showed every request processed in under two seconds once it arrived,
 * but requests sat for gaps of up to several minutes before arriving at
 * all — the delay was entirely client-side, before the request was even
 * sent over the wire, consistent with protocol negotiation overhead. The
 * previous transport here was raw {@link HttpURLConnection} for the same
 * reason; {@code Rest}'s HTTP/1.1 pin preserves that.
 * <p>
 * <b>No timeout.</b> Neither {@code Rest} nor this class sets a connect
 * or read timeout — a call waits as long as the backend takes, and a
 * connection the backend closes is reported as the failure it is. This
 * is deliberate and matches {@code Rest}'s design stance: latency
 * expectations are the caller's concern, never the transport's.
 * <p>
 * The streaming path ({@link #askStreaming}) stays on
 * {@link HttpURLConnection}: {@code Rest} buffers the whole response body,
 * whereas the gateway's SSE relay needs the raw {@link InputStream} to
 * pump bytes straight through.
 */
final class LlamaClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
        return ask(endpoint, model, prompt, null, json, null, null, null);
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
    static Reply ask(Endpoint endpoint, String model, String prompt, String systemPrompt, boolean json,
            String imageBase64, String imageUrl, SamplingOverride sampling) throws IOException, InterruptedException {
        byte[] bodyBytes = buildRequestBody(model, prompt, systemPrompt, json, false, imageBase64, imageUrl, sampling);

        Rest<JsonNode> rest = new Rest<JsonNode>()
                .base(endpoint.url + "/v1/chat/completions")
                .verb(Rest.Verb.POST)
                .header("Content-Type", "application/json")
                .bodyText(new String(bodyBytes, StandardCharsets.UTF_8));
        if (null != endpoint.apiKey) {
            rest.header("Authorization", "Bearer " + endpoint.apiKey);
        }

        JsonNode root = rest.bodyAs(JsonNode.class);
        Transcript transcript = rest.transcript();
        CallLog.record(endpoint.name, model, transcript);

        if (null == root) {
            // Rest collects failures rather than throwing; translate the first
            // one into the exception RoutePlanner's fallthrough logic expects.
            throw translate(endpoint, rest, transcript);
        }

        int statusCode = transcript.status;
        String responseBody = null != transcript.responseBody ? transcript.responseBody : "";
        checkStatus(endpoint, statusCode, responseBody);

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
        return new Reply(content, completionTokens, transcript.totalMillis(), endpoint.name, model);
    }

    /**
     * Maps a failed {@link Rest} call (which threw nothing, only collected)
     * onto the exception type {@link RoutePlanner} keys its fallthrough on.
     * <p>
     * The split is by <i>whether an HTTP response arrived at all</i>, not by
     * status code:
     * <ul>
     *   <li>A response came back ({@code transcript.status > 0}) — even a 500,
     *       even with a body that didn't parse: it is an application-level
     *       outcome. Plain {@link IOException}; the caller and the inspector
     *       see it, {@link RoutePlanner} does not cool the endpoint down or
     *       try another one.</li>
     *   <li>No response — connection refused, DNS failure, no route, SSL
     *       handshake failure, connection dropped before a status line:
     *       {@link EndpointUnreachableException}, so the planner cools this
     *       endpoint and moves to the next candidate.</li>
     *   <li>Neither (a bad URL, a request that never left): plain
     *       {@link IOException} — nothing to fall through to.</li>
     * </ul>
     */
    private static IOException translate(Endpoint endpoint, Rest<?> rest, Transcript transcript) {
        if (transcript.status > 0) {
            String body = null != transcript.responseBody ? transcript.responseBody : "";
            return new IOException("Chat completion failed on " + endpoint.name
                    + ": HTTP " + transcript.status + " " + body);
        }
        Exception first = rest.firstError();
        if (first instanceof ConnectException || first instanceof UnknownHostException
                || first instanceof NoRouteToHostException || first instanceof javax.net.ssl.SSLException
                || first instanceof java.io.InterruptedIOException) {
            return new EndpointUnreachableException("Cannot reach " + endpoint.name + " at " + endpoint.url, first);
        }
        if (first instanceof IOException) {
            // Some other transport-level IOException with no response — e.g. the
            // connection dropped mid-flight. No HTTP outcome to report, so treat
            // it as unreachable and let another candidate try.
            return new EndpointUnreachableException("Call to " + endpoint.name + " at " + endpoint.url
                    + " failed: " + first.getMessage(), first);
        }
        String detail = null != first ? first.toString() : "no response, no error recorded";
        return new IOException("Chat completion failed on " + endpoint.name + ": " + detail);
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
    static StreamingReply askStreaming(Endpoint endpoint, String model, String prompt, String systemPrompt,
            boolean json, String imageBase64, String imageUrl, SamplingOverride sampling) throws IOException {
        byte[] bodyBytes = buildRequestBody(model, prompt, systemPrompt, json, true, imageBase64, imageUrl, sampling);

        int statusCode;
        HttpURLConnection conn;
        try {
            conn = openConnection(endpoint, bodyBytes);
            statusCode = conn.getResponseCode();
        } catch (UnknownHostException | java.net.SocketException
                | javax.net.ssl.SSLException | java.io.InterruptedIOException e) {
            // No HTTP response reached us — connection refused/reset, DNS, route,
            // TLS, or the socket gave out. Unreachable: let RoutePlanner try the
            // next candidate. Anything with a status line is handled below and is
            // NOT a fallthrough case, same split as the buffered path's translate().
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

    /**
     * A non-200 status is an <i>application-level</i> outcome, not a
     * connectivity failure — a 500 for "no such model", a 400 for a
     * malformed body, a 429 for rate limiting are all things the caller
     * (and the inspector) should see verbatim, not something to retry
     * behind their back. So every non-200 becomes a plain
     * {@link IOException}: {@link RoutePlanner} does not treat it as
     * unreachable, does not cool the endpoint down, and does not fall
     * through to another candidate. Only a genuine transport failure —
     * no HTTP response at all — is {@link EndpointUnreachableException},
     * and that is raised at the point the connection fails, not here.
     */
    private static void checkStatus(Endpoint endpoint, int statusCode, String responseBody) throws IOException {
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
        // No connect/read timeout, by design (matches the Rest transport on the
        // buffered path): a call waits as long as the backend takes, and a
        // connection the backend closes is reported as the failure it is.
        conn.setRequestProperty("Content-Type", "application/json");
        if (null != endpoint.apiKey) {
            conn.setRequestProperty("Authorization", "Bearer " + endpoint.apiKey);
        }
        try (OutputStream out = conn.getOutputStream()) {
            out.write(bodyBytes);
        }
        return conn;
    }

    private static byte[] buildRequestBody(String model, String prompt, String systemPrompt, boolean json,
            boolean stream, String imageBase64, String imageUrl, SamplingOverride sampling) throws IOException {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        var messages = body.putArray("messages");
        if (null != systemPrompt && !systemPrompt.isEmpty()) {
            ObjectNode systemMessage = messages.addObject();
            systemMessage.put("role", "system");
            systemMessage.put("content", systemPrompt);
        }
        ObjectNode message = messages.addObject();
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
        if (json) {
            body.putObject("response_format").put("type", "json_object");
        }
        if (stream) {
            body.put("stream", true);
        }
        if (null != sampling) {
            if (null != sampling.temperature) {
                body.put("temperature", sampling.temperature);
            }
            if (null != sampling.topP) {
                body.put("top_p", sampling.topP);
            }
            if (null != sampling.topK) {
                body.put("top_k", sampling.topK);
            }
            if (null != sampling.minP) {
                body.put("min_p", sampling.minP);
            }
            if (null != sampling.repeatPenalty) {
                body.put("repeat_penalty", sampling.repeatPenalty);
            }
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
