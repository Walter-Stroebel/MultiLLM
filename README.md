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
  model(s) each one serves, whether it can accept images, and a coarse
  cost tier (`free` / `cheap` / `expensive`).
- Runs a real work-queue/worker-pool: one dedicated thread per endpoint,
  all pulling from a single shared queue. A worker takes its next item
  the instant it's free — no per-worker assignment decision to get
  wrong, no bookkeeping to keep fair. Throughput self-balances by real
  completion speed: a fast box naturally does more work than a slow one
  simply by finishing sooner and grabbing the next item, not because
  anything measured or scored it as "faster."
- Treats the requested model as a **preference, not a requirement**.
  If nothing serving that model is free right now but another endpoint
  is idle, the idle one answers using its own model instead of the
  caller sitting behind a busy queue for no reason. The one hard
  boundary this preference doesn't cross: an image-bearing request is
  only ever handed to an endpoint that actually declares vision support.
- Prefers **free** endpoints over `cheap`/`expensive` ones as a tier,
  not a hard rule that can never fall through — `expensive` is only
  ever reached when nothing free-and-local could serve the request at
  all.
- Falls back automatically if an endpoint is unreachable, and recovers
  gracefully from a corrupted reply (a real llama.cpp quirk under
  concurrent vision load — see `CLAUDE.md`) without ever freezing an
  entire capability class or hanging a caller indefinitely.

Verified under real, sustained concurrent load: 228 requests (28 vision
+ 200 text) fired against three real machines finished in ~2m15s,
228/228 succeeded, GPU load spread almost perfectly evenly across all
three (~32–34% share each, all sustained 80%+ utilization the whole
run) — not because of a scoring formula, just because "whoever's free
grabs the next job" is a self-balancing mechanism.

## Why concurrency changes the economics, not just the speed

The 228-request test above finished in ~2 minutes across three
GPUs you already own. The same 228 calls, answered one at a time by a
single hosted model, would take roughly 5x as long purely from lacking
that concurrency — a single conversational stream cannot fork itself
into three workers the way this router forks work across three real
machines.

The obvious rebuttal is "just run three hosted agent sessions in
parallel, then" — but that doesn't actually get you the same trade.
Each session re-pays its own fixed setup cost (system prompt, tool
definitions, context bootstrapping) before producing a single useful
token, so three parallel sessions burn roughly 3x the tokens of one
sequential run doing the *same* total work, not the same total spend
split three ways. And it's still not guaranteed to be faster in
practice — hosted concurrency has its own tax: rate limits, queueing
behind other tenants' traffic, orchestration overhead to fan the work
out and reassemble it coherently. Three GPUs you own outright have none
of that: no metering, no per-token toll, no other tenant to queue
behind, and their economics only get *better* the more concurrent use
you put through them, since the hardware is a sunk cost either way.
That's not a trick this router is pulling — it's what the numbers look
like once you're not renting compute by the token anymore.

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
        "costTier": "free",
        "vision": true
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

```
Loaded 3 endpoint(s) from config/endpoints.json
[served by victus, model gemma-vision] Hello there, how are you?
```

MultiLLM loads the config, queues the request, and prints which
endpoint actually answered and with which model — useful for seeing
the preference-vs-substitution behavior in action.

## Status

The routing core (single shared work queue, model-as-preference,
vision as a hard capability boundary, cooldown/retry on failure) is
implemented and verified under real sustained concurrent load — see
the 228-request stress test result above. Still a foundation to build
on, not a finished product: no streaming, no conversation history (by
design — each call is a stateless "ant"), and the CLI is single-prompt
in/single-reply out. See `CLAUDE.md` for architecture notes,
conventions, and a documented upstream llama-server quirk if you're
extending it.
