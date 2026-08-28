# CLAUDE.md
This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Status
Skeleton only — no functional design decided yet. Sibling project
`~/github/ttok` (see its `CLAUDE.md`) is the closest prior art: a
headless Java 17 CLI that talks to predator's/victus's
OpenAI-compatible `llama-server` via a plain `java.net.http.HttpClient`
wrapper (`LlamaClient`), no MCP envelope needed for plain text prompts.
Whatever MultiLLM becomes, it likely reuses that call pattern.

## Build and Run
```bash
mvn package
java -jar target/MultiLLM-1.0-jar-with-dependencies.jar
```

## Known issue: concurrent vision requests can return corrupted replies

Under real concurrent load (multiple simultaneous multimodal requests
against predator/victus's `gemma-vision` llama-server instances),
occasional replies come back claiming no image was provided even
though a correct, distinct image was sent — the model answers as if
the request were text-only.

This was investigated at length and confirmed to be a llama-server-side
bug, not a MultiLLM bug: instrumented every outgoing request with a
hash/length of its base64 image payload and its prompt text, and
verified each corrupted reply corresponded to a request that
demonstrably carried a correct, unique image. Ruled out along the way:

- Oversized/corrupt image files — all test images work perfectly
  sent single-shot, no concurrency.
- MultiLLM's own concurrency handling — at the time, `Endpoint`'s
  single-flight semaphore was confirmed via live `/slots` polling to
  never allow more than one in-flight request per endpoint from
  MultiLLM's traffic. (That semaphore no longer exists post-rewrite —
  see note below.)
- Mixing vision (predator/victus) and text (legion/Ollama) traffic in
  the same batch — reproduces with vision-only traffic too.
- `--slot-prompt-similarity` LCP-based slot cache reuse (llama-server's
  default 0.10 threshold reusing a slot's cached KV state for a
  new, unrelated request) — disabled entirely (`--slot-prompt-similarity
  0`) as a test; corruption still reproduced identically, so this was
  not it either. Reverted after ruling out.

Root cause lives somewhere in llama-server's 4-slot concurrent
scheduling and how it manages multimodal (image) context per slot
under load — out of scope for this project to fix upstream.

**Mitigation already in place**: `Router` detects the "claims no image
was given" signature in a reply (`looksLikeMissingImage`), cools the
offending endpoint down, and retries the next candidate — this recovers
correctly in practice, at the cost of an occasional wasted call. This
is the general posture for OpenAI-compatible-spec gaps and
backend-specific quirks generally: work around them at the router
level with detection and retry, don't chase every backend's individual
bugs upstream. There will always be another one.

**Note (post-rewrite, current architecture)**: the shared-queue
`Router` described above was replaced by `RoutePlanner` +
`GatewayServer` (an OpenAI-compatible HTTP gateway on the JDK's
built-in `HttpServer`, one thread per request via a cached thread
pool). There is no queue and no per-endpoint concurrency cap anymore —
`Endpoint` carries no semaphore, only `cooldownUntilMillis` for
failure-triggered cooldown. Concurrency limiting for local backends is
left entirely to llama-server's own slot count; MultiLLM only picks a
candidate endpoint order and falls through on connectivity failure via
`EndpointUnreachableException`, it doesn't throttle admission. Any
`looksLikeMissingImage`-style detection living on today's code path
would need to be re-verified against the current `LlamaClient`/
`RoutePlanner`, not assumed carried over from the old `Router`.

## HTTP transport: nethttp's `Rest`, not raw `HttpURLConnection`

`LlamaClient`'s buffered `ask()` path runs on the vendored
`nl.infcomtec.nethttp.Rest` (sources under `src/main/java/nl/infcomtec/nethttp/`
+ `.../jacksonwrap/`, copied from `~/github/catalog/nethttp` the same way
`advswing` is vendored — divergences from the catalog original are
flagged in-file with a `NOTE (MultiLLM vendored copy)` comment). `Rest`
is a non-throwing wrapper that captures a full `Transcript` of every
call (assembled URL, request as sent, response as received, timing,
errors) as a side effect — that transcript is what the call inspector
renders.

Two things about it that must **not** be "modernized" away:
- **HTTP/1.1 pin** (`Rest.useHttp2` left `false`). `HttpClient` defaults
  to HTTP/2 and negotiates an upgrade against every endpoint; against
  HTTP/1.1-only local `llama-server` boxes under concurrent load that
  stalled requests client-side for minutes. This is the same reason the
  transport was raw `HttpURLConnection` before.
- **No timeout, anywhere.** Neither `Rest` nor `LlamaClient` sets a
  connect or read timeout — a call waits as long as the backend takes,
  a closed connection is reported as the failure it is. Walter's call,
  matching `Rest`'s design stance: latency expectations are the caller's
  concern, never the transport's.

`LlamaClient.translate()` maps `Rest`'s *collected* (never thrown)
failures onto the exception `RoutePlanner` keys its fallthrough on. The
streaming path (`askStreaming`) stays on `HttpURLConnection` — `Rest`
buffers the whole body, the SSE relay needs the raw `InputStream`.

### `EndpointUnreachableException` means no HTTP response arrived — nothing else

The split for "cool this endpoint down and try the next candidate" vs
"return this error to the caller" is **whether an HTTP response came
back at all**, not the status code:

- **A response arrived — any status, 500 included.** Returned to the
  caller as a plain `IOException` with the status and body, shown in
  full in the inspector. `RoutePlanner` does *not* cool the endpoint
  down and does *not* fall through. A 500 for "no such model", a 404,
  a 429, a 400 for a malformed body are all common and are exactly what
  you want to see, not paper over — the endpoint answered, it is not
  sick. (`checkStatus` is now just: non-200 → `IOException`. The old
  `>= 500 → EndpointUnreachableException` heuristic is gone — it hid
  these and cooled down healthy boxes.)
- **No response** — connection refused, DNS failure, no route, TLS
  handshake failure, socket dropped before a status line: that, and
  only that, is `EndpointUnreachableException` → cooldown + next
  candidate.

This is the "DIY toolbox, not appliance" stance (see the memory of the
same name): hand the backend's actual response to the user, don't
silently retry around it. `translate()` checks `transcript.status > 0`
first — if a response arrived, the collected exception (e.g. a JSON
parse error on a 500's HTML body) is irrelevant, it's still an
application outcome.

## Call inspector (`CallLog` + `InspectorFrame` + `CallDetailFrame`)

Config-gated Swing window listing the last N LLM calls; click one for a
tabbed view of the full request/response. Off unless
`config/endpoints.json` uses the wrapper form
(`{ "endpoints": [...], "inspector": { "enabled": true, ... } }` — see
`config/endpoints.inspector-example.json`); a bare array still parses
and runs headless. **D & T, A at the most, never P** — enabling it
turns a headless backend into one with a GUI and surfaces call detail
(credentials included, unless `revealSecrets` is left false) that only
the key holder should authorise. `CallLog` is a bounded ring (`maxCalls`
from config) — a no-op until `CallLog.enable()` runs at startup, so a
plain production gateway retains nothing. Cross-thread: every request
thread calls `CallLog.record`, the EDT reads via `snapshot()`, all under
one monitor; the frame marshals every update onto the EDT with
`invokeLater`.

## Java Style
General idiom (no lambdas/method references, threading defaults, no Spring,
etc.) is covered by the `java-style` skill. Repo-specific on top of that,
same as sibling `ttok`/`Voynich`/`infimg` repos: plain `long` epoch-millis
for timestamps, not `java.time`; public fields on plain data holders, no
getter/setter ceremony where the class is just a struct.
