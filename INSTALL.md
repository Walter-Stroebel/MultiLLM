# Installing MultiLLM

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

Produces `target/MultiLLM-<version>-jar-with-dependencies.jar` — a
single self-contained jar, nothing else to install.

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

### Optional: the call inspector

To turn on the local call-inspector GUI (see `MANUAL.md` → "The call
inspector"), the config file takes a second, wrapper form: an object
with the endpoint array under `"endpoints"` and an `"inspector"` block
alongside it. `config/endpoints.inspector-example.json` is a tracked
template.

```json
{
  "endpoints": [
    { "name": "predator", "url": "http://predator:8081", "models": ["gemma-vision"],
      "kind": "local", "vision": true }
  ],
  "inspector": {
    "enabled": true,
    "maxCalls": 50,
    "revealSecrets": false
  }
}
```

A bare array (no wrapper) is still valid and runs headless — the
`"inspector"` block only *adds* the feature, it changes nothing else.

- **`enabled`** — launch the inspector window and start capturing.
  Default `false`: no window, nothing retained, no Swing on the
  classpath at startup.
- **`maxCalls`** — ring size; only the most recent this-many calls are
  kept in memory.
- **`revealSecrets`** — show real credential values in the inspector
  instead of `<redacted>`. Default `false`.

The inspector needs a display (`$DISPLAY` on Linux). It is a
development / testing aid — see the warning in `MANUAL.md` about not
running it in production.

## Run

```bash
java -jar target/MultiLLM-<version>-jar-with-dependencies.jar [port]
```

Defaults to port 8085. Starts the gateway and prints its self URL
(used for the `/v1/files` upload response) and how many endpoints it
loaded.

```bash
java -jar target/MultiLLM-1.3.0-jar-with-dependencies.jar
```
```
Loaded 3 endpoint(s) from config/endpoints.json
MultiLLM gateway listening on port 8085 (self URL http://legion:8085)
```

Then point any OpenAI-compatible client at it — see `MANUAL.md` for
usage and request/response details.

## Run as a system service

A sample unit file is at `systemd/multillm.service`, matching the
style already used for this machine's `llama-server` units: no
dedicated service user, no sandboxing directives. Unlike those units,
though, it does **not** run out of a repo checkout under a user's home
directory — the unit runs as root (no `User=` set), so `ExecStart`
must point at a jar and config that a non-root user cannot overwrite,
or any local account can hand itself root on the next service
restart. Install the built jar and config to a root-owned system
path instead:

```bash
mvn package
sudo mkdir -p /usr/local/lib/multillm/config
sudo cp target/MultiLLM-1.3.0-jar-with-dependencies.jar /usr/local/lib/multillm/MultiLLM.jar
sudo cp config/endpoints.json /usr/local/lib/multillm/config/endpoints.json   # your real config, if any
sudo chown -R root:root /usr/local/lib/multillm
sudo chmod 600 /usr/local/lib/multillm/config/endpoints.json                 # may contain API keys

sudo cp systemd/multillm.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now multillm
```

Re-run the `cp`/`chown` steps (or a small deploy script) after every
rebuild — the unit intentionally never points at anything under a
user's home directory or git working tree.

### The systemd service is the production instance — and only that

The unit runs the gateway headless: a bare-array `endpoints.json` (no
`"inspector"` block), doing nothing but routing traffic. That is the
**P** deployment.

The **call inspector is never part of the systemd service.** When you
want it — for development, or to inspect what a test workload is
actually putting on the wire — you run a *separate*, hand-started jar
with an `inspector`-enabled config, typically on a different port, and
you stop it when you're done. It is a D/T/A tool that happens to share
a codebase with the production gateway, not a mode of the production
gateway. Do not add the `"inspector"` block to the config the unit
loads.
