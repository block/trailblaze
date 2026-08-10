---
title: Configuration
---

# Configuration

Most of Trailblaze needs no configuration — a single `.trail.yaml` file is a complete
project. When you do want project-level settings (which app to target, which LLM to use,
which toolsets your agent sees), they live in one file: **`trailblaze.yaml`**.

## `trailblaze.yaml` — the workspace config file

```
my-project/
└── trails/
    ├── config/
    │   └── trailblaze.yaml   ← this file
    └── login/
        └── trail.yaml
```

Trailblaze walks up from the current directory (or from the directory containing the trail
you invoked) until it finds `trails/config/trailblaze.yaml`. The owning `trails/` directory
becomes the **workspace root**, and every relative path inside the file resolves against
`trails/config/`. See [Project Layout](project_layout.md) for the discovery rules and
[External Config](generated/external-config.md) for the full `trails/config/` directory shape.

`trailblaze.yaml` is config, never a trail — Trailblaze will not try to run it.

**Every section is optional**, and an empty file is valid. Committing it is the point: it's
how a team pins one target, one model, and one toolset surface for everybody who clones the
repo, with no per-machine setup.

```yaml
# trails/config/trailblaze.yaml — everything below is optional
defaults:
  target: my-app
  max-llm-calls: 25

targets:
  - my-app

llm:
  providers:
    openai:
      models:
        - id: gpt-4.1
  defaults:
    model: gpt-4.1
```

### Top-level keys

| Key | Type | What it does |
|---|---|---|
| `defaults` | map | Workspace-wide defaults — see below |
| `targets` | list of ids | Target-trailmap ids this workspace opts into. **Omit to auto-discover** every target trailmap under `<workspace>/trailmaps/`. Listing ids is how a workspace with many trailmaps loads only a subset. Each id must be a *target* trailmap (one with a `target:` block); library trailmaps reach scope through a target's `dependencies:`. |
| `toolsets` | list | Extra toolsets, either written inline or pulled in with `ref: path/to/toolset.yaml` |
| `tools` | list | Extra tools, same inline-or-`ref:` shape |
| `providers` | list | Reserved for standalone LLM provider files. Provider and model definitions are read from the `llm:` block today — put them there. |
| `llm` | map | LLM providers, models, and defaults — see [LLM Configuration](llm_configuration.md) |

### `defaults`

| Key | What it does |
|---|---|
| `target` | Target-trailmap id used when nothing more specific is set. Must match a loaded target (case-sensitive); an unknown id is logged and skipped rather than failing the run. |
| `max-llm-calls` | Team-wide cap on LLM calls per objective, so every developer and CI runner inherits the same budget without passing `--max-llm-calls`. Positive integer. |

### `ref:` entries

`toolsets`, `tools`, and `providers` each accept either the entry written inline or a
pointer to a separate file:

```yaml
toolsets:
  - ref: toolsets/my-toolset.yaml     # relative to trails/config/
  - name: inline-toolset              # or write the whole thing here
    tools: [tapOn, assertVisible]
```

Ref paths are always resolved relative to the directory holding `trailblaze.yaml` — a
leading `/` is stripped and treated the same way, so `/foo.yaml` is not an escape to the
filesystem root. A `ref:` entry may not carry any sibling keys.

## Precedence

Later rows win.

| Priority | Source | Scope |
|---|---|---|
| 1 (lowest) | Built-in defaults shipped in the binary | Everyone |
| 2 | `~/.trailblaze/trailblaze.yaml` | Just you, every workspace |
| 3 | `<workspace>/trails/config/trailblaze.yaml` | Everyone in this project |
| 4 | Persisted per-machine settings (`trailblaze config …`) | Just you, this machine |
| 5 (highest) | Environment variables and per-run CLI flags | This invocation |

Two clarifications worth knowing:

- **Workspace beats user file, but per-run beats everything.** A committed workspace file is
  the team's baseline; `--target`, `-d`, `TRAILBLAZE_DEFAULT_MODEL` and friends still win for
  a single run, which is what makes CI overrides work.
- **`defaults.target` is deliberately ranked below a real user selection but above the
  neutral built-in target** — and a persisted selection of the neutral `default` target does
  *not* count as a real selection, so it can't mask the committed workspace default. Full
  ordering in [Project Layout → Workspace defaults](project_layout.md#workspace-defaults).

## Common tasks

| I want to… | Go to |
|---|---|
| Use a model that isn't built in | [Adding a Model](adding_a_model.md) |
| Point at a private LLM gateway | [LLM Configuration](llm_configuration.md#enterprise-gateway) |
| See what's currently in effect | `trailblaze config show` ([CLI](CLI.md#trailblaze-config)) |
| Set a default target for the team | `defaults.target`, above |
| Understand the `trails/config/` directory | [External Config](generated/external-config.md) |
| Add custom tools to a project | [Your First Trailmap](your-first-trailmap.md) |

Per-machine settings (`trailblaze config llm`, `trailblaze config target`, …) live in
`~/.trailblaze/` and are documented with the [`trailblaze config`](CLI.md#trailblaze-config)
command.

## On-Device Android Instrumentation Arguments
* `trailblaze.aiEnabled` (defaults to `true`) - This will have the Trailblaze SDK send all requests to the LLM.  When `false`, only recordings can be used.
* `trailblaze.reverseProxy` (defaults to `false`) - This will enable the reverse proxy for all Trailblaze traffic.
  * When `false`, logging traffic is sent to `https://10.0.2.2:<httpsPort>`, the default Android Emulator networking loopback address.
  * When `true`, the logs are sent through `https://localhost:<httpsPort>` and using `adb reverse tcp:<httpsPort> tcp:<httpsPort>` are forwarded to the host running the Trailblaze app.
    * This means all Trailblaze SDK Traffic is re-routed through `adb` and then the logs server reverse proxies the traffic to the final host.
    * This is important because it allows the Trailblaze Agent to run on-device, but not require a network connection.
    * It is also helpful/important because in the future it will allow you to not send your API Keys to the device itself, but add the `Authorization` information via the reverse proxy.
* `trailblaze.httpsPort` (defaults to `52526`, i.e. `trailblaze.port` + 1) - The HTTPS port for the Trailblaze server. Override this when running multiple Trailblaze instances.
* `trailblaze.logsEndpoint` - Defaults to the same values as the `reverseProxy` uses.  You can use this value if you want to use a remote logs server.  NOTE: Logging timeouts are set to 5 seconds as they are expected to be fast.

LLM selection for on-device runs has its own resolution order — see
[LLM Configuration → On-Device Android Agent](llm_configuration.md#on-device-android-agent).

## Scripting Callback Channel

Tuning knobs for the `/scripting/callback` endpoint that backs the TypeScript scripting SDK's `client.tools.<name>(args)` round-trip (the wire-protocol callback name inside the framework is `callTool`). Defaults are production-ready; override only when a slow emulator or unusual composition graph needs more headroom.

* `-Dtrailblaze.callback.timeoutMs` (JVM system property, defaults to `120000`) — Per-callback dispatch timeout on the daemon side. Bounds how long a single `client.tools.<name>(args)` dispatch can run before the daemon returns a structured timeout error. Raise when a target tool is legitimately slow (e.g. waiting for a screen to settle on a slow emulator).
* `TRAILBLAZE_CLIENT_FETCH_TIMEOUT_MS` (env var, defaults to `32000` standalone) — Client-side fetch timeout in the subprocess. At runtime the daemon forwards its own timeout value + 2 s as this variable, so the daemon is normally the one that surfaces a structured timeout. **If you raise `trailblaze.callback.timeoutMs`, raise this in lockstep** — otherwise the client aborts the HTTP request before the daemon can return and the daemon-side override is defeated. Sampled once at SDK module load; must be set before `import { trailblaze } from "@trailblaze/scripting"`.
* `-Dtrailblaze.callback.maxDepth` (JVM system property, defaults to `16`) — Reentrance cap for recursive callback chains. A subprocess tool that calls back into the daemon to dispatch another subprocess tool counts as one level; the cap prevents runaway recursion from wedging a session until the outer agent timeout fires. Raise only if you have a legitimate deep-composition use case (e.g. recursive tree-walker).
* `-Dtrailblaze.callback.maxBodyBytes` (JVM system property, defaults to `1048576` / 1 MB) — Maximum accepted `JsScriptingCallbackRequest` body size. Requests whose declared `Content-Length` exceeds this are rejected with HTTP 413 before buffering. Real callback payloads are tiny (invocation id, session id, a single action with a JSON-string args field) so the cap is pure belt-and-suspenders against a buggy subprocess emitting a runaway args string. Raise only if a legitimate tool needs to pass a very large args payload through the callback channel.
