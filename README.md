# MultiLLM

A small, boring, load-bearing piece of infrastructure: an
OpenAI-compatible HTTP gateway that sits in front of any number of
`/v1/chat/completions`-speaking backends — local (llama-server,
Ollama, LM Studio, whatever) or remote/paid — and routes each request
to the right one.

## The problem this solves

Every project that touches an LLM ends up rewriting the same handful
of decisions, usually badly, usually once per project:

- Which endpoint do I call? What if it's down?
- Do I keep this local and free, or fall back to something paid?
- If I do use a paid endpoint, how do I stop it from silently burning
  money once the free tier runs out?
- If I have more than one machine capable of running a model, how do
  I actually use both instead of hardcoding one hostname?

The usual answer is "just use OpenRouter" — a single cloud API key in
front of hundreds of models. That's a fine answer if you're fine with
"cloud" by default. This project exists for people who aren't.

"The cloud" is not a neutral abstraction — it's someone else's server,
in someone else's jurisdiction, under someone else's terms of service
and content-scanning policy, and you generally have no idea which of
those actually apply to a given request until something goes wrong.
Send the wrong image or the wrong text to the wrong provider and the
failure mode isn't "slightly worse latency" — automated moderation
systems misfire on innocuous content often enough to be a documented
problem (a parent's photo of their own child, sent for a medical
reason, has been enough to trigger an account lock and a police
report). "Someone else's jurisdiction" is not an abstract risk either:
a government doesn't need to breach a provider to get at your data —
it just needs a subpoena, a national-security letter, or in an
authoritarian state a single signature, and a compliant provider hands
it over, often under a gag order that means you never find out. That
risk doesn't require the provider to be malicious, just legally
obligated — which is exactly why EU public-sector bodies have begun
actively moving workloads off US hyperscalers (Azure/AWS/GCP) rather
than trusting contractual promises about where data "lives." And the
exposure isn't only the provider's: under GDPR, sending EU personal
data to a provider/jurisdiction without adequate safeguards is a
compliance failure attributable to *you*, the data controller — "the
API I used did that automatically, I didn't realize" is not a defense
that makes the liability go away. A local model on hardware you own
has no TOS, no upstream moderation pipeline, no third-party data
processor, and nothing to subpoena but you. That's the whole case for
local-first as the *default*, not a preference: keep everything local
and free as the default, and treat any paid/remote endpoint as an
explicit, opt-in fallback — never a silent one.

The OpenAI-compatible `/v1/chat/completions` shape has become the
de-facto standard wire format regardless of who's actually serving the
model — local llama.cpp, Ollama, LM Studio, or a paid cloud API all
speak it. That means solving "route a request to the right endpoint"
once, well, in one place, is enough. One JVM install, one jar, one
config file, one gateway URL any OpenAI-compatible client can point
at — no per-project reinvention.

## What it does

MultiLLM is an HTTP server (`GatewayServer`) exposing:

- **`POST /v1/chat/completions`** — the standard OpenAI-compatible
  endpoint, both buffered and `stream: true` (SSE passthrough,
  unparsed — the backend's own framing reaches the client unmodified).
- **`POST`/`PUT /v1/files`** + **`GET /v1/files/{id}`** — a small local
  file store, so a caller with no public web server of its own can
  still hand a backend a real fetchable image URL instead of inlining
  base64.

Requests are handled by `RoutePlanner`, which builds an ordered
candidate list of configured endpoints and tries them in order, using
OpenRouter's own routing vocabulary so any OpenRouter-aware client
already knows how to steer it:

- A bare model name routes by policy: every endpoint that declares the
  model, local (`kind: "local"`) endpoints tried before remote ones,
  in config-declaration order — unless the request supplies its own
  `provider.order` (list of endpoint names, tried first in that order)
  or `provider.ignore` (names removed outright).
- `models: [...]` (OpenRouter-style) supplies fallback model names to
  also match against, if the primary model isn't served anywhere.
  `provider.allow_fallbacks: false` truncates the candidate list to
  just the first match — fail rather than try anyone else.
- A `host/model`-prefixed model name (e.g. `"predator/gemma-vision"`)
  is the sharp tool: it names exactly one configured endpoint by name,
  bypassing policy entirely — "ask that box, not whichever one policy
  would pick."
- An image-bearing request (base64 or `image_url`) is a hard boundary:
  only endpoints declaring `"vision": true` are ever candidates,
  regardless of any other routing preference.
- A connection failure (unreachable/timeout) cools that endpoint down
  for 30s and falls through to the next candidate automatically — no
  request fails just because one box is down, as long as another
  candidate exists.

## What it deliberately doesn't do

- No hidden spend, and no hidden exposure. `expensive` (and even
  `cheap`) endpoints are only ever tried because you configured and
  ordered them that way — MultiLLM never sends a request off-box
  because a remote endpoint happens to answer faster. What leaves your
  network is entirely a choice you made in the config file.
- No conversation history. Every call is one-shot and stateless — only
  the last message's content is read; multi-turn `messages` history is
  deliberately not reconstructed server-side.
- No hardware benchmarking, no GPU introspection, no scoring formula
  to tune. The config file states only what you already know (what's
  running where, what it can do); routing is a plain deterministic
  candidate walk, not a measured decision.
- No cloud dependency of any kind required to use this at all — a
  single local endpoint in the config file is a completely valid
  setup.

## Requirements

- A JVM (OpenJDK 17+). That's the entire runtime dependency for
  consumers of the built jar.
- At least one endpoint somewhere that speaks the OpenAI-compatible
  `/v1/chat/completions` API — e.g. `llama-server`, Ollama (which
  exposes this alongside its native API), LM Studio, or a paid
  provider if you choose to configure one.

## Build

```bash
mvn package
```

Produces `target/MultiLLM-1.0-jar-with-dependencies.jar` — a single
self-contained jar, nothing else to install.

## Configure

Copy the example and edit it:

```bash
cp config/endpoints.example.json config/endpoints.json
```

```json
[
    {
        "name": "predator",
        "url": "http://predator:8081",
        "models": ["gemma-vision"],
        "costTier": "free",
        "kind": "local",
        "vision": true
    },
    {
        "name": "openai-example",
        "url": "https://api.openai.com",
        "models": ["gpt-4o-mini"],
        "costTier": "cheap",
        "kind": "remote",
        "apiKey": "sk-replace-me"
    }
]
```

Every model an endpoint can be asked for must be listed explicitly,
even for a pass-through gateway like OpenRouter — no wildcard, since
that would let any request silently route to a paid model nobody
approved spending on.

`config/endpoints.json` is gitignored on purpose — it's the one file
likely to contain a real API key. Only `endpoints.example.json` (dummy
values) is tracked. If no `config/endpoints.json` exists, MultiLLM
falls back to the example file so the jar still runs out of the box.

## Run

```bash
java -jar target/MultiLLM-1.0-jar-with-dependencies.jar [port]
```

Defaults to port 8085. Starts the gateway and prints its self URL
(used for the `/v1/files` upload response) and how many endpoints it
loaded.

```bash
java -jar target/MultiLLM-1.0-jar-with-dependencies.jar
```
```
Loaded 3 endpoint(s) from config/endpoints.json
MultiLLM gateway listening on port 8085 (self URL http://legion:8085)
```

Then point any OpenAI-compatible client at it:

```bash
curl -s http://localhost:8085/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"gemma-vision","messages":[{"role":"user","content":"say hi in five words"}]}'
```

The response's `served_by` field (and `X-Served-By` header on
streamed responses) names whichever endpoint actually answered — handy
for watching the policy-vs-pinned-host routing decision in action.

A minimal Swing GUI (`DebugClient`) is also available for interactive
testing against a running gateway, if you'd rather not shell out curl
commands by hand.

## Run as a system service

A sample unit file is at `systemd/multillm.service`, matching the
style already used for this machine's `llama-server` units (runs
in-place from a repo checkout, no dedicated service user, no
sandboxing directives — adjust paths/user for your own setup):

```bash
mvn package
sudo cp systemd/multillm.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now multillm
```

## Status

The gateway core — OpenAI-compatible `/v1/chat/completions` (buffered
and streaming), `/v1/files` upload/download, OpenRouter-style
candidate routing (host-pinning, provider.order/ignore/allow_fallbacks,
vision as a hard capability boundary, cooldown/retry on connection
failure) — is implemented and load-tested clean to 64 concurrent
requests against a real llama-server backend with zero failures.
Concurrency limiting is intentionally left to each backend (e.g.
llama-server's own slot count); MultiLLM's job is routing and
failover, not admission control. See `CLAUDE.md` for architecture
notes, conventions, and a documented upstream llama-server quirk if
you're extending it.
