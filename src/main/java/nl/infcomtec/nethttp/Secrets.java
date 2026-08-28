/*
 *  Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.nethttp;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * The redaction policy for {@link Transcript#render()}.
 * <p>
 * <b>Rule this enforces:</b> credentials must never be written to an
 * acceptance or production log. A transcript is a debugging aid whose
 * intended use is {@code log.error(rest.transcriptText())} on failure — which
 * is exactly how an {@code Authorization} header or an {@code appid} query
 * parameter ends up in a log. So the <i>rendered</i> transcript masks
 * known-sensitive header and parameter values <b>by default</b>. The caller
 * opts in to seeing real values (see {@link Rest#revealSecretsInTranscript});
 * they do not opt out of hiding them.
 * <p>
 * The raw {@link Transcript} object still holds the true values in memory
 * (that is what the opt-in reveal reads); only {@link Transcript#render()}
 * masks them.
 * <p>
 * Scope: credentials travel in <b>request headers and query parameters</b>,
 * and those are the only things this touches. Response/request <i>bodies</i>
 * are not scanned — in A/P you run with {@link Rest#captureBody}{@code =false}
 * so bodies are not retained at all, and in D you are looking at the body on
 * purpose. No body parsing, no regex.
 *
 * @author Walter Stroebel
 */
public final class Secrets {

    private Secrets() {
    }

    public static final String MASK = "<redacted>";

    /**
     * Header names whose value is masked. Matched case-insensitively, exact
     * name. The defaults cover the standard credential-bearing headers.
     */
    private static final Set<String> HEADER_NAMES = new CopyOnWriteArraySet<>(Set.of(
            "authorization",
            "proxy-authorization",
            "cookie",
            "set-cookie",
            "x-api-key",
            "x-auth-token",
            "api-key",
            "apikey"
    ));

    /**
     * Query-parameter name fragments whose value is masked. Matched
     * case-insensitively as a <i>substring</i> of the param name, so
     * {@code openweather_appid} is caught by {@code appid}. The defaults
     * cover the names APIs commonly use for a key/token/secret/signature.
     */
    private static final Set<String> PARAM_FRAGMENTS = new CopyOnWriteArraySet<>(Set.of(
            "appid",
            "api_key",
            "apikey",
            "api-key",
            "access_token",
            "auth_token",
            "authtoken",
            "client_secret",
            "password",
            "passwd",
            "secret",
            "token",
            "signature",
            "sig",
            "key"
    ));

    /**
     * Add a header name and/or param-name fragment to redact, for an API that
     * uses a non-standard name (e.g. {@code "x-acme-credential"}). Idempotent;
     * applies process-wide. There is deliberately no "remove" — you do not
     * un-redact a credential name.
     */
    public static void redactAlso(String nameOrFragment) {
        if (nameOrFragment == null || nameOrFragment.isBlank()) {
            return;
        }
        String v = nameOrFragment.toLowerCase();
        HEADER_NAMES.add(v);
        PARAM_FRAGMENTS.add(v);
    }

    // NOTE (MultiLLM vendored copy): these three helpers were widened from
    // package-private to public so the call-inspector UI (a different package)
    // can mirror render()'s masking in its field-by-field tabs. The catalog
    // original keeps them package-private. Nothing else here diverges.
    public static boolean isSensitiveHeader(String name) {
        return name != null && HEADER_NAMES.contains(name.toLowerCase());
    }

    static boolean isSensitiveParam(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase();
        for (String frag : PARAM_FRAGMENTS) {
            if (lower.contains(frag)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Mask the value of any sensitive query parameter inside an already-built
     * URL string, so the assembled-URI line does not leak what the params list
     * already hid. Plain {@code &}/{@code =} splitting — the same shape
     * {@code Rest.assembleUri()} used to build the string. Everything else is
     * left byte-for-byte.
     */
    public static String maskUrlQuery(String url) {
        if (url == null) {
            return null;
        }
        int q = url.indexOf('?');
        if (q < 0) {
            return url;
        }
        StringBuilder out = new StringBuilder(url.substring(0, q + 1));
        String[] pairs = url.substring(q + 1).split("&", -1);
        for (int i = 0; i < pairs.length; i++) {
            if (i > 0) {
                out.append('&');
            }
            String pair = pairs[i];
            int eq = pair.indexOf('=');
            if (eq >= 0 && isSensitiveParam(pair.substring(0, eq))) {
                out.append(pair, 0, eq + 1).append(MASK);
            } else {
                out.append(pair);
            }
        }
        return out.toString();
    }
}
