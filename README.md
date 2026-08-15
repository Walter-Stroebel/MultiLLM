# MultiLLM

A small, boring, load-bearing piece of infrastructure: a client-side
router/load-balancer that sits in front of any number of
OpenAI-compatible chat-completion endpoints — local (llama-server,
Ollama, LM Studio, whatever) or remote — and picks the right one for
each request.

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
config file — no per-project reinvention.

## What it does

- Reads a pool of endpoints from a config file: name, URL, which
  model(s) each one serves, and a coarse cost tier
  (`free` / `cheap` / `expensive`).
- Routes each request to a **free** endpoint that serves the requested
  model whenever one is available. Only falls through to `cheap`, then
  `expensive`, when nothing free-and-local can serve that model —
  never the other way around.
- Among several equally-tiered candidates, picks the least busy one
  (fewest requests currently in flight), tie-broken by each endpoint's
  own measured tokens/second — no need to know or guess which of your
  machines is faster, MultiLLM measures it from real traffic.
- Falls back automatically if an endpoint is unreachable (down,
  timed out, ssh flaky) — the failed endpoint is put into a short
  cooldown and the next-best candidate is tried instead, rather than
  the whole request just failing.
- Never retries a real application error (malformed response, bad
  request) as if it were an outage — that's a bug to surface, not a
  transient condition to paper over.

## What it deliberately doesn't do

- No hidden spend, and no hidden exposure. `expensive` (and even
  `cheap`) endpoints are only used because you configured them, and
  only when nothing free-and-local could serve the model — MultiLLM
  never sends a request off-box just because a remote endpoint happens
  to answer faster. What leaves your network is entirely a choice you
  made in the config file, not a default you have to notice and turn off.
- No hardware benchmarking, no GPU introspection, no scoring formula
  for you to tune. The config file states only what you already know
  (what's running where); MultiLLM measures the rest itself from
  ordinary traffic.
- No cloud dependency of any kind required to use this at all — a
  single local endpoint in the config file is a completely valid setup.

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
        "costTier": "free"
    },
    {
        "name": "openai-example",
        "url": "https://api.openai.com",
        "models": ["gpt-4o-mini"],
        "costTier": "cheap",
        "apiKey": "sk-replace-me"
    }
]
```

`config/endpoints.json` is gitignored on purpose — it's the one file
likely to contain a real API key. Only `endpoints.example.json` (dummy
values) is tracked. If no `config/endpoints.json` exists, MultiLLM
falls back to the example file so the jar still runs out of the box.

## Run

```bash
java -jar target/MultiLLM-1.0-jar-with-dependencies.jar <model> <prompt...>
```

Example:

```bash
java -jar target/MultiLLM-1.0-jar-with-dependencies.jar gemma-vision "say hi in five words"
```

MultiLLM loads the config, picks the best available endpoint serving
`<model>` under the policy above, and prints the reply.

## Status

Early — the routing core (config → rank by cost tier and load →
call → fall back on failure) works and is exercised against real
endpoints, but this is still a foundation, not a finished product. See
`CLAUDE.md` for architecture notes and conventions if you're extending
it.
