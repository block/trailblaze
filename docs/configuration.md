---
title: Configuration
---

# Configuration

Everything you can tune in Trailblaze, in one place: which configuration surface to use for what, which one wins when two disagree, and whether a change takes effect immediately or needs a daemon restart.

Related pages:

- [LLM Configuration](llm_configuration.md) — providers, models, API keys, and the `llm:` YAML schema.
- [CLI reference](CLI.md) — the generated command reference, including the authoritative `trailblaze config` key table.
- [Trailmaps](trailmaps.md) and [Project Layout](project_layout.md) — per-target workspace configuration (`trails/config/`).

## The configuration surfaces

| Surface | Use it for | Where it lives |
|---|---|---|
| **Persistent config keys** — `trailblaze config <key> <value>` | Your personal defaults (LLM, drivers, screenshot format, experimental toggles) | `trailblaze-settings.json` (location below) |
| **Environment variables** — `TRAILBLAZE_*` | One-off overrides, CI, and kill-switches | Process environment |
| **User YAML config** | Personal LLM providers, models, and credentials | `~/.trailblaze/trailblaze.yaml` |
| **Workspace config** | Team defaults committed to the repo (LLM config, targets, trailmaps) | `trailblaze-config/trailblaze.yaml` (or the legacy `trails/config/trailblaze.yaml`) |
| **JVM system properties** — `-D…` | Daemon-process tuning (rarely needed) | Daemon launch command |
| **Android instrumentation arguments** | On-device SDK behavior in instrumented test runs | `am instrument` args |

### Where settings are persisted

Settings live in `trailblaze-settings.json`, but which copy applies depends on how you run:

- **Installed CLI (e.g. Homebrew):** the launcher pins one settings directory for both the CLI and the daemon (`-Dtrailblaze.appdata.dir`), so there is a single file.
- **Daemon:** `$TRAILBLAZE_HOME/trailblaze-settings.json`, default `~/.trailblaze/trailblaze-settings.json`. `~/.trailblaze` is also the daemon's state directory (logs, TLS keystore).
- **Standalone `trailblaze config` inside a git repository** (no launcher pin): falls back to `<repo root>/.trailblaze/trailblaze-settings.json`, so a source checkout doesn't mutate your machine-global settings. Note this repo-local file does **not** drive an already-running daemon — daemon-backed runs read the daemon's own file above.

Run `trailblaze config` with no arguments to print every key with its current value.

### Precedence

Configuration does not have one total ordering because different settings use different subsets of the surfaces. The important resolution chains are:

- **LLM providers and models:** environment/per-run override → workspace `trailblaze.yaml` → user `~/.trailblaze/trailblaze.yaml` → built-in definitions.
- **Target:** explicit per-run selection → persisted non-default `trailblaze config target` selection → workspace `defaults.target` → built-in target. A persisted selection of the neutral `default` target is treated as unset so it cannot mask the workspace default.
- **Maximum LLM calls:** CLI flag → `TRAILBLAZE_MAX_LLM_CALLS` → workspace `defaults.max-llm-calls` → persisted config key → agent default.
- **Self-heal:** CLI flag → `TRAILBLAZE_SELF_HEAL_ENABLED` → persisted config key → `false`.

Two more deliberate exceptions:

- **Experimental toggles** (`stream-screenshots`, `ios-baguette-video`, `disable-animations`): the env var can only turn the feature **on**. An explicit falsey env value does *not* override a `true` persisted config — unset the config key to turn the feature off.
- **Daemon port:** a `serverPort` persisted in `trailblaze-settings.json` outranks `TRAILBLAZE_PORT` unless the persisted value equals the default (`52525`, treated as "not set"). If an env override seems ignored, check for a persisted non-default port.

### When changes take effect

Each variable below is marked with when its value is applied:

| Marker | Meaning |
|---|---|
| **launch** | Read once when the consuming process starts. Restart a running daemon if it consumes this variable. |
| **command** | Read by each CLI process, so a one-shot `VAR=x trailblaze …` works. |
| **session** | Resolved at session/run start from the consuming process's environment and current persisted config. It never changes mid-session. |
| **subprocess** | Read when a tool subprocess imports its SDK or module. Set it before that subprocess starts. |
| **use** | Consulted on each use inside the consuming process rather than cached by Trailblaze. A running process still cannot inherit environment changes made later in its parent shell. |

Environment variables belong to a process environment: changing `export VAR=...` in your shell never rewrites an already-running daemon's environment. When a row is marked **session** or **use**, restart the daemon if you changed that variable outside the daemon process.

## Persistent config keys (`trailblaze config`)

The full generated table (valid values, defaults) lives in the [CLI reference](CLI.md). Grouped summary:

| Group | Keys |
|---|---|
| LLM | `llm` (shorthand `provider/model`), `llm-provider`, `llm-model` — see [LLM Configuration](llm_configuration.md) |
| Devices & drivers | `device`, `android-driver`, `ios-driver`, `web-headless`, `target` |
| Agent & runs | `agent`, `mode`, `max-llm-calls`, `self-heal`, `require-steps` |
| Screenshots | `screenshot-format`, `screenshot-max-dimensions`, `screenshot-quality`, `annotated-screenshots` |
| Experimental | `stream-screenshots`, `ios-baguette-video`, `disable-animations` |

Experimental keys are tri-state (`true`, `false`, or `unset` to inherit the default) and each has an env-var twin documented below.

## Workspace configuration (`trailblaze.yaml`)

Most projects need no workspace configuration — a single `.trail.yaml` file is enough. Add a workspace config dir when a team wants to commit shared targets, LLM settings, toolsets, or tools. Two layouts are supported:

```
my-project/                          my-project/
└── trailblaze-config/               └── trails/
    └── trailblaze.yaml   ← this         ├── config/
                                          │   └── trailblaze.yaml   ← or this
(trails can live anywhere,                └── login/
 e.g. next to features)                       └── trail.yaml
```

Trailblaze walks up from the current directory (or from the directory containing the trail you invoked) until it finds a `trailblaze.yaml` in either `trailblaze-config/` (the standalone layout) or the legacy `trails/config/`. The closest ancestor wins; when both layouts exist at the same ancestor, `trailblaze-config/` takes precedence and the CLI prints a consolidation warning. Every relative path inside the file resolves against the config directory it sits in. See [Project Layout](project_layout.md) for the discovery rules.

`trailblaze.yaml` is configuration, never a trail. Every section is optional, and an empty file is valid.

```yaml
# trailblaze-config/trailblaze.yaml (or trails/config/trailblaze.yaml) — everything below is optional
defaults:
  target: my-app
  max-llm-calls: 25

targets:
  - my-app

llm:
  providers:
    openai:
      models:
        - id: gpt-5.6-terra
  defaults:
    model: gpt-5.6-terra
```

### Top-level keys

| Key | Type | What it does |
|---|---|---|
| `defaults` | map | Workspace-wide defaults — see below. |
| `targets` | list of ids | Target-trailmap ids this workspace opts into. **Omit to auto-discover** every target trailmap under the workspace config dir's `trailmaps/`. Listing ids loads only that subset. Each id must name a target trailmap (one with a `target:` block); library trailmaps enter scope through a target's `dependencies:`. |
| `toolsets` | list | Extra toolsets, either inline or pulled in with `ref: path/to/toolset.yaml`. |
| `tools` | list | Extra tools, with the same inline-or-`ref:` shape. |
| `providers` | list | Reserved for standalone LLM provider files. Provider and model definitions are read from the `llm:` block today. |
| `llm` | map | LLM providers, models, and defaults — see [LLM Configuration](llm_configuration.md). |

### `defaults`

| Key | What it does |
|---|---|
| `target` | Target-trailmap id used when nothing more specific is selected. It must match a loaded target (case-sensitive); an unknown id is logged and skipped rather than failing the run. |
| `llm` | Reserved provider/model shorthand. Use `llm.defaults.model` today. |
| `max-llm-calls` | Team-wide positive-integer cap on LLM calls per objective. Per-run CLI and environment overrides still win. |

### `ref:` entries

`toolsets`, `tools`, and `providers` accept either an inline entry or a pointer to a separate file:

```yaml
toolsets:
  - ref: toolsets/my-toolset.yaml     # relative to the workspace config dir
  - name: inline-toolset
    tools: [tapOn, assertVisible]
```

Ref paths are always resolved relative to the directory holding `trailblaze.yaml`. A leading `/` is stripped and treated the same way, so `/foo.yaml` is not an escape to the filesystem root. A `ref:` entry may not carry sibling keys.

### Common workspace tasks

| I want to… | Go to |
|---|---|
| Use a model that is not built in | [Adding a Model](adding_a_model.md) |
| Point at a private LLM gateway | [LLM Configuration](llm_configuration.md#enterprise-gateway) |
| See the persisted per-machine settings currently in effect | `trailblaze config show` ([CLI](CLI.md#trailblaze-config)) |
| Understand workspace discovery | [Project Layout](project_layout.md) |
| Add custom tools to a project | [Your First Trailmap](your-first-trailmap.md) |

The user-level `~/.trailblaze/trailblaze.yaml` uses the same `llm:` schema for personal providers, models, and credentials. Workspace LLM configuration overrides it; see [LLM Configuration](llm_configuration.md) for the merge rules.

## Environment variables

Only set the variables in this section; variables the framework sets for its own subprocesses are listed [at the end](#set-by-the-framework-not-by-you).

### Daemon and ports

| Variable | Default | Applied | Purpose |
|---|---|---|---|
| `TRAILBLAZE_PORT` | `52525` | launch | Daemon HTTP port. Override to run isolated/parallel daemons. Moves the HTTPS port with it (`+1`). |
| `TRAILBLAZE_HTTPS_PORT` | HTTP port + 1 | launch | Daemon HTTPS port (the adb-reverse target for on-device logging). |
| `TRAILBLAZE_HOME` | `~/.trailblaze` | launch | Relocates the state directory (logs, TLS keystore, settings) — lets concurrent daemons isolate state. |
| `TRAILBLAZE_CONFIG_DIR` | cwd walk-up | command | Authoritative override for the workspace config dir (`trailblaze-config/` or the legacy `trails/config/`). Outranks the working-directory walk-up. |
| `TRAILBLAZE_DISABLE_DAEMON_AUTOSTART` | unset | use | Kill-switch: "daemon not running" becomes an error instead of an implicit background daemon spawn. |
| `TRAILBLAZE_MCP_REQUEST_TIMEOUT_MS` | `180000` | command | Per-request CLI→daemon MCP timeout. Default is long because agent commands legitimately run for minutes; lower it to fail fast when triaging a wedged device. |
| `TRAILBLAZE_RUN_POLL_TIMEOUT_MS` | `600000` | command | Inactivity watchdog for `trailblaze run`: max time a run may go *without new progress* before the CLI gives up. Not a wall-clock cap — a trail that keeps advancing runs as long as it needs. |
| `TRAILBLAZE_CLI_PRINT_STACK_TRACES` | unset | use | Print raw stack traces alongside the structured CLI error envelope. |

Development-checkout launcher knobs (the `./trailblaze` wrapper script; installed CLIs ignore these): `TRAILBLAZE_MAX_HEAP` (JVM max heap, default `4g` — raise for very large workspaces or heap-hungry report generation), `TRAILBLAZE_JAR` (path to the built uber JAR), `TRAILBLAZE_IPC=0` (disable the daemon IPC fast path), `TRAILBLAZE_REBUILD_GRADLE_TASK` (override the rebuild task), `TRAILBLAZE_FORCE_DAEMON_STOP=1` (stop a busy daemon anyway when the rebuilt JAR needs to take effect now — by default a daemon with in-flight runs is left running).

### Runs and recordings

| Variable | Default | Applied | Purpose |
|---|---|---|---|
| `TRAILBLAZE_DEVICE` | unset | command | Manual device override for a shell or non-interactive harness. Interactively you rarely set it — `trailblaze device connect` records a terminal-scoped pin instead. A `--device` flag still wins. |
| `TRAILBLAZE_TARGET` | unset | command | Per-shell target pin for forwarded subcommands (`clear` = unset). |
| `TRAILBLAZE_MAX_LLM_CALLS` | `25` | command | Per-objective LLM call cap for the built-in Trailblaze Runner and strategy-graph agents (flag → env → workspace `defaults.max-llm-calls` → config key). |
| `TRAILBLAZE_SELF_HEAL_ENABLED` | `false` | command | Enable self-heal on recorded replays (flag → env → config key). |
| `TRAILBLAZE_DEFAULT_MODEL` | LLM config value | session | Overrides `defaults.model` from the loaded [LLM configuration](llm_configuration.md). |
| `TRAILBLAZE_OLLAMA_NUM_CTX` | `65536` | session | Positive host-side Ollama `num_ctx` request override. Malformed or non-positive values fall back to 64K. See [Ollama (Local Models)](llm_configuration.md#ollama-local-models). |
| `TRAILBLAZE_DEFERRED_VARIABLES` | empty | command | Comma-separated `{{var}}` names excluded from environment expansion in trail templates (left for runtime memory instead). |
| `TRAILBLAZE_AUTO_TERMINATE_VERIFY_STEPS` | `false` | session | Auto-terminate verify steps once their assertion passes. |
| `TRAILBLAZE_TRAILS_DIR` | configured trails root | launch | Overrides the Trail Runner web UI's primary trails root. Unset, the UI uses the trails directory configured in the app (the app-data `trails/` dir unless you picked a workspace), falling back to `<cwd>/trails` only when the configured path doesn't exist. |

### Android devices

The accessibility-driver switches below are read inside the Android app/service process. Trailblaze does not forward arbitrary host shell environment variables into an already-launched Android process, so treat them as process-local/device-harness controls rather than `VAR=x trailblaze run …` host overrides.

| Variable | Default | Applied | Purpose |
|---|---|---|---|
| `ADB_SERVER_SOCKET` | unset | launch | Point at a remote adb server (`tcp:<host>:<port>`), same semantics as the upstream `adb` binary. Wins over `ANDROID_ADB_SERVER_PORT`. |
| `ANDROID_ADB_SERVER_PORT` | `5037` | launch | Port-only adb server override (host stays `localhost`). |
| `TRAILBLAZE_ADB_TIMEOUT_MS` | `10000` | launch | Bound for short-lived host-side adb shell calls. Streaming paths (`logcat -f`, `screenrecord`) are exempt. Bump for slow CI emulators. |
| `TRAILBLAZE_DISABLE_ACTION_CLICK_ROUTE` | unset | use | Kill-switch: force every selector-resolved tap back to coordinate gestures instead of accessibility `ACTION_CLICK`. |
| `TRAILBLAZE_DISABLE_TAP_OCCLUSION_WARN` | unset | use | Suppress warn-only diagnostics when another visible node covers the selector-resolved tap point; dispatch is unchanged. |
| `TRAILBLAZE_DISABLE_TARGET_TYPE_WARN` | unset | use | Suppress warnings when a selector resolves to an unrequested or ambiguous text input; resolution and dispatch are unchanged. |
| `TRAILBLAZE_IME_DISMISS_VIA_SHOW_MODE` | unset | use | Route `hideKeyboard` through the accessibility `SoftKeyboardController` show-mode instead of a BACK key event (a modal's back handler can't swallow it; no back-stack side effects). |
| `TRAILBLAZE_DISABLE_SETTLE_TREE_STABILITY` | unset | use | Kill-switch for the capture-time settle gate (tree stability + completeness) — capture immediately with no wait. |
| `TRAILBLAZE_SETTLE_VIA_WAIT_FOR_IDLE` | unset | use | Kill-switch for the post-action event-quiet settle: restore the legacy `UiDevice.waitForIdle()` (fixed 500 ms quiet window) after every gesture. |
| `TRAILBLAZE_DISABLE_BATCHED_TOOL_EXECUTION` | unset | use | Kill-switch: give every recorded tool its own execution context instead of sharing one per recording batch. |
| `TRAILBLAZE_ANDROID_WIRE_TRANSPORT` | `auto` | launch | Host↔device RPC / log-upload wire format: `auto`, `protobuf`, or `json` (rollback switch). |
| `TRAILBLAZE_CAPTURE_SECONDARY_TREE` | `false` | session | Capture a secondary view-hierarchy snapshot per selector tool (driver-migration comparison aid). |

### iOS simulators

| Variable | Default | Applied | Purpose |
|---|---|---|---|
| `TRAILBLAZE_BAGUETTE` | `PATH`, then Homebrew | launch | Explicit path to the [baguette](https://github.com/tddworks/baguette) binary (optional macOS dependency powering live H.264 simulator streaming). Absent → the device viewer falls back to screenshot polling. |
| `TRAILBLAZE_BAGUETTE_SERVE_PORT` | `8421` | launch | Port for the shared `baguette serve` process (loopback only). |
| `TRAILBLAZE_IOS_BAGUETTE_VIDEO` | unset | session | **Experimental** (config twin: `ios-baguette-video`): record session video by muxing the live baguette stream (wall-clock-accurate frame timestamps) instead of `simctl io recordVideo`. Declines per session when baguette is unavailable. |
| `TRAILBLAZE_IOS_CLEAR_STATE_MODE` | reinstall | use | AXe `clearState` route. Set `container` (case-insensitive) to wipe the app data container in place; any other value keeps uninstall-and-reinstall. Both routes fail if the clear does not complete. |
| `TRAILBLAZE_DISABLE_AXE_WEB_CONTENT` | unset | use | Kill-switch for AXe WKWebView content descent. The default enables descent when the installed `axe` binary advertises support. Restart after upgrading `axe` because that capability probe is cached. |

### Web and Electron apps

| Variable | Default | Applied | Purpose |
|---|---|---|---|
| `TRAILBLAZE_ELECTRON_COMMAND` | unset | session | Launch command for an Electron app under test (fallback when the target declares no Electron config). |
| `TRAILBLAZE_ELECTRON_ARGS` | empty | session | Space-separated launch args. |
| `TRAILBLAZE_ELECTRON_CDP_URL` | unset | session | Attach to an already-running Electron app via this CDP endpoint. |
| `TRAILBLAZE_ELECTRON_CDP_PORT` | `9222` | session | CDP port used when launching. |
| `TRAILBLAZE_ELECTRON_HEADLESS` | `false` | session | Launch Electron headless. |
| `TRAILBLAZE_PLAYWRIGHT_DRIVER_REPO` | Maven Central | launch | Extra Maven base URL tried first for the Playwright driver-bundle download (air-gapped/mirrored environments). |

### Screenshots and capture

The stream and proxy capture switches are experimental and off by default; each `STREAM_SCREENSHOT` env var has AB-mode and config-key companions. Sprite controls tune the timeline artifacts generated for host sessions.

| Variable | Default | Applied | Purpose |
|---|---|---|---|
| `TRAILBLAZE_ANDROID_STREAM_SCREENSHOT` / `TRAILBLAZE_IOS_STREAM_SCREENSHOT` / `TRAILBLAZE_WEB_STREAM_SCREENSHOT` | unset | session | Serve agent-loop screenshots from the device's live video stream instead of per-turn direct captures (config twin: `stream-screenshots`, one toggle for all three platforms). Unmatched captures fall back to a direct screenshot. |
| `…_STREAM_SCREENSHOT_AB` (same three prefixes) | unset | session | A/B validation: direct screenshot stays authoritative, stream matcher runs alongside and logs match/unmatch per capture. Run this before trusting the stream path. |
| `TRAILBLAZE_DISABLE_ANIMATIONS` | unset | session | Disable OS animations for the duration of each session, restoring previous values at session end (config twin: `disable-animations`). Android: zeroes the three global animation scales; iOS simulators: near-zero `UIAnimationDragCoefficient` (deliberately *not* Reduce Motion, which apps branch on). |
| `TRAILBLAZE_ANDROID_PROXY_CAPTURE` | unset | session | **Single switch** for Android network capture via a host-side mitmproxy (emulator-only, API 34+, needs mitmproxy installed): routes the emulator through `mitmdump`, installs the CA, writes `network.ndjson` into the session. |
| `TRAILBLAZE_MITMDUMP` | `mitmdump` on `PATH` | session | Explicit path to the `mitmdump` binary. |
| `TRAILBLAZE_SPRITE_FPS` | `2` | session | Host-session timeline sprite frame rate (`1..60`); invalid values fall back and log the chosen default. |
| `TRAILBLAZE_SPRITE_FRAME_HEIGHT` | `720` | session | Host-session timeline sprite frame height in pixels (`16..16383`). |
| `TRAILBLAZE_SPRITE_QUALITY` | `80` | session | Host-session timeline sprite WebP quality (`1..100`). |

### Scripted tools and the analyzer

The tool-definition analyzer extracts JSON Schemas from TypeScript scripted tools in a `bun` subprocess; inline `script:` MCP tools run as `bun` subprocesses too.

| Variable | Default | Applied | Purpose |
|---|---|---|---|
| `TRAILBLAZE_TOOL_ANALYZER_TIMEOUT_SECONDS` | `60` | launch | Per-trailmap analyzer subprocess timeout. |
| `TRAILBLAZE_TOOL_ANALYZER_NO_CACHE` | unset | launch | Bypass the workspace-local analyzer cache entirely (reads *and* writes). |
| `TRAILBLAZE_SDK_DIR` | walk-up resolution | launch | Explicit path to the scripting SDK directory (installed-CLI scenarios where the SDK isn't a cwd ancestor). |
| `TRAILBLAZE_SDK_PACKAGE` | `@trailblaze/scripting` | launch | npm package name that defines the recognized authoring surface. |
| `TRAILBLAZE_MCP_SUBPROCESS_HANDSHAKE_TIMEOUT_MS` | `60000` | launch | Watchdog on the MCP `initialize` handshake with each tool subprocess — a hung cold start fails that session's startup fast instead of wedging the daemon. |
| `TRAILBLAZE_CLIENT_FETCH_TIMEOUT_MS` | `32000` standalone | subprocess | Client-side fetch timeout read when the tool subprocess imports the SDK; normally forwarded automatically from the daemon's callback timeout (see [JVM system properties](#jvm-system-properties)). |

### `trailblaze check`

| Variable | Default | Applied | Purpose |
|---|---|---|---|
| `TRAILBLAZE_DISABLE_TRAIL_RECORDING_VALIDATION` | unset | command | Skip the recorded-tool type-validation phase (per-trailmap `tsc` pass) entirely — shaves latency in a tight inner loop. |
| `TRAILBLAZE_DISABLE_SELECTOR_DIALECT_GATE` | unset | command | Emergency opt-out from the selector/driver compatibility phase. It skips this `trailblaze check` phase, but not the unconditional Gradle corpus test. |
| `TRAILBLAZE_TYPECHECK_TIMEOUT_MS` | `300000` | command | Timeout for the TypeScript typecheck phase (clamped to ≥ 1 min). |
| `TRAILBLAZE_TEST_TIMEOUT_MS` | `300000` | command | Timeout for the trailmap unit-test runner. |

### Built-in agent tuning (`--agent KOOG_STRATEGY_GRAPH` only)

These affect only the opt-in strategy-graph agent; the default agent ignores them. All are applied per agent run.

| Variable | Default | Purpose |
|---|---|---|
| `TRAILBLAZE_KOOG_DISABLE_HISTORY_COMPRESSION` | unset | Prune-only context management (no summarization of older turns). |
| `TRAILBLAZE_KOOG_HISTORY_COMPRESSION_THRESHOLD` | `30` | Message count above which older turns are folded into a summary. |
| `TRAILBLAZE_KOOG_LOOP_DETECT_THRESHOLD` | `3` | Identical back-to-back tool dispatches before a "loop detected" nudge; `<= 0` disables. |
| `TRAILBLAZE_KOOG_DISABLE_SCREENSHOT` | unset | Send view-hierarchy text only (drop the annotated screenshot) — for A/B or token cost. |
| `TRAILBLAZE_KOOG_DISABLE_VERIFY_SCOPE` | unset | Keep the full tool surface on verify-only steps instead of scoping to assertion tools. |

### Reports and results

| Variable | Default | Applied | Purpose |
|---|---|---|---|
| `MAX_PLAYBACK_WAIT_MS` | `600000` | command | Playback ceiling for MP4, GIF, and WebP report exports. Non-numeric or non-positive values fall back to the default; an overrun still writes a best-effort truncated artifact. |
| `TRAILBLAZE_REPORT_IMAGE_COMPRESSION_PARALLELISM` | `min(cores, 4)` | command | Thread count for report image compression. |
| `TRAILBLAZE_RESULTS_REPO` | unset | command | `owner/name` of the results-index repository when `--repo` isn't passed to `trailblaze results`. |

CI systems additionally stamp report metadata via `TRAILBLAZE_TARGET_APP`, `TRAILBLAZE_DEVICES`, `TRAILBLAZE_BUILD_TYPE`, `TRAILBLAZE_TEST_RETRY_COUNT`, `TRAILBLAZE_AI_ENABLED`, and `TRAILBLAZE_PARALLEL_EXECUTION`. The report generator reads them all as labels, but CI pipelines commonly bridge `TRAILBLAZE_AI_ENABLED` (disables the LLM for the run) and `TRAILBLAZE_TEST_RETRY_COUNT` (drives retry loops) into real run behavior — don't treat those two as cosmetic.

### Stability kill-switches

Rollback levers for specific framework behaviors — reach for one when a change regresses your pipeline and you need a one-line revert while the underlying issue is fixed.

| Variable | Applied | Reverts |
|---|---|---|
| `TRAILBLAZE_MEMORY_BLANK_UNKNOWN_TOKENS` | use | Unknown `{{var}}` tokens resolve to empty strings again instead of failing loudly. |
| `TRAILBLAZE_DISABLE_BOUNDARY_MEMORY_INTERPOLATION` | use | Disable dispatch-boundary memory interpolation. |
| `TRAILBLAZE_DISABLE_NESTED_DISPATCH_RECORDING_FILTER` | use | Record nested tool dispatches again. |
| `TRAILBLAZE_DISABLE_SCRIPTED_ARG_TYPE_COERCION` | use | Disable scripted-tool argument type coercion. |
| `TRAILBLAZE_DEVICE_DISCOVERY_CACHE_TTL_MS` / `TRAILBLAZE_DISABLE_DEVICE_DISCOVERY_CACHE` | use | Tune (default `1500` ms) or disable the device-discovery cache. |

### Set by the framework, not by you

The framework exports these into its own subprocesses; don't set them yourself. Scripted-tool and MCP-server authors may **read** the device-context ones as a stable contract:

- **Read-only contract for tool subprocesses:** `TRAILBLAZE_DEVICE_PLATFORM`, `TRAILBLAZE_DEVICE_DRIVER`, `TRAILBLAZE_DEVICE_WIDTH_PX`, `TRAILBLAZE_DEVICE_HEIGHT_PX`.
- **Internal plumbing:** `TRAILBLAZE_SESSION_ID`, `TRAILBLAZE_SESSION_DIR`, `TRAILBLAZE_TOOLSET_FILE`, `TRAILBLAZE_BASE_URL`, `TRAILBLAZE_SHELL_PID`, `TRAILBLAZE_LAUNCHER`, `TRAILBLAZE_INTERACTIVE`, `TRAILBLAZE_TRAIL_CONTEXT`, `TRAILBLAZE_SETUP_TRAIL_ID`.

## JVM system properties

Tuning knobs for the `/scripting/callback` endpoint that backs the TypeScript scripting SDK's `client.tools.<name>(args)` round-trip. Defaults are production-ready; override only when a slow emulator or unusual composition graph needs more headroom.

* `-Dtrailblaze.callback.timeoutMs` (defaults to `120000`) — Per-callback dispatch timeout on the daemon side. Raise when a target tool is legitimately slow (e.g. waiting for a screen to settle on a slow emulator).
* `TRAILBLAZE_CLIENT_FETCH_TIMEOUT_MS` (env var, defaults to `32000` standalone) — Client-side fetch timeout in the subprocess. At runtime the daemon forwards its own timeout value + 2 s as this variable, so the daemon is normally the one that surfaces a structured timeout. **If you raise `trailblaze.callback.timeoutMs`, raise this in lockstep** — otherwise the client aborts the HTTP request before the daemon can return. Sampled once at SDK module load.
* `-Dtrailblaze.callback.maxDepth` (defaults to `16`) — Reentrance cap for recursive callback chains (a subprocess tool dispatching another subprocess tool counts as one level).
* `-Dtrailblaze.callback.maxBodyBytes` (defaults to `1048576` / 1 MB) — Maximum accepted callback request body size; larger declared bodies are rejected with HTTP 413.

The Homebrew-installed launcher also sets `-Dtrailblaze.appdata.dir` to pin the settings directory — relevant only if you're building custom launch wrappers.

## On-device Android instrumentation arguments

* `trailblaze.aiEnabled` (defaults to `true`) - This will have the Trailblaze SDK send all requests to the LLM.  When `false`, only recordings can be used.
* `trailblaze.reverseProxy` (defaults to `false`) - This will enable the reverse proxy for all Trailblaze traffic.
  * When `false`, logging traffic is sent to `https://10.0.2.2:<httpsPort>`, the default Android Emulator networking loopback address.
  * When `true`, the logs are sent through `https://localhost:<httpsPort>` and using `adb reverse tcp:<httpsPort> tcp:<httpsPort>` are forwarded to the host running the Trailblaze app.
    * This means all Trailblaze SDK Traffic is re-routed through `adb` and then the logs server reverse proxies the traffic to the final host.
    * This is important because it allows the Trailblaze Agent to run on-device, but not require a network connection.
    * It is also helpful/important because in the future it will allow you to not send your API Keys to the device itself, but add the `Authorization` information via the reverse proxy.
* `trailblaze.httpsPort` (defaults to `52526`, i.e. `trailblaze.port` + 1) - The HTTPS port for the Trailblaze server. Override this when running multiple Trailblaze instances.
* `trailblaze.logsEndpoint` - Defaults to the same values as the `reverseProxy` uses.  You can use this value if you want to use a remote logs server.  NOTE: Logging timeouts are set to 5 seconds as they are expected to be fast.
* `trailblaze.selfHeal` (unset by default) - Strict `true`/`false`: enable self-heal for on-device runs. Unset (or an invalid value) defers to the host-side resolution, same parser as `TRAILBLAZE_SELF_HEAL_ENABLED`.
* `trailblaze.agent` (defaults to the standard agent) - Agent implementation name for on-device runs; unknown values fall back to the default with a logged warning.
* `trailblaze.driverType` (unset by default) - **Force** override for the on-device driver (e.g. `ANDROID_ONDEVICE_ACCESSIBILITY`); when set, the per-trail `config.driver` YAML value is skipped entirely.
* `trailblaze.captureSecondaryTree` (defaults to `false`) - Strict `true`/`false`: also dump the legacy UiAutomator view hierarchy on every capture and use it as the captured `viewHierarchy` (selector-migration aid; roughly doubles per-step capture latency and session-log size). On-device counterpart of `TRAILBLAZE_CAPTURE_SECONDARY_TREE`.

## Diagnostic log prefixes

Most subsystems tag their diagnostic lines with a bracketed prefix, so you can `grep` one subsystem out of a verbose run. These go through the console logger, which is suppressed in CLI quiet mode — run with `-v` or inspect `$TRAILBLAZE_HOME/daemon.log` for a detached daemon. Desktop logs use `$TRAILBLAZE_HOME/desktop-logs/trailblaze.log` on the default port and `trailblaze-<port>.log` on a custom port.

| Prefix | Subsystem |
|---|---|
| `[AndroidHostAdbUtils]` | Host-side adb (timeouts, env overrides, client eviction) |
| `[tap-route]`, `[tapByActionClickOnBounds]` | Android selector-resolved tap routing |
| `[tap-occlusion]`, `[tap-target-type]` | Android tap-overlay and selector-target warnings |
| `[hideKeyboard]` | IME dismissal routing |
| `[settle]`, `[capture-coverage]` | Android settle gates and tree-completeness checks |
| `[ToolBatchScope]` | Batched recorded-tool execution |
| `[ScriptedToolDefinitionAnalyzer]` | Scripted-tool schema analyzer |
| `[McpSubprocessSession]` | Tool subprocess handshake watchdog |
| `[CliMcpClient]`, `[DaemonClient]` | CLI→daemon request timeouts and run polling |
| `[KOOG]`, `[KOOG_PRUNE]`, `[KOOG_COMPRESS]`, `[KOOG_SCREENSHOT]`, `[KOOG_VERIFY_SCOPE]` | Strategy-graph agent |
| `[stream-screenshot]` | Stream-sourced screenshots (including AB-mode lines) |
| `[baguette-video]`, `[IosBaguetteServer]`, `[devices-stream]` | iOS streaming and stream-sourced video |
| `[disable-animations]` | Session animation disabling |
| `[mitm-capture]` | Android proxy network capture |
| `[AxeDeviceManager]` | iOS AXe clear-state routing |
| `selector-dialect gate (FATAL)` | Selector/driver incompatibilities found by `trailblaze check` |
