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
- MultiLLM's own concurrency handling — `Endpoint`'s single-flight
  semaphore was confirmed via live `/slots` polling to never allow more
  than one in-flight request per endpoint from MultiLLM's traffic.
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

## Java Style
General idiom (no lambdas/method references, threading defaults, no Spring,
etc.) is covered by the `java-style` skill. Repo-specific on top of that,
same as sibling `ttok`/`Voynich`/`infimg` repos: plain `long` epoch-millis
for timestamps, not `java.time`; public fields on plain data holders, no
getter/setter ceremony where the class is just a struct.
