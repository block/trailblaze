# Trailblaze CLI

Trailblaze - AI-powered device automation

## Usage

```
trailblaze [OPTIONS] [COMMAND]
```

## Device Claims & Sessions

Trailblaze tracks device ownership per MCP session so two CLI workflows on
the same machine cannot accidentally drive the same device at the same time.
Understanding the model up front saves debugging time when a command
unexpectedly returns `Error: Device <id> is already in use by another MCP session`.

### Two execution models

- **One-shot commands** — `ask`, `verify`, `snapshot`, `tool`. Each invocation
  opens a fresh MCP session, binds the requested device, runs once, and tears
  the session down. Different-device parallel one-shots are fully isolated.
- **Reusable workflows** — `blaze`, `blaze --save`, `session start/info/save/recording/stop/end/artifacts/delete`,
  `device connect`. These persist an MCP session under `/tmp/trailblaze-cli-session-{port}[-scope]`
  so follow-up commands can reattach. `blaze --save` is the canonical reason —
  each `blaze` invocation records steps into a per-device scoped session that
  `blaze --save` later exports as a trail YAML.

### Device-claim conflicts (yield-unless-busy)

Device-binding commands try to claim the requested device on the daemon.
If another MCP session already holds the claim, the daemon decides:

- **Prior holder is idle** → the new command silently displaces it and proceeds.
  Idle means "no MCP tool call currently executing on that session."
- **Prior holder is mid-tool-call** → the new command fails with a `Device …
  is busy.` block naming the holder, the running tool, and how long it has been
  running. Wait for it to finish, or stop the holder before retrying.

Same-session re-claims are always allowed, so a `blaze` workflow that keeps
calling into its own scope never trips on this — only cross-session contention
with a busy holder does.

### When a `blaze` scope leaks across commands

`blaze --device android "…"` opens a `blaze-android` scoped MCP session that
stays alive on the daemon after the CLI exits, holding the device claim until
`blaze --save` (or another `blaze --device android`) reattaches. The session
is idle while it waits, so a subsequent one-shot like `ask --device android`
just yields and proceeds — the leaked scope no longer blocks unrelated commands.
If you want to clear it explicitly, `trailblaze app --stop` recycles the daemon
and drops all in-memory sessions.

Note: `session stop` ends the **global** CLI session created by `session start`.
It does not reap device-scoped `blaze` sessions; use `app --stop` for those.

## Global Options

| Option | Description | Default |
|--------|-------------|---------|
| `-h`, `--help` | Show this help message and exit. | - |
| `-V`, `--version` | Print version information and exit. | - |

## Commands

| Command | Description |
|---------|-------------|
| `blaze` | Drive a device with AI — describe what to do in plain English |
| `ask` | Ask a question about what's on screen (uses AI vision, no actions taken) |
| `verify` | Check a condition on screen and pass/fail (exit code 0/1, ideal for CI) |
| `snapshot` | Capture the current screen's UI tree (fast, no AI, no actions) |
| `tool` | Run a Trailblaze tool by name (e.g., tap, inputText) |
| `toolbox` | Browse available tools by target app and platform |
| `trail` | Run a trail file (.trail.yaml) — execute a scripted test on a device |
| `session` | Every blaze records a session — save it as a replayable trail |
| `report` | Generate an HTML or JSON report from session recordings |
| `waypoint` | Match named app locations (waypoints) against captured screen state. |
| `config` | View and set configuration (target app, device defaults, AI provider) |
| `device` | List and connect devices (Android, iOS, Web) |
| `app` | Start or stop the Trailblaze daemon (background service that drives devices) |
| `mcp` | Start a Model Context Protocol (MCP) server for AI agent integration |
| `compile` | Compile pack manifests into resolved target YAMLs |

---

### `trailblaze blaze`

Drive a device with AI — describe what to do in plain English

**Synopsis:**

```
trailblaze blaze [OPTIONS] [<<objectiveWords>>]
```

**Arguments:**

| Argument | Description | Required |
|----------|-------------|----------|
| `<<objectiveWords>>` | Objective or assertion (e.g., 'Tap login', 'The email field is visible') | No |

**Options:**

| Option | Description | Default |
|--------|-------------|---------|
| `--verify` | Verify an assertion instead of taking an action (exit code 1 if assertion fails) | - |
| `-d`, `--device` | Device: platform (android, ios, web) or platform/id (e.g., android/emulator-5554). Required for interactive blaze/verify execution. | - |
| `--context` | Context from previous steps for situational awareness | - |
| `-v`, `--verbose` | Enable verbose output (show daemon logs, MCP calls) | - |
| `--target` | Target app ID, saved as the default for future commands. List available targets with `trailblaze toolbox` (no args). | - |
| `--no-screenshots`, `--text-only` | Skip screenshots — the LLM only sees the textual view hierarchy, no vision tokens, and disk logging of screenshots is skipped too. Faster and cheaper for short objectives where the visual layout doesn't matter; some tasks need vision and will degrade without it. | - |
| `--save` | Save current session as a trail file. Shows steps if --setup not specified. | - |
| `--setup` | Step range for setup/trailhead (e.g., '1-3'). Use with --save. | - |
| `--no-setup` | Save without setup steps. Use with --save. | - |
| `--headless` | For --device web/...: launch the Playwright browser headless. When omitted, falls back to the persisted `web-headless` config (see `trailblaze config web-headless`). Pass --headless=false to force a visible browser, --headless=true to force headless. Ignored for non-web devices. | - |
| `-h`, `--help` | Show this help message and exit. | - |
| `-V`, `--version` | Print version information and exit. | - |

---

### `trailblaze ask`

Ask a question about what's on screen (uses AI vision, no actions taken)

**Synopsis:**

```
trailblaze ask [OPTIONS] <<questionWords>>
```

**Arguments:**

| Argument | Description | Required |
|----------|-------------|----------|
| `<<questionWords>>` | Question about the screen (e.g., 'What's the current balance?') | Yes |

**Options:**

| Option | Description | Default |
|--------|-------------|---------|
| `-d`, `--device` | Device: platform (android, ios, web) or platform/id (e.g., android/emulator-5554). Required. | - |
| `-v`, `--verbose` | Enable verbose output (show daemon logs, MCP calls) | - |
| `--headless` | For --device web/...: launch the Playwright browser headless. When omitted, falls back to the persisted `web-headless` config (see `trailblaze config web-headless`). Pass --headless=false to force a visible browser, --headless=true to force headless. Ignored for non-web devices. | - |
| `-h`, `--help` | Show this help message and exit. | - |
| `-V`, `--version` | Print version information and exit. | - |

---

### `trailblaze verify`

Check a condition on screen and pass/fail (exit code 0/1, ideal for CI)

**Synopsis:**

```
trailblaze verify [OPTIONS] <<assertionWords>>
```

**Arguments:**

| Argument | Description | Required |
|----------|-------------|----------|
| `<<assertionWords>>` | Assertion to verify (e.g., 'The Sign In button is visible') | Yes |

**Options:**

| Option | Description | Default |
|--------|-------------|---------|
| `-d`, `--device` | Device: platform (android, ios, web) or platform/id. Required. | - |
| `-v`, `--verbose` | Enable verbose output | - |
| `--no-screenshots`, `--text-only` | Skip screenshots — the LLM only sees the textual view hierarchy, no vision tokens, and disk logging of screenshots is skipped too. Faster and cheaper for short objectives where the visual layout doesn't matter; some tasks need vision and will degrade without it. | - |
| `--headless` | For --device web/...: launch the Playwright browser headless. When omitted, falls back to the persisted `web-headless` config (see `trailblaze config web-headless`). Pass --headless=false to force a visible browser, --headless=true to force headless. Ignored for non-web devices. | - |
| `-h`, `--help` | Show this help message and exit. | - |
| `-V`, `--version` | Print version information and exit. | - |

---

### `trailblaze snapshot`

Capture the current screen's UI tree (fast, no AI, no actions)

**Synopsis:**

```
trailblaze snapshot [OPTIONS]
```

**Options:**

| Option | Description | Default |
|--------|-------------|---------|
| `-d`, `--device` | Device: platform (android, ios, web) or platform/id. Required. | - |
| `-v`, `--verbose` | Enable verbose output | - |
| `--bounds` | Include bounding box {x,y,w,h} for each element | - |
| `--offscreen` | Include offscreen elements marked (offscreen) | - |
| `--screenshot` | Save a screenshot to disk and print the file path | - |
| `--all` | Show all visible elements, including those normally filtered as non-interactive | - |
| `--headless` | For --device web/...: launch the Playwright browser headless. When omitted, falls back to the persisted `web-headless` config (see `trailblaze config web-headless`). Pass --headless=false to force a visible browser, --headless=true to force headless. Ignored for non-web devices. | - |
| `-h`, `--help` | Show this help message and exit. | - |
| `-V`, `--version` | Print version information and exit. | - |

---

### `trailblaze tool`

Run a Trailblaze tool by name (e.g., tap, inputText)

**Synopsis:**

```
trailblaze tool [OPTIONS] [<<toolName>>] [<<argPairs>>]
```

**Arguments:**

| Argument | Description | Required |
|----------|-------------|----------|
| `<<toolName>>` | Tool name (e.g., web_click, tap) | No |
| `<<argPairs>>` | Tool arguments as key=value pairs (e.g., ref="Sign In") | No |

**Options:**

| Option | Description | Default |
|--------|-------------|---------|
| `--objective`, `-o` | Natural language intent — describe what, not how. If the UI changes, Trailblaze uses this to retry the step with AI. 'Navigate to Settings' survives a redesign; 'tap button at 200,400' does not. | - |
| `--yaml` | Raw YAML tool sequence (multiple tools in one call) | - |
| `-d`, `--device` | Device: platform (android, ios, web) or platform/id. Required. | - |
| `-v`, `--verbose` | Enable verbose output | - |
| `--no-screenshots`, `--text-only` | Skip screenshots — the LLM only sees the textual view hierarchy, no vision tokens, and disk logging of screenshots is skipped too. Faster and cheaper for short objectives where the visual layout doesn't matter; some tasks need vision and will degrade without it. | - |
| `--target` | Target app ID, saved as the default for future commands. List available targets with `trailblaze toolbox` (no args). | - |
| `--headless` | For --device web/...: launch the Playwright browser headless. When omitted, falls back to the persisted `web-headless` config (see `trailblaze config web-headless`). Pass --headless=false to force a visible browser, --headless=true to force headless. Ignored for non-web devices. | - |
| `-h`, `--help` | Show this help message and exit. | - |
| `-V`, `--version` | Print version information and exit. | - |

---

### `trailblaze toolbox`

Browse available tools by target app and platform

**Synopsis:**

```
trailblaze toolbox [OPTIONS]
```

**Options:**

| Option | Description | Default |
|--------|-------------|---------|
| `--name`, `-n` | Show details for a single tool by name | - |
| `--target`, `-t` | Show tools for a specific target app | - |
| `--search`, `-s` | Search tools by keyword (matches names and descriptions) | - |
| `-d`, `--device` | Filter by platform: android, ios, web | - |
| `--detail` | Show full parameter descriptions for all tools | - |
| `-v`, `--verbose` | Enable verbose output | - |
| `-h`, `--help` | Show this help message and exit. | - |
| `-V`, `--version` | Print version information and exit. | - |

---

### `trailblaze trail`

Run a trail file (.trail.yaml) — execute a scripted test on a device

**Synopsis:**

```
trailblaze trail [OPTIONS] <<trailFile>>
```

**Arguments:**

| Argument | Description | Required |
|----------|-------------|----------|
| `<<trailFile>>` | One or more trail files (.trail.yaml or blaze.yaml). Use your shell's glob to run a batch (e.g., flows/**/*.trail.yaml). | Yes |

**Options:**

| Option | Description | Default |
|--------|-------------|---------|
| `-d`, `--device` | Device: platform (android, ios, web), platform/instance-id, or instance ID | - |
| `-a`, `--agent` | Agent: TRAILBLAZE_RUNNER, MULTI_AGENT_V3. Default: TRAILBLAZE_RUNNER | - |
| `--use-recorded-steps` | Use recorded tool sequences instead of LLM inference | - |
| `--self-heal` | When a recorded step fails, let AI take over and continue. Overrides the persisted 'trailblaze config self-heal' setting for this run. Omit to inherit the saved setting (opt-in, off by default). | - |
| `-v`, `--verbose` | Enable verbose output | - |
| `--driver` | Driver type to use (e.g., PLAYWRIGHT_NATIVE, ANDROID_ONDEVICE_INSTRUMENTATION). Overrides driver from trail config. | - |
| `--headless` | Launch the Playwright browser headless (default true). Pass --no-headless or --headless=false to surface a visible window. Equivalent to --show-browser when negated. | - |
| `--llm` | LLM provider/model shorthand (e.g., openai/gpt-4-1). Mutually exclusive with --llm-provider and --llm-model. | - |
| `--llm-provider` | LLM provider override (e.g., openai, anthropic, google) | - |
| `--llm-model` | LLM model ID override (e.g., gemini-3-flash, gpt-4-1) | - |
| `--no-report` | Skip HTML report generation after execution | - |
| `--no-record` | Skip saving the recording back to the trail source directory | - |
| `--no-logging` | Disable session logging — no files written to logs/, session does not appear in Sessions tab | - |
| `--markdown` | Generate a markdown report after execution | - |
| `--no-daemon` | Run in-process without delegating to or starting a persistent daemon. The server shuts down when the run completes. | - |
| `--compose-port` | RPC port for Compose driver connections (default: 52600) | - |
| `--capture-video` | Record device screen video for the session (on by default, use --no-capture-video to disable) | - |
| `--capture-logcat` | Capture logcat output filtered to the app under test (local dev mode) | - |
| `--capture-network` | Auto-capture network requests/responses to <session-dir>/network.ndjson on supported devices (web today; mobile devices added as engines land). Mirrors the desktop-app "Capture Network Traffic" toggle. | - |
| `--capture-all` | Enable all capture streams: video, logcat, network (local dev mode) | - |
| `-h`, `--help` | Show this help message and exit. | - |
| `-V`, `--version` | Print version information and exit. | - |

---

### `trailblaze session`

Every blaze records a session — save it as a replayable trail

**Synopsis:**

```
trailblaze session [OPTIONS]
trailblaze session start
trailblaze session stop
trailblaze session save
trailblaze session recording
trailblaze session info
trailblaze session list
trailblaze session artifacts
trailblaze session delete
trailblaze session end
trailblaze session report
```

**Options:**

| Option | Description | Default |
|--------|-------------|---------|
| `-h`, `--help` | Show this help message and exit. | - |
| `-V`, `--version` | Print version information and exit. | - |

---

### `trailblaze session start`

Start a new session with automatic video and log capture

**Synopsis:**

```
trailblaze session start [OPTIONS]
```

**Options:**

| Option | Description | Default |
|--------|-------------|---------|
| `--target` | Target app ID. Saved to config for future commands. | - |
| `--mode` | Working mode: trail or blaze. Saved to config for future commands. | - |
| `-d`, `--device` | Device: platform (android, ios, web) or platform/id (e.g., ios/DEVICE-UUID). Binds the shared CLI session to that device for session workflows. | - |
| `--title` | Title for the session (used as trail name when saving) | - |
| `--no-video` | Disable video capture | - |
| `--no-logs` | Disable device log capture | - |
| `-v`, `--verbose` | Enable verbose output | - |
| `--headless` | For --device web/...: launch the Playwright browser headless. When omitted, falls back to the persisted `web-headless` config (see `trailblaze config web-headless`). Pass --headless=false to force a visible browser, --headless=true to force headless. Ignored for non-web devices. | - |
| `-h`, `--help` | Show this help message and exit. | - |
| `-V`, `--version` | Print version information and exit. | - |

---

### `trailblaze session stop`

Stop the current session and finalize captures

**Synopsis:**

```
trailblaze session stop [OPTIONS]
```

**Options:**

| Option | Description | Default |
|--------|-------------|---------|
| `--save` | Save session as a trail before stopping | - |
| `--title`, `-t` | Trail title when saving (overrides session title) | - |
| `-h`, `--help` | Show this help message and exit. | - |
| `-V`, `--version` | Print version information and exit. | - |

---

### `trailblaze session save`

Write the recorded steps to a *.trail.yaml file you can replay later (does not end the session)

**Synopsis:**

```
trailblaze session save [OPTIONS]
```

**Options:**

| Option | Description | Default |
|--------|-------------|---------|
| `--title`, `-t` | Title for the saved trail (uses session title if not specified) | - |
| `--id` | Session ID to save (defaults to current session, supports prefix matching) | - |
| `-h`, `--help` | Show this help message and exit. | - |
| `-V`, `--version` | Print version information and exit. | - |

---

### `trailblaze session recording`

Output the recording YAML for a session

**Synopsis:**

```
trailblaze session recording [OPTIONS]
```

**Options:**

| Option | Description | Default |
|--------|-------------|---------|
| `--id` | Session ID (defaults to current session, supports prefix matching) | - |
| `-h`, `--help` | Show this help message and exit. | - |
| `-V`, `--version` | Print version information and exit. | - |

---

### `trailblaze session info`

Show information about a session

**Synopsis:**

```
trailblaze session info [OPTIONS]
```

**Options:**

| Option | Description | Default |
|--------|-------------|---------|
| `--id` | Session ID (defaults to current session) | - |
| `-h`, `--help` | Show this help message and exit. | - |
| `-V`, `--version` | Print version information and exit. | - |

---

### `trailblaze session list`

List recent sessions

**Synopsis:**

```
trailblaze session list [OPTIONS]
```

**Options:**

| Option | Description | Default |
|--------|-------------|---------|
| `--limit`, `-n` | Maximum number of sessions to show (default: 10) | - |
| `--all`, `-a` | Show all sessions in a flat chronological list | - |
| `-h`, `--help` | Show this help message and exit. | - |
| `-V`, `--version` | Print version information and exit. | - |

---

### `trailblaze session artifacts`

List artifacts in a session

**Synopsis:**

```
trailblaze session artifacts [OPTIONS]
```

**Options:**

| Option | Description | Default |
|--------|-------------|---------|
| `--id` | Session ID (defaults to current session) | - |
| `-h`, `--help` | Show this help message and exit. | - |
| `-V`, `--version` | Print version information and exit. | - |

---

### `trailblaze session delete`

Delete a session's logs and artifacts

**Synopsis:**

```
trailblaze session delete [OPTIONS]
```

**Options:**

| Option | Description | Default |
|--------|-------------|---------|
| `--id` | Session ID to delete (supports prefix matching) | - |
| `-h`, `--help` | Show this help message and exit. | - |
| `-V`, `--version` | Print version information and exit. | - |

---

### `trailblaze session end`

End the CLI session and release the device (deprecated: use 'stop' instead)

**Synopsis:**

```
trailblaze session end [OPTIONS]
```

**Options:**

| Option | Description | Default |
|--------|-------------|---------|
| `--name`, `-n` | Save the recording as a trail before ending | - |
| `-h`, `--help` | Show this help message and exit. | - |
| `-V`, `--version` | Print version information and exit. | - |

---

### `trailblaze session report`

Generate an HTML or JSON report for this session

**Synopsis:**

```
trailblaze session report [OPTIONS]
```

**Options:**

| Option | Description | Default |
|--------|-------------|---------|
| `--id` | Session ID to report on (defaults to current session, supports prefix matching) | - |
| `--open` | Open the report in the default browser after generation (HTML only) | - |
| `--format` | Output format: html (default) or json — JSON emits a CiSummaryReport artifact | - |
| `-h`, `--help` | Show this help message and exit. | - |
| `-V`, `--version` | Print version information and exit. | - |

---

### `trailblaze report`

Generate an HTML or JSON report from session recordings

**Synopsis:**

```
trailblaze report [OPTIONS]
```

**Options:**

| Option | Description | Default |
|--------|-------------|---------|
| `--id` | Session ID to report on (defaults to all sessions) | - |
| `--open` | Open the report in the default browser after generation (HTML only) | - |
| `--format` | Output format: html (default) or json — JSON emits a CiSummaryReport artifact | - |
| `-h`, `--help` | Show this help message and exit. | - |
| `-V`, `--version` | Print version information and exit. | - |

---

### `trailblaze waypoint`

Match named app locations (waypoints) against captured screen state.

**Synopsis:**

```
trailblaze waypoint [OPTIONS]
trailblaze waypoint list
trailblaze waypoint locate
trailblaze waypoint validate
trailblaze waypoint capture-example
trailblaze waypoint segment
```

**Options:**

| Option | Description | Default |
|--------|-------------|---------|
| `-h`, `--help` | Show this help message and exit. | - |
| `-V`, `--version` | Print version information and exit. | - |

---

### `trailblaze waypoint list`

List all waypoint definitions from active packs (workspace + framework classpath) and any additional *.waypoint.yaml files discovered under --root.

**Synopsis:**

```
trailblaze waypoint list [OPTIONS]
```

**Options:**

| Option | Description | Default |
|--------|-------------|---------|
| `--root` | Additional directory to scan for *.waypoint.yaml files (default: ./trails, resolved against the current working directory). Pack waypoints are always included regardless of --root. | - |
| `-h`, `--help` | Show this help message and exit. | - |
| `-V`, `--version` | Print version information and exit. | - |

---

### `trailblaze waypoint locate`

Given a captured screen state, report which waypoint(s) match.

**Synopsis:**

```
trailblaze waypoint locate [OPTIONS]
```

**Options:**

| Option | Description | Default |
|--------|-------------|---------|
| `--session` | Session log directory (containing *_TrailblazeLlmRequestLog.json files) | - |
| `--step` | 1-based index of the step within the session (default: last step) | - |
| `--file` | Direct path to a *_TrailblazeLlmRequestLog.json file (alternative to --session/--step) | - |
| `--root` | Additional directory to scan for *.waypoint.yaml files (default: ./trails, resolved against the current working directory). Pack waypoints are always included regardless of --root. | - |
| `--live` | Pull screen state from the connected device (not yet implemented) | - |
| `-h`, `--help` | Show this help message and exit. | - |
| `-V`, `--version` | Print version information and exit. | - |

---

### `trailblaze waypoint validate`

Validate that a specific waypoint definition matches a captured screen state.

**Synopsis:**

```
trailblaze waypoint validate [OPTIONS] [<<positionalLogFile>>]
```

**Arguments:**

| Argument | Description | Required |
|----------|-------------|----------|
| `<<positionalLogFile>>` | Path to *_TrailblazeLlmRequestLog.json (required unless --session/--step given) | No |

**Options:**

| Option | Description | Default |
|--------|-------------|---------|
| `--def` | Waypoint id to validate (required) | - |
| `--session` | Session log directory (containing *_TrailblazeLlmRequestLog.json files) | - |
| `--step` | 1-based index of the step within the session (default: last step) | - |
| `--root` | Additional directory to scan for *.waypoint.yaml files (default: ./trails, resolved against the current working directory). Pack waypoints are always included regardless of --root. | - |
| `-h`, `--help` | Show this help message and exit. | - |
| `-V`, `--version` | Print version information and exit. | - |

---

### `trailblaze waypoint capture-example`

Capture a sibling <id>.example.json + screenshot next to the waypoint YAML. Picks the raw (un-annotated) screenshot twin from the source session.

**Synopsis:**

```
trailblaze waypoint capture-example [OPTIONS] [<<positionalLogFile>>]
```

**Arguments:**

| Argument | Description | Required |
|----------|-------------|----------|
| `<<positionalLogFile>>` | Path to a *_TrailblazeLlmRequestLog.json (alternative to --session/--step) | No |

**Options:**

| Option | Description | Default |
|--------|-------------|---------|
| `--def` | Waypoint id to capture an example for (required) | - |
| `--session` | Session log directory (containing *_TrailblazeLlmRequestLog.json files) | - |
| `--step` | 1-based step within the session (default: last step) | - |
| `--root` | Root directory to scan for *.waypoint.yaml files (default: ./trails) | - |
| `--force` | Overwrite an existing example pair without prompting. | - |
| `-h`, `--help` | Show this help message and exit. | - |
| `-V`, `--version` | Print version information and exit. | - |

---

### `trailblaze waypoint segment`

Inspect transitions between waypoints observed in a session log.

**Synopsis:**

```
trailblaze waypoint segment [OPTIONS]
trailblaze waypoint segment list
```

**Options:**

| Option | Description | Default |
|--------|-------------|---------|
| `-h`, `--help` | Show this help message and exit. | - |
| `-V`, `--version` | Print version information and exit. | - |

---

### `trailblaze waypoint segment list`

List trail segments observed in a session log directory. A segment is a transition from one matched waypoint to another, with the tool calls that drove it.

**Synopsis:**

```
trailblaze waypoint segment list [OPTIONS]
```

**Options:**

| Option | Description | Default |
|--------|-------------|---------|
| `--session` | Session log directory (containing *.json log files) | - |
| `--root` | Additional directory to scan for *.waypoint.yaml files (default: ./trails, resolved against the current working directory). Pack waypoints are always included regardless of --root. | - |
| `-h`, `--help` | Show this help message and exit. | - |
| `-V`, `--version` | Print version information and exit. | - |

---

### `trailblaze config`

View and set configuration (target app, device defaults, AI provider)

**Synopsis:**

```
trailblaze config [OPTIONS] [<<key>>] [<<value>>]
trailblaze config show
trailblaze config target
trailblaze config models
trailblaze config reset
```

**Arguments:**

| Argument | Description | Required |
|----------|-------------|----------|
| `<<key>>` | Config key to get or set | No |
| `<<value>>` | Value to set | No |

**Options:**

| Option | Description | Default |
|--------|-------------|---------|
| `-h`, `--help` | Show this help message and exit. | - |
| `-V`, `--version` | Print version information and exit. | - |

**Config Keys:**

| Key | Description | Valid Values |
|-----|-------------|-------------|
| `llm` | LLM provider and model (shorthand: provider/model) | provider/model (e.g., openai/gpt-4-1, anthropic/claude-sonnet-4-20250514) or 'none' to disable |
| `llm-provider` | LLM provider | openai, anthropic, google, ollama, openrouter, etc. or 'none' to disable |
| `llm-model` | LLM model ID | e.g., gpt-4-1, claude-sonnet-4-20250514, gemini-3-flash or 'none' to disable |
| `target` | Target app for device connections and custom tools | App target ID. Run 'trailblaze config target' to see all. |
| `agent` | Agent implementation | TRAILBLAZE_RUNNER, MULTI_AGENT_V3 |
| `android-driver` | Android driver type | accessibility, instrumentation |
| `ios-driver` | iOS driver type | host, axe |
| `self-heal` | Enable/disable self-heal (AI takes over) when recorded steps fail | true, false |
| `mode` | CLI working mode: trail (author reproducible trails) or blaze (explore device) | trail, blaze |
| `web-headless` | Default for `--headless` on web devices (CLI flag still wins when explicitly passed) | true, false |
| `device` | Default device platform for CLI commands | android, ios, web |

**Examples:**

```bash
trailblaze config                                    # Show all settings
trailblaze config llm                                # Show current LLM provider/model
trailblaze config llm anthropic/claude-sonnet-4-6    # Set both provider + model
trailblaze config llm-provider openai                # Set provider only
trailblaze config llm-model gpt-4-1                  # Set model only
trailblaze config agent MULTI_AGENT_V3               # Set agent implementation
trailblaze config models                             # List available LLM models
trailblaze config agents                             # List agent implementations
trailblaze config drivers                            # List driver types
```

---

### `trailblaze config show`

Show all settings and authentication status

**Synopsis:**

```
trailblaze config show [OPTIONS]
```

**Options:**

| Option | Description | Default |
|--------|-------------|---------|
| `-h`, `--help` | Show this help message and exit. | - |
| `-V`, `--version` | Print version information and exit. | - |

---

### `trailblaze config target`

List or set the target app

**Synopsis:**

```
trailblaze config target [OPTIONS] [<<targetId>>]
```

**Arguments:**

| Argument | Description | Required |
|----------|-------------|----------|
| `<<targetId>>` | Target app ID to set | No |

**Options:**

| Option | Description | Default |
|--------|-------------|---------|
| `-h`, `--help` | Show this help message and exit. | - |
| `-V`, `--version` | Print version information and exit. | - |

---

### `trailblaze config models`

List available LLM models by provider

**Synopsis:**

```
trailblaze config models [OPTIONS]
```

**Options:**

| Option | Description | Default |
|--------|-------------|---------|
| `-h`, `--help` | Show this help message and exit. | - |
| `-V`, `--version` | Print version information and exit. | - |

---

### `trailblaze config reset`

Reset all settings to defaults

**Synopsis:**

```
trailblaze config reset [OPTIONS]
```

**Options:**

| Option | Description | Default |
|--------|-------------|---------|
| `-h`, `--help` | Show this help message and exit. | - |
| `-V`, `--version` | Print version information and exit. | - |

---

### `trailblaze device`

List and connect devices (Android, iOS, Web)

**Synopsis:**

```
trailblaze device [OPTIONS]
trailblaze device list
trailblaze device connect
```

**Options:**

| Option | Description | Default |
|--------|-------------|---------|
| `-h`, `--help` | Show this help message and exit. | - |
| `-V`, `--version` | Print version information and exit. | - |

---

### `trailblaze device list`

List available devices

**Synopsis:**

```
trailblaze device list [OPTIONS]
```

**Options:**

| Option | Description | Default |
|--------|-------------|---------|
| `--all` | Include hidden platforms (e.g. the Compose desktop driver — `desktop/self`). | - |
| `-h`, `--help` | Show this help message and exit. | - |
| `-V`, `--version` | Print version information and exit. | - |

---

### `trailblaze device connect`

Connect a device to your session (ANDROID, IOS, or WEB)

**Synopsis:**

```
trailblaze device connect [OPTIONS] <<platform>>
```

**Arguments:**

| Argument | Description | Required |
|----------|-------------|----------|
| `<<platform>>` | Device platform: ANDROID, IOS, or WEB | Yes |

**Options:**

| Option | Description | Default |
|--------|-------------|---------|
| `--headless` | For --device web/...: launch the Playwright browser headless. When omitted, falls back to the persisted `web-headless` config (see `trailblaze config web-headless`). Pass --headless=false to force a visible browser, --headless=true to force headless. Ignored for non-web devices. | - |
| `-h`, `--help` | Show this help message and exit. | - |
| `-V`, `--version` | Print version information and exit. | - |

---

### `trailblaze app`

Start or stop the Trailblaze daemon (background service that drives devices)

**Synopsis:**

```
trailblaze app [OPTIONS]
```

**Options:**

| Option | Description | Default |
|--------|-------------|---------|
| `--headless` | Start in headless mode (daemon only, no GUI) | - |
| `--stop` | Stop the running daemon | - |
| `--status` | Check if the daemon is running | - |
| `--foreground` | Run in foreground (blocks terminal). Use for debugging with an attached IDE. | - |
| `-h`, `--help` | Show this help message and exit. | - |
| `-V`, `--version` | Print version information and exit. | - |

---

### `trailblaze mcp`

Start a Model Context Protocol (MCP) server for AI agent integration  Exposes Trailblaze tools via the Model Context Protocol (MCP) so that AI coding agents can control devices.  Quick setup:   Claude Code:  claude mcp add trailblaze -- trailblaze mcp   Cursor:       Add to .cursor/mcp.json with command 'trailblaze mcp'   Windsurf:     Add to MCP config with command 'trailblaze mcp'

**Synopsis:**

```
trailblaze mcp [OPTIONS]
```

**Options:**

| Option | Description | Default |
|--------|-------------|---------|
| `--http` | Use Streamable HTTP transport instead of STDIO. Starts a standalone HTTP MCP server. | - |
| `--direct`, `--no-daemon` | Run as an in-process MCP server over STDIO instead of the default proxy mode. Bypasses the Trailblaze daemon and runs everything in a single process. Use this for environments where the HTTP daemon cannot run. | - |
| `--tool-profile` | Tool profile: FULL or MINIMAL (only device/blaze/verify/ask/trail). Defaults to MINIMAL for STDIO, FULL for HTTP. | - |
| `-h`, `--help` | Show this help message and exit. | - |
| `-V`, `--version` | Print version information and exit. | - |

---

### `trailblaze compile`

Compile pack manifests into resolved target YAMLs

**Synopsis:**

```
trailblaze compile [OPTIONS]
```

**Options:**

| Option | Description | Default |
|--------|-------------|---------|
| `--input`, `-i` | Directory containing one <id>/pack.yaml per pack. Defaults to <workspace-root>/trails/config (workspace root is found by walking up from the current directory looking for `trails/config/`). | - |
| `--output`, `-o` | Directory to emit resolved <id>.yaml files into. Defaults to <workspace-root>/trails/config/dist/targets. | - |
| `-h`, `--help` | Show this help message and exit. | - |
| `-V`, `--version` | Print version information and exit. | - |

<hr/>

**NOTE**: THIS IS GENERATED DOCUMENTATION
