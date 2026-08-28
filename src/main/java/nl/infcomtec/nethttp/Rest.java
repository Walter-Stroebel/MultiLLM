/*
 *  Copyright (c) 2021-2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.nethttp;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import nl.infcomtec.jacksonwrap.JSON;

/**
 * A fluent, non-throwing wrapper around {@code java.net.http} that captures a
 * full {@link Transcript} of every call it makes.
 * <p>
 * <b>Design thesis</b> (Walter, 2026): an HTTP call failing is <b>not
 * exceptional</b>. It is a call, and a call can fail. You might want to know
 * <i>why</i> — but nothing "broke", so nothing is thrown. Every failure is
 * caught and appended to an internal list you can inspect via
 * {@link #iterator()}, {@link #hasErrors()} and {@link #firstError()}. A failed
 * call returns {@code null} from {@link #bodyText()}.
 * <p>
 * Consequences of that thesis, all deliberate — do <b>not</b> "fix" them:
 * <ul>
 *   <li>No timeout. No retry. Those are the <i>caller's</i> concern: this class
 *       has no idea what the caller is doing or what latency to expect.</li>
 *   <li>Errors are collected, never logged. The caller decides if silence is
 *       fine (fire-and-forget) or not (loop over {@code this}).</li>
 *   <li>The client is pinned to HTTP/1.1. {@code HttpClient}'s HTTP/2 default
 *       negotiates an upgrade against every endpoint and has stalled requests
 *       for minutes against HTTP/1.1-only servers under concurrent load. See
 *       {@link #useHttp2}.</li>
 * </ul>
 * <p>
 * <b>Why it captures a transcript.</b> Calling "some API" is the most common
 * thing in development, and it is invented fresh each time from the API docs
 * and debugged in situ. The docs tell you what the server <i>should</i>
 * accept; they do not tell you what your code <i>actually sent</i>. That gap —
 * especially the assembled query string, which is computed and never shown —
 * is where the time goes. So {@code Rest} owns URL assembly ({@link #base} +
 * {@link #param}) and records the request-as-sent and the response-as-received
 * as a {@link Transcript}. Think "{@code tcpdump -A} of exactly one call",
 * already parsed, no root, no second process. See {@link #transcriptText()}.
 * <p>
 * <b>Lifecycle contract.</b> A {@code Rest} is <i>single-call, single-use —
 * build it, execute it once, read what you need, discard it.</i> Do not pool
 * {@code Rest} instances, keep them in a field, or stash them in a list "for
 * diagnostics". The {@link Transcript} — and in particular the response body
 * it holds a reference to — lives exactly as long as the {@code Rest} object;
 * with per-call-and-discard use that is one young-GC cycle and costs nothing,
 * but a retained {@code Rest} pins its full response body for as long as you
 * hold it. At sustained high call rates (a 24/7 service doing many calls a
 * second) that retention is the only real memory concern, and it is entirely a
 * function of how long you keep the object.
 * <p>
 * For such a service, also set {@link #captureBody}{@code = false} once at
 * startup: the transcript then still records status, timing, the assembled
 * URL, headers and errors — everything an incident needs — but does not retain
 * response (or request) body strings.
 * <p>
 * Typical use:
 * <pre>{@code
 * Weather w = new Rest<Weather>()
 *         .base("https://api.openweathermap.org/data/2.5/weather")
 *         .param("q", city)
 *         .param("units", "metric")
 *         .param("appid", apiKey)
 *         .bodyAs(Weather.class);
 *
 * if (w == null) {                 // something went wrong — look at the wire
 *     System.err.println(rest.transcriptText());
 * }
 * }</pre>
 *
 * @param <T> the type {@link #bodyAs(Class)} will deserialize to.
 * @author Walter Stroebel
 */
public class Rest<T> implements Iterable<Exception> {

    /**
     * Set by {@link #trustAnyCertificate()}; when non-null, clients are built
     * against it instead of the JVM default trust store.
     */
    private static SSLContext trustAll;

    /**
     * Whether transcripts retain the request and response <b>body</b> strings
     * (default {@code true}). Set once to {@code false} in a long-running
     * high-throughput service so transcripts do not pin large body strings for
     * the lifetime of their {@code Rest} — everything else (status, timing,
     * assembled URL, headers, errors) is still captured. A process-wide switch
     * on purpose: it is a deployment-tier decision (on in D/T/A, off in P),
     * not something to toggle per call.
     */
    public static volatile boolean captureBody = true;

    /**
     * Whether {@link #transcriptText()} shows real credential values
     * (default {@code false} — they are masked). Leave off in T/A/P: it is
     * what makes {@code log.error(rest.transcriptText())} safe to keep in the
     * code. Set {@code true} only in a local D session when you specifically
     * need to see whether the key/token you sent is the right one. Sensitive
     * names are {@link Secrets}'s built-in list plus anything you pass to
     * {@link Secrets#redactAlso}.
     */
    public static volatile boolean revealSecretsInTranscript = false;

    /**
     * Whether the underlying {@link HttpClient} negotiates HTTP/2
     * (default {@code false} — the client is pinned to HTTP/1.1).
     * <p>
     * {@code HttpClient} defaults to {@link HttpClient.Version#HTTP_2}, which
     * means an upgrade negotiation against <i>every</i> endpoint on the first
     * call. Against HTTP/1.1-only servers (a local {@code llama-server}, many
     * small services) that negotiation has been observed — under concurrent
     * multi-endpoint load — to leave requests sitting client-side for minutes
     * before they reach the wire. HTTP/2 buys a plain one-shot REST call
     * nothing, so the default here is HTTP/1.1. Set {@code true} once at
     * startup only if you are calling an HTTP/2 endpoint and know it. A
     * process-wide, deployment-tier switch, like {@link #captureBody}.
     */
    public static volatile boolean useHttp2 = false;

    private final LinkedList<Exception> errors = new LinkedList<>();
    private final LinkedList<Header> headers = new LinkedList<>();
    private final LinkedList<Param> params = new LinkedList<>();

    private Verb verb = Verb.GET;
    private String body;
    /** Set by {@link #base}: the scheme+host+path, no query. */
    private String base;
    /** Set by {@link #url}/{@link #uri}: a whole URI, overrides base+params. */
    private URI explicitUri;

    private HttpClient client;
    private HttpRequest request;
    private HttpResponse<String> response;
    private long at;
    private Transcript transcript;

    public Rest() {
    }

    // ---- request construction (fluent) -----------------------------------

    /**
     * Set the whole request URL as one string (scheme, host, path, and any
     * query). Overrides {@link #base}/{@link #param} entirely — if you use
     * this, the {@code param(...)} list is ignored (the transcript says so).
     */
    public Rest<T> url(String url) {
        try {
            this.explicitUri = new URI(url);
        } catch (URISyntaxException ex) {
            errors.add(ex);
        }
        return invalidate();
    }

    /**
     * As {@link #url(String)} but from an already-built {@link URI}.
     */
    public Rest<T> uri(URI uri) {
        this.explicitUri = uri;
        return invalidate();
    }

    /**
     * Set the base URL — scheme, host, path, <b>no</b> query string. Query
     * parameters go through {@link #param(String, Object)}; {@code Rest}
     * assembles and URL-encodes the final URI and shows both halves in the
     * {@link Transcript}.
     */
    public Rest<T> base(String base) {
        this.base = base;
        return invalidate();
    }

    /**
     * Add one query parameter. Order is preserved. The value is
     * {@code String.valueOf}'d then URL-encoded at assembly time; a
     * {@code null} or empty value is kept (so the server sees the key) and
     * flagged in the transcript, because {@code &appid=} with nothing after
     * it is a classic silent bug.
     */
    public Rest<T> param(String name, Object value) {
        params.add(new Param(name, value == null ? null : String.valueOf(value)));
        return invalidate();
    }

    public Rest<T> verb(Verb verb) {
        this.verb = verb;
        return invalidate();
    }

    public Rest<T> header(String name, String value) {
        headers.add(new Header(name, value));
        return invalidate();
    }

    /**
     * Set the request body from a raw string (no serialization).
     */
    public Rest<T> bodyText(String body) {
        this.body = body;
        return invalidate();
    }

    /**
     * Set the request body by serializing {@code obj} to JSON via the shared
     * jacksonwrap mapper (single-line, {@code NON_NULL} — null fields omitted).
     */
    public Rest<T> body(Object obj) {
        this.body = JSON.writeValueAsString(obj);
        return invalidate();
    }

    // ---- execution ------------------------------------------------------

    /**
     * Execute (building request/client lazily as needed) and return the
     * response body as text, or {@code null} if anything failed — check
     * {@link #hasErrors()}. A {@link Transcript} is captured either way.
     */
    public String bodyText() {
        at = System.currentTimeMillis();
        transcript = new Transcript();
        transcript.startedAt = at;
        transcript.revealSecrets = revealSecretsInTranscript;

        URI target = assembleUri();
        transcript.uri = target == null ? null : target.toString();

        if (target == null) {
            // assembleUri() recorded why (bad syntax / nothing to build from).
            transcript.endedAt = System.currentTimeMillis();
            transcript.errors = snapshotErrors();
            return null;
        }

        buildRequest(target);
        if (client == null) {
            buildClient();
        }

        send();

        transcript.endedAt = System.currentTimeMillis();
        transcript.errors = snapshotErrors();

        if (response == null) {
            // send() already recorded why; don't add a derived NPE on top.
            return null;
        }
        transcript.status = response.statusCode();
        transcript.responseHeaders = flatten(response.headers().map());
        String text = response.body();
        if (captureBody) {
            transcript.responseBody = text;
        }
        return text;
    }

    /**
     * As {@link #bodyText()} but deserialize the JSON response into {@code t}.
     * Returns {@code null} on transport <i>or</i> parse failure. When
     * {@link #captureBody} is on (the default), the {@link Transcript} still
     * holds the raw response body, so a parse bug ("my POJO's fields don't
     * match what the server sent") is debugged against the exact bytes.
     */
    public T bodyAs(Class<T> t) {
        String txt = bodyText();
        if (txt == null) {
            return null;
        }
        return JSON.readValue(txt, t);
    }

    /**
     * The wall-clock millis at which the last {@link #bodyText()} started.
     */
    public long startedAt() {
        return at;
    }

    // ---- transcript --------------------------------------------------

    /**
     * The {@link Transcript} of the most recent call, or {@code null} if no
     * call has been made yet. Populated as a side effect of {@link #bodyText()}
     * — the caller wires up nothing.
     */
    public Transcript transcript() {
        return transcript;
    }

    /**
     * The most recent call rendered as a human-readable block — request line,
     * assembled URL, params (empties flagged), headers and body as sent, then
     * the status, response headers and response body. Roughly {@code curl -v}
     * out, or {@code tcpdump -A} scoped to this one call. {@code "(no call
     * made yet)"} if there is nothing to show.
     */
    public String transcriptText() {
        return transcript == null ? "(no call made yet)" : transcript.render();
    }

    // ---- TLS -----------------------------------------------------------

    /**
     * Install a process-wide all-trusting {@link SSLContext} so subsequent
     * {@code Rest} instances accept <b>any</b> server certificate, valid or
     * not, self-signed or not, expired or not.
     * <p>
     * This disables the entire point of TLS authentication. Use it only when
     * talking to a box <i>you own</i> over a network <i>you control</i>
     * (a lab VPS with a self-signed cert, a localhost service) where you have
     * out-of-band certainty about the endpoint. Never against anything on the
     * public internet you do not physically administer.
     * <p>
     * Note the protocol string is {@code "TLS"} — the original 2021 code used
     * {@code "SSL"}, which selects a deprecated, insecure protocol family.
     */
    public static void trustAnyCertificate() {
        TrustManager[] yesMan = {new TrustEverything()};
        try {
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, yesMan, new SecureRandom());
            trustAll = sc;
        } catch (GeneralSecurityException ex) {
            // Consistent with the whole-class thesis: nothing throws. If TLS
            // itself is unavailable the next request will fail and land in
            // errors like any other failure.
        }
    }

    // ---- error inspection --------------------------------------------

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    public Exception firstError() {
        return errors.peekFirst();
    }

    @Override
    public Iterator<Exception> iterator() {
        return errors.iterator();
    }

    // ---- internals -------------------------------------------------

    private Rest<T> invalidate() {
        request = null;
        response = null;
        return this;
    }

    /**
     * Produce the URI to actually call, and record on the transcript exactly
     * how it was arrived at. Returns {@code null} (and adds to {@code errors})
     * if there is nothing to build from or the result is not a valid URI.
     */
    private URI assembleUri() {
        if (explicitUri != null) {
            transcript.uriSource = params.isEmpty()
                    ? "url()/uri() — passed verbatim"
                    : "url()/uri() — passed verbatim; param() list IGNORED";
            return explicitUri;
        }
        if (base == null) {
            errors.add(new IllegalStateException(
                    "no URL: call base(...)+param(...) or url(...) before executing"));
            return null;
        }
        transcript.base = base;
        StringBuilder sb = new StringBuilder(base);
        boolean first = base.indexOf('?') < 0;
        for (Param p : params) {
            sb.append(first ? '?' : '&');
            first = false;
            sb.append(enc(p.name)).append('=').append(enc(p.value == null ? "" : p.value));
            transcript.params.add(new Param(p.name, p.value));
        }
        transcript.uriSource = params.isEmpty()
                ? "base() only, no params"
                : "base() + " + params.size() + " param(); Rest assembled and encoded the query";
        try {
            return new URI(sb.toString());
        } catch (URISyntaxException ex) {
            errors.add(ex);
            return null;
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private void buildRequest(URI target) {
        HttpRequest.Builder b = HttpRequest.newBuilder(target);
        switch (verb) {
            case GET:
                b.GET();
                break;
            case DELETE:
                b.DELETE();
                break;
            case POST:
                b.POST(HttpRequest.BodyPublishers.ofString(nz(body)));
                break;
            case PUT:
                b.PUT(HttpRequest.BodyPublishers.ofString(nz(body)));
                break;
            default:
                break;
        }
        for (Header h : headers) {
            b.header(h.name, h.value);
        }
        request = b.build();

        transcript.verb = verb.name();
        for (Header h : headers) {
            transcript.requestHeaders.add(new Header(h.name, h.value));
        }
        if (captureBody) {
            transcript.requestBody = body;
        } else if (body != null) {
            transcript.requestBody = "(" + body.length() + " chars, not retained)";
        }
    }

    private void buildClient() {
        HttpClient.Builder b = HttpClient.newBuilder();
        b.version(useHttp2 ? HttpClient.Version.HTTP_2 : HttpClient.Version.HTTP_1_1);
        if (trustAll != null) {
            b.sslContext(trustAll);
        }
        client = b.build();
    }

    private void send() {
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException ex) {
            errors.add(ex);
            // Restore the flag the checked exception cleared, so callers that
            // poll interruption still see it. This IS the correct handling.
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            errors.add(ex);
        }
    }

    private List<String> snapshotErrors() {
        if (errors.isEmpty()) {
            // The success path: allocate nothing.
            return List.of();
        }
        List<String> out = new LinkedList<>();
        for (Exception ex : errors) {
            out.add(ex.getClass().getSimpleName()
                    + (ex.getMessage() == null ? "" : ": " + ex.getMessage()));
        }
        return out;
    }

    private static List<Header> flatten(Map<String, List<String>> map) {
        List<Header> out = new LinkedList<>();
        for (Map.Entry<String, List<String>> e : map.entrySet()) {
            for (String v : e.getValue()) {
                out.add(new Header(e.getKey(), v));
            }
        }
        return out;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    // ---- value types ---------------------------------------------

    /**
     * The "trust anything" {@link X509TrustManager} behind
     * {@link #trustAnyCertificate()}: every check is a no-op, so no
     * certificate is ever rejected. Named (not anonymous) so it shows up
     * plainly in a stack trace if it ever ends up somewhere it shouldn't.
     */
    private static final class TrustEverything implements X509TrustManager {

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
            // deliberately trusts everything — see class javadoc
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
            // deliberately trusts everything — see class javadoc
        }
    }

    /**
     * One HTTP header, or one flattened response header line.
     */
    public static final class Header {

        public final String name;
        public final String value;

        public Header(String name, String value) {
            this.name = name;
            this.value = value;
        }
    }

    /**
     * One query parameter, name plus raw (un-encoded) value; a {@code null}
     * value means the key was given with nothing after the {@code =}.
     */
    public static final class Param {

        public final String name;
        public final String value;

        public Param(String name, String value) {
            this.name = name;
            this.value = value;
        }
    }

    public enum Verb {
        GET, POST, PUT, DELETE
    }
}
