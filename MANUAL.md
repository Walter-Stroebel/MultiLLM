# MultiLLM Manual

See `INSTALL.md` first if you haven't got a gateway running yet.

## What it does

MultiLLM is an HTTP server (`GatewayServer`) exposing:

- **`POST /v1/chat/completions`** — the standard OpenAI-compatible
  endpoint, both buffered and `stream: true` (SSE passthrough,
  unparsed — the backend's own framing reaches the client unmodified).
- **`POST`/`PUT /v1/files`** + **`GET /v1/files/{id}`** — a small local
  file store, so a caller with no public web server of its own can
  still hand a backend a real fetchable image URL instead of inlining
  base64.
- **`POST /v1/think`** (experimental, unreleased) — see "Concept:
  messing with the parameters" below.

## Routing

Requests to `/v1/chat/completions` are handled by `RoutePlanner`, which
builds an ordered candidate list of configured endpoints and tries them
in order, using OpenRouter's own routing vocabulary so any
OpenRouter-aware client already knows how to steer it:

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

## Example: a plain chat request

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

## What it deliberately doesn't do

- No hidden spend, and no hidden exposure. `expensive` (and even
  `cheap`) endpoints are only ever tried because you configured and
  ordered them that way — MultiLLM never sends a request off-box
  because a remote endpoint happens to answer faster. What leaves your
  network is entirely a choice you made in the config file.
- No conversation history. Every call is one-shot and stateless — only
  the last message's content is read; multi-turn `messages` history is
  deliberately not reconstructed server-side. The one exception is a
  leading `system`-role message, which is preserved and forwarded
  separately (needed for OpenAI-style clients that send a system+user
  pair, e.g. agentic tool-calling clients).
- No hardware benchmarking, no GPU introspection, no scoring formula
  to tune. The config file states only what you already know (what's
  running where, what it can do); routing is a plain deterministic
  candidate walk, not a measured decision.
- No cloud dependency of any kind required to use this at all — a
  single local endpoint in the config file is a completely valid
  setup.
- No automatic load-spreading across equivalent local boxes (e.g. two
  identical llama-server instances). Which box handles a given
  workload is always the caller's explicit choice (via `host/model`
  addressing or a persona's `hostEndpoint`), never something MultiLLM
  decides for you — see "Personas are pinned to one box, on purpose"
  below.

## Concept: messing with the parameters

One thing you can't do with a hosted/"store bought" LLM API is vary its
sampling parameters (`temperature`, `top_p`, `top_k`, `min_p`,
`repeat_penalty`) per request — most hosted APIs expose `temperature`
at best, if that. A local backend like llama-server has no such
restriction: it accepts all of these on every `/v1/chat/completions`
call, no server restart required to change them.

That opens up an experiment: push a model into a deliberately
high-variance sampling regime — colloquially, "feed it the equivalent
of LSD" — and see what comes out, then have a second, soberly-sampled
pass of the same (or another) model make sense of the result. MultiLLM
has two features built around this.

### Personas: named sampling overrides

A **persona** (`config/personas.json`, optional — see
`personas.example.json`) is a named alias that pins one exact
endpoint+model and applies a fixed set of sampling overrides:

```json
[
    {
        "name": "drunk-gemma4",
        "hostEndpoint": "predator",
        "model": "gemma-vision",
        "temperature": 4.0,
        "topP": 0.98,
        "topK": 300,
        "minP": 0.001,
        "repeatPenalty": 1.05
    },
    {
        "name": "sober-gemma4",
        "hostEndpoint": "predator",
        "model": "gemma-vision",
        "temperature": 0.2
    }
]
```

Once configured, a persona name is used exactly like a model name in
`/v1/chat/completions`:

```bash
curl -s http://localhost:8085/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"drunk-gemma4","messages":[{"role":"user","content":"..."}]}'
```

A persona resolves before any normal routing policy — like `host/model`
addressing, it names one exact backend, with no fallback and no
candidate list, since the entire point is to ask *that* model, sampled
*that* particular way.

**What actually happens at high temperature, for the record** (findings
from testing gemma-vision — see the caveat on generality below):
coherence does not degrade gradually. In a normal `top_k`/`min_p` range
(e.g. `top_k` 300, `min_p` 0.001), even `temperature` 4.0 stays fully
grammatical and well-argued — the model doesn't slur, it becomes *more
confident about inventing plausible-sounding technical jargon that
doesn't exist* (invented acronyms, invented formulas, invented named
concepts, stated with zero hedging). Remove the sampling floor entirely
(`top_k` 0, `min_p` 0) at high temperature and it falls off a cliff
instead of degrading smoothly: pure multilingual token soup, or a
backend request failure because the output can't even be parsed as
valid chat. That collapse mode is also dramatically slower to generate
— don't disable both floors at once expecting a quick, if garbled,
answer.

**Caveat**: this was tested against exactly one model (gemma-vision)
across a handful of prompts. It's a real, reproducible finding for that
model, not a universal law of LLM sampling — a different model's
tokenizer or training could behave quite differently at the same
settings. Treat the numbers above as a starting point to explore from,
not a spec.

### `/v1/think`: an automated divergent-then-convergent pass

`POST /v1/think` runs the two-step pattern above in one request: a
divergent persona generates raw, high-variance material, then a
convergent persona — **explicitly told which persona produced the
input and its sampling settings** — is asked to sort the material into
genuine insight versus confident fabrication, with reasoning per item.

```bash
curl -s http://localhost:8085/v1/think \
  -H "Content-Type: application/json" \
  -d '{"prompt":"Should we colonize space via stratospheric balloon launch instead of rockets?"}'
```

```json
{
  "id": "think-...",
  "prompt": "...",
  "divergent": {"persona": "drunk-gemma4", "servedBy": "predator", "content": "..."},
  "convergent": {
    "persona": "sober-gemma4", "servedBy": "predator",
    "items": [
      {"claim": "...", "verdict": "fabrication", "reasoning": "..."},
      {"claim": "...", "verdict": "insight", "reasoning": "..."}
    ],
    "summary": "..."
  }
}
```

`divergentPersona`/`convergentPersona` are optional request fields,
defaulting to `"drunk-gemma4"`/`"sober-gemma4"`. If the convergent
(judge) call fails, the request still returns 200 with `divergent`
populated, `convergent: null`, and an `error` field — a failed judge
pass shouldn't discard an already-successful, possibly slow, divergent
generation.

**Why the judge is told the origin, on purpose**: the interesting
question isn't whether a model can detect its own hallucination blind
— that's most existing hallucination-detection work, and it's hard
precisely because a model has no special access to its own reliability
just by re-reading its output. The interesting question is whether a
*second* pass, explicitly told how the first pass was produced, can use
that as a prior — treating unusual claims as candidates for either
"insight the sober pass wouldn't have reached alone" or "confident
fabrication," rather than defaulting to trusting fluency as truth. This
is deliberately not a fair blind test; awareness of origin is the whole
point.

**Warning — the judge's own verdict is not ground truth.** In practice
the judge has correctly *flagged* a claim as fabricated while its
stated *reasoning* for the flag was vague or slightly off from the
actual underlying error. Sorting insight from fabrication is not the
same as being right about *why* — `/v1/think`'s output is still
unverified LLM output at every stage, judge included, and needs the
same "don't trust it, verify" posture as any other model output.

**The bigger frame this sits in**: naive always-on chain-of-thought
conflates divergence (generating candidate reasoning) and convergence
(judging it) into one undifferentiated blob, often not even honest
deliberation but post-hoc narration dressed as reasoning. Naive
anti-hallucination tuning (low temperature everywhere, tight sampling,
heavy refusal training) "fixes" fabrication by deleting the divergent
mode outright — which also deletes genuine creative synthesis, since
both ride the same underlying mechanism (reaching for a
low-probability-but-fitting continuation). The persona +
`/v1/think` pattern is an attempt at the shape that actually works:
divergence and convergence as separate, deliberate, explicitly
operator-triggered steps, with the fabulation happening in the open
rather than hidden inside a trace pretending to be real thought.

### Personas are pinned to one box, on purpose

A persona's `hostEndpoint` names exactly one configured endpoint —
never a policy-based choice among several equivalent boxes, even if you
have two. This is deliberate: which physical machine takes a given
workload is a decision the user makes explicitly (e.g. "run
`drunk-gemma4` on predator, keep victus free for something else"), not
something MultiLLM should infer or auto-balance on your behalf.

This also means personas are effectively a local-only feature. Nothing
stops you from pointing a persona's `hostEndpoint` at a remote gateway
like OpenRouter, but two things make it a poor fit in practice: (1)
OpenRouter is itself a router — pinning to it doesn't pin to any actual
hardware, so you lose the "I chose exactly what runs this" property
that makes local persona-pinning meaningful; (2) `top_k`, `min_p`, and
`repeat_penalty` are llama.cpp-family extensions, not part of the
OpenAI Chat Completions spec — a remote/hosted model may ignore them
silently, reject them, or (reasonably) clamp genuinely extreme values
server-side to protect shared infrastructure from the same
runaway-generation failure mode this project deliberately triggers
locally. Remote personas are left unsupported for now rather than
built out.

### Status

Both the persona feature and `/v1/think` are experimental — built and
manually verified end-to-end, but not covered by automated tests, not
load-tested, and not yet part of a tagged release. Treat them as a
working prototype for exploring the divergent/convergent pattern, not
as a stable API.
