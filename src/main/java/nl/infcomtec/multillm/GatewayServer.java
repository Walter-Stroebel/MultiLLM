/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.multillm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;

/**
 * The gateway itself: an OpenAI-compatible {@code /v1/chat/completions}
 * endpoint backed by {@link RoutePlanner}, plus a small file-upload path
 * ({@code /v1/files}) so a caller with no public web server can still
 * hand a local llama-server/Ollama endpoint a real fetchable image URL
 * instead of inlining base64 — see {@link FileStore}. Built on the JDK's
 * own {@link HttpServer}; no new dependency for something this small.
 */
final class GatewayServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RoutePlanner planner;
    private final ThinkOrchestrator thinkOrchestrator;
    private final FileStore fileStore = new FileStore();
    private final String selfBaseUrl;
    private final HttpServer server;
    private final List<Endpoint> endpoints;

    GatewayServer(List<Endpoint> endpoints, Map<String, Persona> personas, int port, String selfBaseUrl)
            throws IOException {
        this.planner = new RoutePlanner(endpoints, personas);
        this.thinkOrchestrator = new ThinkOrchestrator(endpoints, personas);
        this.selfBaseUrl = selfBaseUrl;
        this.endpoints = endpoints;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/v1/chat/completions", new ChatHandler());
        server.createContext("/v1/files", new FilesHandler());
        server.createContext("/v1/think", new ThinkHandler());
        server.createContext("/v1/models", new ModelsHandler());
        server.setExecutor(Executors.newCachedThreadPool());
    }

    void start() {
        server.start();
    }

    private final class ChatHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Method not allowed");
                return;
            }
            try {
                JsonNode root = MAPPER.readTree(exchange.getRequestBody());
                ChatRequest request = ChatRequest.parse(root);
                if (request.stream) {
                    LlamaClient.StreamingReply reply = planner.routeStreaming(request);
                    relayStream(exchange, reply);
                } else {
                    LlamaClient.Reply reply = planner.route(request);
                    sendChatReply(exchange, reply);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                sendError(exchange, 503, "Interrupted");
            } catch (IOException e) {
                sendError(exchange, 502, e.getMessage());
            }
        }

        /**
         * Copies the backend's own SSE bytes straight through to the
         * client, unparsed — llama-server/Ollama already speak the exact
         * {@code data: {...}\n\n ... data: [DONE]\n\n} framing an
         * OpenAI-compatible streaming client expects, so there's nothing
         * to translate.
         */
        private void relayStream(HttpExchange exchange, LlamaClient.StreamingReply reply) throws IOException {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.getResponseHeaders().add("Cache-Control", "no-cache");
            // Not part of any OpenAI-compatible client's expectations — this gateway's
            // own extension so a caller can tell which endpoint answered a streamed
            // request, since that information isn't otherwise present in the raw SSE
            // passthrough the way it is in the buffered response's served_by field.
            exchange.getResponseHeaders().add("X-Served-By", reply.servedBy);
            exchange.sendResponseHeaders(200, 0);
            try (InputStream in = reply.body; OutputStream out = exchange.getResponseBody()) {
                byte[] chunk = new byte[4096];
                int n;
                while (-1 != (n = in.read(chunk))) {
                    out.write(chunk, 0, n);
                    out.flush();
                }
            }
        }

        private void sendChatReply(HttpExchange exchange, LlamaClient.Reply reply) throws IOException {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("id", "gen-" + UUID.randomUUID());
            body.put("object", "chat.completion");
            body.put("created", System.currentTimeMillis() / 1000L);
            body.put("model", reply.servedModel);
            ObjectNode choice = body.putArray("choices").addObject();
            choice.put("index", 0);
            choice.put("finish_reason", "stop");
            ObjectNode message = choice.putObject("message");
            message.put("role", "assistant");
            message.put("content", reply.content);
            ObjectNode usage = body.putObject("usage");
            usage.put("completion_tokens", reply.completionTokens);
            body.put("served_by", reply.servedBy);

            byte[] bytes = MAPPER.writeValueAsBytes(body);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    /**
     * The "help me think about X" endpoint: runs {@link ThinkOrchestrator}'s
     * divergent-then-convergent pass and returns both stages. Unlike
     * {@link ChatHandler}, a failure in the second (convergent/judge) call
     * is not a request failure — see {@link ThinkOrchestrator#run} — so
     * only an {@link IOException} thrown before any divergent result exists
     * (persona/endpoint resolution, or the divergent call itself failing)
     * produces an error response here.
     */
    private final class ThinkHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Method not allowed");
                return;
            }
            try {
                JsonNode root = MAPPER.readTree(exchange.getRequestBody());
                ThinkRequest request = ThinkRequest.parse(root);
                ThinkOrchestrator.ThinkReply reply = thinkOrchestrator.run(request);
                sendThinkReply(exchange, request, reply);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                sendError(exchange, 503, "Interrupted");
            } catch (IOException e) {
                sendError(exchange, 502, e.getMessage());
            }
        }

        private void sendThinkReply(HttpExchange exchange, ThinkRequest request, ThinkOrchestrator.ThinkReply reply)
                throws IOException {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("id", "think-" + UUID.randomUUID());
            body.put("prompt", request.prompt);

            ObjectNode divergent = body.putObject("divergent");
            divergent.put("persona", reply.divergent.persona);
            divergent.put("servedBy", reply.divergent.servedBy);
            divergent.put("content", reply.divergent.content);

            if (null != reply.convergent) {
                ObjectNode convergent = body.putObject("convergent");
                convergent.put("persona", reply.convergent.persona);
                convergent.put("servedBy", reply.convergent.servedBy);
                convergent.put("summary", reply.convergent.summary);
                var itemsArray = convergent.putArray("items");
                for (ThinkOrchestrator.ConvergentItem item : reply.convergent.items) {
                    ObjectNode itemNode = itemsArray.addObject();
                    itemNode.put("claim", item.claim);
                    itemNode.put("verdict", item.verdict);
                    itemNode.put("reasoning", item.reasoning);
                }
            } else {
                body.putNull("convergent");
                body.put("error", reply.convergentError);
            }

            byte[] bytes = MAPPER.writeValueAsBytes(body);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    private final class FilesHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            if ("PUT".equals(method) || "POST".equals(method)) {
                handleUpload(exchange);
            } else if ("GET".equals(method) && path.length() > "/v1/files/".length()) {
                handleDownload(exchange, path.substring("/v1/files/".length()));
            } else {
                sendError(exchange, 405, "Method not allowed");
            }
        }

        private void handleUpload(HttpExchange exchange) throws IOException {
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            if (null == contentType) {
                contentType = "application/octet-stream";
            }
            byte[] bytes = exchange.getRequestBody().readAllBytes();
            String id = fileStore.put(bytes, contentType);
            String url = selfBaseUrl + "/v1/files/" + id;

            ObjectNode body = MAPPER.createObjectNode();
            body.put("id", id);
            body.put("url", url);
            byte[] responseBytes = MAPPER.writeValueAsBytes(body);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(responseBytes);
            }
        }

        private void handleDownload(HttpExchange exchange, String id) throws IOException {
            byte[] bytes = fileStore.getBytes(id);
            if (null == bytes) {
                sendError(exchange, 404, "No such file");
                return;
            }
            exchange.getResponseHeaders().add("Content-Type", fileStore.getContentType(id));
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    /**
     * Static handshake stub for OpenAI-compatible clients (HA's "OpenAI
     * Conversation" integration, model-picker dropdowns, etc.) that call
     * {@code GET /v1/models} to validate an endpoint before ever calling
     * {@code /v1/chat/completions}. Lists every distinct model name across
     * all configured endpoints; no per-model detail beyond id is available
     * or needed for a handshake check.
     */
    private final class ModelsHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Method not allowed");
                return;
            }
            Set<String> modelNames = new LinkedHashSet<>();
            for (Endpoint endpoint : endpoints) {
                modelNames.addAll(endpoint.models);
            }
            ObjectNode body = MAPPER.createObjectNode();
            body.put("object", "list");
            var data = body.putArray("data");
            for (String model : modelNames) {
                ObjectNode entry = data.addObject();
                entry.put("id", model);
                entry.put("object", "model");
                entry.put("created", 0);
                entry.put("owned_by", "multillm");
            }
            byte[] bytes = MAPPER.writeValueAsBytes(body);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    private static void sendError(HttpExchange exchange, int status, String message) throws IOException {
        ObjectNode body = MAPPER.createObjectNode();
        body.putObject("error").put("message", null == message ? "error" : message);
        byte[] bytes = MAPPER.writeValueAsBytes(body);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
