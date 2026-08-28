/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.multillm;

import java.util.Base64;

/**
 * Locating and handling {@code data:<mime>;base64,<payload>} URIs inside a
 * request or response body string, for the call inspector.
 * <p>
 * Two jobs:
 * <ul>
 *   <li>{@link #elideLargeBase64} — replace any base64 payload over a size
 *       threshold with a {@code ‹N KB elided (mime)›} marker, so a vision
 *       call's ~MB image blob does not bloat the retained transcript's
 *       rendered view or make the inspector tab unusable. The raw
 *       {@code Transcript} still holds the true bytes; this only trims the
 *       inspector's <i>display</i> of them.</li>
 *   <li>{@link #firstImage} — pull the first {@code data:image/*} payload
 *       back out and decode it, so the inspector can put it on the
 *       clipboard for the user to inspect (e.g. against a downscale-OCR
 *       injection, where sparse pixels only resolve into text at a reduced
 *       scale). Nothing is written to disk.</li>
 * </ul>
 * Plain string scanning throughout — {@code indexOf} on the fixed
 * {@code data:} / {@code ;base64,} tokens, no regex.
 */
final class DataUri {

    /** Payloads longer than this (in base64 chars) are elided from the inspector's body view. */
    static final int ELIDE_THRESHOLD_CHARS = 2048;

    private static final String SCHEME = "data:";
    private static final String MARKER = ";base64,";

    private DataUri() {
    }

    /** One decoded image extracted from a body: the raw bytes and their MIME type. */
    static final class Image {

        final byte[] bytes;
        final String mime;

        Image(byte[] bytes, String mime) {
            this.bytes = bytes;
            this.mime = mime;
        }
    }

    /**
     * Return {@code body} with every {@code data:<mime>;base64,<payload>}
     * whose payload exceeds {@link #ELIDE_THRESHOLD_CHARS} rewritten to
     * {@code data:<mime>;base64,‹<N> KB elided (<mime>)›}. Payloads at or
     * under the threshold, and non-base64 {@code data:} URIs, are left
     * untouched. Never throws; returns {@code body} unchanged if it holds
     * no such URI.
     */
    static String elideLargeBase64(String body) {
        if (null == body || body.indexOf(SCHEME) < 0) {
            return body;
        }
        StringBuilder out = new StringBuilder(body.length());
        int pos = 0;
        while (true) {
            int scheme = body.indexOf(SCHEME, pos);
            if (scheme < 0) {
                out.append(body, pos, body.length());
                return out.toString();
            }
            int marker = body.indexOf(MARKER, scheme);
            if (marker < 0) {
                out.append(body, pos, body.length());
                return out.toString();
            }
            int payloadStart = marker + MARKER.length();
            int payloadEnd = payloadEnd(body, payloadStart);
            String mime = body.substring(scheme + SCHEME.length(), marker);
            int payloadLen = payloadEnd - payloadStart;

            out.append(body, pos, payloadStart);
            if (payloadLen > ELIDE_THRESHOLD_CHARS) {
                out.append('‹').append(approxKb(payloadLen)).append(" KB elided (")
                        .append(mime.isEmpty() ? "no mime" : mime).append(")›");
            } else {
                out.append(body, payloadStart, payloadEnd);
            }
            pos = payloadEnd;
        }
    }

    /**
     * Decode the first {@code data:image/*;base64,...} payload in
     * {@code body}, or {@code null} if there is none / it does not decode.
     * The MIME must start {@code image/}.
     */
    static Image firstImage(String body) {
        if (null == body) {
            return null;
        }
        int pos = 0;
        while (true) {
            int scheme = body.indexOf(SCHEME, pos);
            if (scheme < 0) {
                return null;
            }
            int marker = body.indexOf(MARKER, scheme);
            if (marker < 0) {
                return null;
            }
            String mime = body.substring(scheme + SCHEME.length(), marker);
            int payloadStart = marker + MARKER.length();
            int payloadEnd = payloadEnd(body, payloadStart);
            if (mime.startsWith("image/")) {
                try {
                    byte[] bytes = Base64.getDecoder().decode(body.substring(payloadStart, payloadEnd));
                    return new Image(bytes, mime);
                } catch (IllegalArgumentException notBase64) {
                    // fall through to look for another one
                }
            }
            pos = payloadEnd;
        }
    }

    /** True if the body carries at least one {@code data:image/*} URI. */
    static boolean hasImage(String body) {
        return null != body && body.contains(SCHEME + "image/");
    }

    /**
     * The end of a base64 payload starting at {@code start}: the first
     * character that cannot be part of one (base64 is {@code A-Za-z0-9+/=},
     * and JSON puts the URI in quotes, so a {@code "} ends it; whitespace
     * and {@code )} also end it for a bare (non-JSON) body).
     */
    private static int payloadEnd(String s, int start) {
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean base64Char = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '+' || c == '/' || c == '=';
            if (!base64Char) {
                return i;
            }
        }
        return s.length();
    }

    private static long approxKb(int base64Chars) {
        // base64 encodes 3 bytes per 4 chars; round to nearest KB, min 1.
        long bytes = (long) base64Chars * 3 / 4;
        return Math.max(1, (bytes + 512) / 1024);
    }
}
