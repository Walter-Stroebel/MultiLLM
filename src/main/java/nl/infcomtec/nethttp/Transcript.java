/*
 *  Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.nethttp;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.LinkedList;
import java.util.List;
import nl.infcomtec.jacksonwrap.JSON;

/**
 * A full record of one {@link Rest} call: the request as actually sent and the
 * response as actually received, plus how the URL was assembled and how long
 * each half took. "{@code tcpdump -A} of exactly one call", already parsed.
 * <p>
 * Populated as a side effect of {@link Rest#bodyText()} — the caller wires up
 * nothing. Read it back with {@link Rest#transcript()} (the object) or
 * {@link Rest#transcriptText()} ({@link #render()}, a human-readable block).
 * <p>
 * The point it addresses: API-call code is invented fresh from the docs and
 * debugged in situ, and the one thing you cannot see is <b>what your code
 * actually put on the wire</b> — especially the assembled query string. This
 * makes that visible without a proxy or a second process.
 * <p>
 * All fields are plain and public — this is a record you inspect, print, or
 * serialize with jacksonwrap, not an object with behaviour. The fields hold
 * the <b>true</b> request values; {@link #render()} is where credentials are
 * masked (see {@link Secrets}), so anything you log goes through render(),
 * never {@code field}-by-{@code field} string building of your own.
 *
 * @author Walter Stroebel
 */
public class Transcript {

    /**
     * Set from {@link Rest#revealSecretsInTranscript} at render time. When
     * false (the default), {@link #render()} masks sensitive header and param
     * values. When true, it shows them — a D-only opt-in.
     */
    boolean revealSecrets;

    // ---- how the URL was built ----

    /** The {@code base(...)} value, if {@code base()}/{@code param()} was used. */
    public String base;
    /** The query params as given (raw, un-encoded values; null = empty value). */
    public final List<Rest.Param> params = new LinkedList<>();
    /** One line saying how {@link #uri} was arrived at (base+params / url() verbatim / ...). */
    public String uriSource;
    /** The final URI actually requested. */
    public String uri;

    // ---- request as sent ----

    public String verb;
    public final List<Rest.Header> requestHeaders = new LinkedList<>();
    /** Request body as sent, or null if none. */
    public String requestBody;

    // ---- response as received ----

    /** HTTP status code, or 0 if the call never got a response. */
    public int status;
    public List<Rest.Header> responseHeaders = new LinkedList<>();
    /** Response body verbatim, or null if the call failed before a response. */
    public String responseBody;

    // ---- timing + failures ----

    public long startedAt;
    public long endedAt;
    /** Collected failures (class + message), same list as {@link Rest#iterator()}. */
    public List<String> errors = new LinkedList<>();

    public long totalMillis() {
        return endedAt - startedAt;
    }

    /**
     * Render as a readable block: request line, assembled URL, params (empties
     * flagged), headers and body as sent, a timing/status line, then response
     * headers and body. Long bodies are shown in full — this is for a human
     * looking at one call, not a log firehose. A JSON body is pretty-printed
     * (see {@link #prettyIfJson}); anything that does not parse is shown
     * byte-for-byte.
     * <p>
     * <b>Credentials in sensitive headers and query parameters are masked</b>
     * unless {@link #revealSecrets} is set (a D-only opt-in via
     * {@link Rest#revealSecretsInTranscript}). This is what makes
     * {@code log.error(rest.transcriptText())} safe to leave in acceptance and
     * production code. See {@link Secrets}.
     */
    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== nethttp transcript ===\n");
        sb.append("when   : ").append(Instant.ofEpochMilli(startedAt)).append('\n');
        if (!revealSecrets) {
            sb.append("note   : sensitive headers/params masked; ")
                    .append("Rest.revealSecretsInTranscript=true to show (D only)\n");
        }

        if (base != null) {
            sb.append("base   : ").append(base).append('\n');
            if (params.isEmpty()) {
                sb.append("params : (none)\n");
            } else {
                sb.append("params :\n");
                for (Rest.Param p : params) {
                    boolean empty = p.value == null || p.value.isEmpty();
                    sb.append("         ").append(p.name).append(" = ");
                    if (empty) {
                        sb.append("(EMPTY!)");
                    } else if (!revealSecrets && Secrets.isSensitiveParam(p.name)) {
                        sb.append(Secrets.MASK);
                    } else {
                        sb.append('\'').append(p.value).append('\'');
                    }
                    sb.append('\n');
                }
            }
        }
        if (uriSource != null) {
            sb.append("assembly: ").append(uriSource).append('\n');
        }

        sb.append('\n');
        String shownUri = uri == null ? "(no URI)"
                : (revealSecrets ? uri : Secrets.maskUrlQuery(uri));
        sb.append("> ").append(verb == null ? "?" : verb).append(' ')
                .append(shownUri).append('\n');
        for (Rest.Header h : requestHeaders) {
            String hv = (!revealSecrets && Secrets.isSensitiveHeader(h.name))
                    ? Secrets.MASK : h.value;
            sb.append("> ").append(h.name).append(": ").append(hv).append('\n');
        }
        if (requestBody != null) {
            sb.append("> \n");
            for (String line : prettyIfJson(requestBody).split("\n", -1)) {
                sb.append("> ").append(line).append('\n');
            }
        } else if ("POST".equals(verb) || "PUT".equals(verb)) {
            sb.append("> (empty body)\n");
        }

        sb.append("--- ");
        if (status > 0) {
            sb.append("HTTP ").append(status).append("  ");
        } else {
            sb.append("no response  ");
        }
        sb.append("(total ").append(totalMillis()).append(" ms)\n");

        if (status > 0) {
            for (Rest.Header h : responseHeaders) {
                String hv = (!revealSecrets && Secrets.isSensitiveHeader(h.name))
                        ? Secrets.MASK : h.value;
                sb.append("< ").append(h.name).append(": ").append(hv).append('\n');
            }
            sb.append("< \n");
            String body = responseBody == null ? "" : prettyIfJson(responseBody);
            for (String line : body.split("\n", -1)) {
                sb.append("< ").append(line).append('\n');
            }
        }

        if (!errors.isEmpty()) {
            sb.append("\nerrors :\n");
            for (String e : errors) {
                sb.append("         ").append(e).append('\n');
            }
        }
        sb.append("==========================");
        return sb.toString();
    }

    /**
     * If {@code body} parses as JSON, return it pretty-printed (2-space
     * indent, via the shared jacksonwrap mapper) so a human can actually
     * read it — an API response arrives as one very long line, and it is
     * inside this text block where no editor "format JSON" command reaches.
     * If it does not parse — a non-JSON body, HTML error page, or a
     * truncated response — return it unchanged, so you still see whatever
     * did come back. Never throws.
     * <p>
     * The <b>stringified-JSON</b> case (OpenAI tool calls put the call
     * arguments in a JSON string <i>inside</i> the response JSON) is left
     * as-is on purpose: it is escaped text, not structure, and re-escaping
     * it after a re-indent would only add noise. The point of the
     * transcript is to show what was actually on the wire.
     */
    // NOTE (MultiLLM vendored copy): widened from package-private to public so
    // the call-inspector UI (a different package) can pretty-print bodies in
    // its Request/Response tabs. The catalog original keeps it package-private.
    public static String prettyIfJson(String body) {
        if (body == null) {
            return null;
        }
        String trimmed = body.stripLeading();
        if (trimmed.isEmpty() || (trimmed.charAt(0) != '{' && trimmed.charAt(0) != '[')) {
            return body; // not worth trying; also skips the common HTML error page
        }
        try {
            JsonNode tree = JSON.getMapper().readTree(body);
            return JSON.getMapper().writeValueAsString(tree);
        } catch (Exception notJson) {
            return body;
        }
    }

    @Override
    public String toString() {
        return render();
    }
}
