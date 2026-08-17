/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.multillm;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store for uploaded image bytes, keyed by a generated id.
 * Exists so a caller without a public web server can still hand a local
 * llama-server/Ollama endpoint a real fetchable URL instead of inlining
 * base64: {@code PUT} bytes here, get an id back, reference
 * {@code /v1/files/{id}} as the {@code image_url} in a chat request — the
 * gateway serves those bytes back out over the LAN and the backend fetches
 * them itself. No persistence and no eviction for now; this is a
 * single-process gateway, not a file service.
 */
final class FileStore {

    private static final class Stored {

        final byte[] bytes;
        final String contentType;

        Stored(byte[] bytes, String contentType) {
            this.bytes = bytes;
            this.contentType = contentType;
        }
    }

    private final Map<String, Stored> files = new ConcurrentHashMap<>();

    String put(byte[] bytes, String contentType) {
        String id = UUID.randomUUID().toString();
        files.put(id, new Stored(bytes, contentType));
        return id;
    }

    byte[] getBytes(String id) {
        Stored s = files.get(id);
        return null == s ? null : s.bytes;
    }

    String getContentType(String id) {
        Stored s = files.get(id);
        return null == s ? null : s.contentType;
    }
}
