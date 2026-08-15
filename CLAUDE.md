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

## Java Style
General idiom (no lambdas/method references, threading defaults, no Spring,
etc.) is covered by the `java-style` skill. Repo-specific on top of that,
same as sibling `ttok`/`Voynich`/`infimg` repos: plain `long` epoch-millis
for timestamps, not `java.time`; public fields on plain data holders, no
getter/setter ceremony where the class is just a struct.
