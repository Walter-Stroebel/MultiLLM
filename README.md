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

## Get started

- **`INSTALL.md`** — requirements, build, configure, run, run as a
  system service.
- **`MANUAL.md`** — routing behavior, endpoint reference, and the
  local-sampling-parameter experiments (personas, `/v1/think`).
- **`CLAUDE.md`** — architecture notes and conventions, for anyone
  extending the code.

## Status

The gateway core — OpenAI-compatible `/v1/chat/completions` (buffered
and streaming), `/v1/files` upload/download, OpenRouter-style
candidate routing (host-pinning, provider.order/ignore/allow_fallbacks,
vision as a hard capability boundary, cooldown/retry on connection
failure) — is implemented and load-tested clean to 64 concurrent
requests against a real llama-server backend with zero failures.
Concurrency limiting is intentionally left to each backend (e.g.
llama-server's own slot count); MultiLLM's job is routing and
failover, not admission control.
