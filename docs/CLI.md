# Trailblaze CLI

Trailblaze - AI-powered device automation

## Usage

```
trailblaze [OPTIONS] [COMMAND]
```

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
| `tool` | Run a Trailblaze tool by name (e.g., tapOnElement, inputText) |
| `toolbox` | Browse available tools by target app and platform |
| `trail` | Run a trail file (.trail.yaml) — execute a scripted test on a device |
| `session` | Every blaze records a session — save it as a replayable trail |
| `report` | Generate an HTML or JSON report from session recordings |
| `config` | View and set configuration (target app, device defaults, AI provider) |
| `device` | List and connect devices (Android, iOS, Web) |
| `app` | Start or stop the Trailblaze daemon (background service that drives devices) |
| `mcp` | Start a Model Context Protocol (MCP) server for AI agent integration |

---

### `trailblaze blaze`

Drive a device with AI — describe what to do in plain English

**Synopsis:**

```
trailblaze blaze [OPTIONS] [<<goalWords>>]
```

**Arguments:**

| Argument | Description | Required |
|----------|-------------|----------|
| `<<goalWords>>` | Objective or assertion (e.g., 'Tap login', 'The email field is visible') | No |

**Options:**

| Option | Description | Default |
|--------|-------------|---------|
| `--verify` | Verify an assertion instead of taking an action (exit code 1 if assertion fails) | - |
| `-d`, `--device` | Device: platform (android, ios, web) or platform/id (e.g., android/emulator-5554). Switches the daemon's active device for all clients. Required for multi-device workflows. | - |
| `--context` | Context from previous steps for situational awareness | - |
| `-v`, `--verbose` | Enable verbose output (show daemon logs, MCP calls) | - |
| `--target` | Target app ID. Saved for future commands. | - |
| `--fast` | Text-only mode: skip screenshots, use text-only screen analysis (no vision tokens sent to LLM), and skip disk logging. Also enabled by BLAZE_FAST=1 env var. | - |
| `--save` | Save current session as a trail file. Shows steps if --setup not specified. | - |
| `--setup` | Step range for setup/trailhead (e.g., '1-3'). Use with --save. | - |
| `--no-setup` | Save without setup steps. Use with --save. | - |
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
| `-d`, `--device` | Device: platform (android, ios, web) or platform/id (e.g., android/emulator-5554). Switches the daemon's active device for all clients. Required for multi-device workflows. | - |
| `-v`, `--verbose` | Enable verbose output (show daemon logs, MCP calls) | - |
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
| `-d`, `--device` | Device: platform (android, ios, web) or platform/id | - |
| `-v`, `--verbose` | Enable verbose output | - |
| `--fast` | Text-only mode: skip screenshots, use text-only screen analysis (no vision tokens sent to LLM), and skip disk logging. Also enabled by BLAZE_FAST=1 env var. | - |
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
| `-d`, `--device` | Device: platform (android, ios, web) or platform/id | - |
| `-v`, `--verbose` | Enable verbose output | - |
| `--bounds` | Include bounding box {x,y,w,h} for each element | - |
| `--offscreen` | Include offscreen elements marked (offscreen) | - |
| `--screenshot` | Save a screenshot to disk and print the file path | - |
| `-h`, `--help` | Show this help message and exit. | - |
| `-V`, `--version` | Print version information and exit. | - |

---

### `trailblaze tool`

Run a Trailblaze tool by name (e.g., tapOnElement, inputText)

**Synopsis:**

```
trailblaze tool [OPTIONS] [<<toolName>>] [<<argPairs>>]
```

**Arguments:**

| Argument | Description | Required |
|----------|-------------|----------|
| `<<toolName>>` | Tool name (e.g., web_click, tapOnElement) | No |
| `<<argPairs>>` | Tool arguments as key=value pairs (e.g., ref="Sign In") | No |

**Options:**

| Option | Description | Default |
|--------|-------------|---------|
| `--objective`, `-o` | Natural language intent — describe what, not how. If the UI changes, Trailblaze uses this to retry the step with AI. 'Navigate to Settings' survives a redesign; 'tap button at 200,400' does not. | - |
| `--yaml` | Raw YAML tool sequence (multiple tools in one call) | - |
| `-d`, `--device` | Device: platform (android, ios, web) or platform/id | - |
| `-v`, `--verbose` | Enable verbose output | - |
| `--fast` | Text-only mode: skip screenshots, use text-only screen analysis (no vision tokens sent to LLM), and skip disk logging. Also enabled by BLAZE_FAST=1 env var. | - |
| `--target` | Target app ID. Saved for future commands. | - |
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
| `<<trailFile>>` | Path to a .trail.yaml file or directory containing trail files | Yes |

**Options:**

| Option | Description | Default |
|--------|-------------|---------|
| `-d`, `--device` | Device: platform (android, ios, web), platform/instance-id, or instance ID | - |
| `-a`, `--agent` | Agent: TRAILBLAZE_RUNNER, MULTI_AGENT_V3. Default: TRAILBLAZE_RUNNER | - |
| `--use-recorded-steps` | Use recorded tool sequences instead of LLM inference | - |
| `-v`, `--verbose` | Enable verbose output | - |
| `--driver` | Driver type to use (e.g., PLAYWRIGHT_NATIVE, ANDROID_ONDEVICE_INSTRUMENTATION). Overrides driver from trail config. | - |
| `--show-browser` | Show the browser window (default: headless). Useful for debugging web trails. | - |
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
| `--capture-all` | Enable all capture streams: video, logcat (local dev mode) | - |
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
| `-d`, `--device` | Device: platform (android, ios, web) or platform/id (e.g., ios/DEVICE-UUID). Switches the daemon's active device for all clients. Required for multi-device workflows. | - |
| `--title` | Title for the session (used as trail name when saving) | - |
| `--no-video` | Disable video capture | - |
| `--no-logs` | Disable device log capture | - |
| `-v`, `--verbose` | Enable verbose output | - |
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

Save the current recording as a trail without ending the session

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

### `trailblaze report`

Generate an HTML or JSON report from session recordings

**Synopsis:**

```
trailblaze report [OPTIONS]
```

**Options:**

| Option | Description | Default |
|--------|-------------|---------|
| `--open` | Open the report in the default browser after generation | - |
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
| `android-driver` | Android driver type | HOST, ONDEVICE, ACCESSIBILITY |
| `ios-driver` | iOS driver type | HOST |
| `ai-fallback` | Enable/disable AI fallback when recorded steps fail | true, false |
| `mode` | CLI working mode: trail (author reproducible trails) or blaze (explore device) | trail, blaze |
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

<hr/>

**NOTE**: THIS IS GENERATED DOCUMENTATION
