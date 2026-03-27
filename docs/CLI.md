# Trailblaze CLI

Trailblaze - AI-powered UI automation

## Usage

```
trailblaze [OPTIONS] [COMMAND]
```

## Global Options

| Option | Description | Default |
|--------|-------------|---------|
| `--headless` | Start in headless mode (daemon only, no GUI) | - |
| `-h`, `--help` | Show this help message and exit. | - |
| `-V`, `--version` | Print version information and exit. | - |

## Commands

| Command | Description |
|---------|-------------|
| `app` | Launch, stop, or check the status of the Trailblaze application |
| `run` | Run trail.yaml files on a connected device |
| `blaze` | Take a UI action or verify an assertion on a connected device |
| `ask` | Ask a question about what's currently visible on screen |
| `snapshot` | Get raw screenshot and/or view hierarchy from connected device |
| `session` | Start, stop, save, and inspect sessions |
| `mcp` | Start the MCP server |
| `devices` | List all connected devices |
| `config` | View and modify Trailblaze configuration |
| `report` | Generate a report (html, json) for Trailblaze sessions |
| `stop` | Stop the Trailblaze daemon (alias for 'app --stop') |

---

### `trailblaze app`

Launch, stop, or check the status of the Trailblaze application

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
| `-h`, `--help` | Show this help message and exit. | - |
| `-V`, `--version` | Print version information and exit. | - |

---

### `trailblaze run`

Run trail.yaml files on a connected device

**Synopsis:**

```
trailblaze run [OPTIONS] <<trailFile>>
```

**Arguments:**

| Argument | Description | Required |
|----------|-------------|----------|
| `<<trailFile>>` | Path to a .trail.yaml file or directory containing trail files | Yes |

**Options:**

| Option | Description | Default |
|--------|-------------|---------|
| `-d`, `--device` | Device ID to run on (e.g., 'emulator-5554'). If not specified, uses first available device. | - |
| `-a`, `--agent` | Agent: TRAILBLAZE_RUNNER, MULTI_AGENT_V3. Default: TRAILBLAZE_RUNNER | - |
| `--use-recorded-steps` | Use recorded tool sequences instead of LLM inference | - |
| `--set-of-mark` | Enable Set of Mark mode (default: true) | - |
| `-v`, `--verbose` | Enable verbose output | - |
| `--driver` | Driver type to use (e.g., PLAYWRIGHT_NATIVE, ANDROID_HOST). Overrides driver from trail config. | - |
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

### `trailblaze blaze`

Take a UI action or verify an assertion on a connected device

**Synopsis:**

```
trailblaze blaze [OPTIONS] <<goalWords>>
```

**Arguments:**

| Argument | Description | Required |
|----------|-------------|----------|
| `<<goalWords>>` | Goal or assertion (e.g., 'Tap login', 'The email field is visible') | Yes |

**Options:**

| Option | Description | Default |
|--------|-------------|---------|
| `--verify` | Verify an assertion instead of taking an action (exit code 1 if assertion fails) | - |
| `-d`, `--device` | Device platform to connect: ANDROID, IOS, or WEB | - |
| `--json` | Output machine-readable JSON instead of human-readable text | - |
| `--context` | Context from previous steps for situational awareness | - |
| `-v`, `--verbose` | Enable verbose output (show daemon logs, MCP calls) | - |
| `-h`, `--help` | Show this help message and exit. | - |
| `-V`, `--version` | Print version information and exit. | - |

---

### `trailblaze ask`

Ask a question about what's currently visible on screen

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
| `-d`, `--device` | Device platform to connect: ANDROID, IOS, or WEB | - |
| `--json` | Output machine-readable JSON instead of human-readable text | - |
| `-v`, `--verbose` | Enable verbose output (show daemon logs, MCP calls) | - |
| `-h`, `--help` | Show this help message and exit. | - |
| `-V`, `--version` | Print version information and exit. | - |

---

### `trailblaze snapshot`

Get raw screenshot and/or view hierarchy from connected device

**Synopsis:**

```
trailblaze snapshot [OPTIONS]
```

**Options:**

| Option | Description | Default |
|--------|-------------|---------|
| `--screenshot` | Include only the screenshot (no hierarchy) | - |
| `--hierarchy` | Include only the view hierarchy (no screenshot) | - |
| `--verbosity` | Hierarchy verbosity: MINIMAL, STANDARD, or FULL | - |
| `-d`, `--device` | Device platform to connect: ANDROID, IOS, or WEB | - |
| `--json` | Output machine-readable JSON instead of human-readable text | - |
| `--output`, `-o` | Save screenshot to a file (WebP format) | - |
| `-v`, `--verbose` | Enable verbose output (show daemon logs, MCP calls) | - |
| `-h`, `--help` | Show this help message and exit. | - |
| `-V`, `--version` | Print version information and exit. | - |

---

### `trailblaze session`

Start, stop, save, and inspect sessions

**Synopsis:**

```
trailblaze session [OPTIONS]
trailblaze session start
trailblaze session stop
trailblaze session save
trailblaze session info
trailblaze session list
trailblaze session artifacts
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
| `--title`, `-t` | Title for the session (used as trail name when saving) | - |
| `--no-video` | Disable video capture | - |
| `--no-logs` | Disable device log capture | - |
| `-d`, `--device` | Device platform to connect: ANDROID, IOS, or WEB | - |
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
| `--json` | Output machine-readable JSON | - |
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
| `--limit`, `-n` | Maximum number of sessions to show (default: 20) | - |
| `--json` | Output machine-readable JSON | - |
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
| `--json` | Output machine-readable JSON | - |
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

### `trailblaze mcp`

Start the MCP server

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

### `trailblaze devices`

List all connected devices

**Synopsis:**

```
trailblaze devices [OPTIONS]
```

**Options:**

| Option | Description | Default |
|--------|-------------|---------|
| `-h`, `--help` | Show this help message and exit. | - |
| `-V`, `--version` | Print version information and exit. | - |

---

### `trailblaze config`

View and modify Trailblaze configuration

**Synopsis:**

```
trailblaze config [OPTIONS] [<<key>>] [<<value>>]
trailblaze config show
trailblaze config models
trailblaze config agents
trailblaze config drivers
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
| `llm` | LLM provider and model (shorthand: provider/model) | provider/model (e.g., openai/gpt-4-1, anthropic/claude-sonnet-4-20250514) |
| `llm-provider` | LLM provider | openai, anthropic, google, ollama, openrouter, etc. |
| `llm-model` | LLM model ID | e.g., gpt-4-1, claude-sonnet-4-20250514, gemini-3-flash |
| `agent` | Agent implementation | TRAILBLAZE_RUNNER, MULTI_AGENT_V3 |
| `android-driver` | Android driver type | HOST, ONDEVICE, ACCESSIBILITY |
| `ios-driver` | iOS driver type | HOST |
| `set-of-mark` | Enable/disable Set of Mark mode | true, false |
| `ai-fallback` | Enable/disable AI fallback when recorded steps fail | true, false |

**Examples:**

```bash
trailblaze config                                    # Show all settings
trailblaze config llm                                # Show current LLM provider/model
trailblaze config llm anthropic/claude-sonnet-4-6    # Set both provider + model
trailblaze config llm-provider openai                # Set provider only
trailblaze config llm-model gpt-4-1                  # Set model only
trailblaze config agent MULTI_AGENT_V3               # Set agent implementation
trailblaze config set-of-mark false                  # Disable Set of Mark
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

### `trailblaze config agents`

List available agent implementations

**Synopsis:**

```
trailblaze config agents [OPTIONS]
```

**Options:**

| Option | Description | Default |
|--------|-------------|---------|
| `-h`, `--help` | Show this help message and exit. | - |
| `-V`, `--version` | Print version information and exit. | - |

---

### `trailblaze config drivers`

List available driver types

**Synopsis:**

```
trailblaze config drivers [OPTIONS]
```

**Options:**

| Option | Description | Default |
|--------|-------------|---------|
| `-h`, `--help` | Show this help message and exit. | - |
| `-V`, `--version` | Print version information and exit. | - |

---

### `trailblaze report`

Generate a report (html, json) for Trailblaze sessions

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

### `trailblaze stop`

Stop the Trailblaze daemon (alias for 'app --stop')

**Synopsis:**

```
trailblaze stop [OPTIONS]
```

**Options:**

| Option | Description | Default |
|--------|-------------|---------|
| `-h`, `--help` | Show this help message and exit. | - |
| `-V`, `--version` | Print version information and exit. | - |

<hr/>

**NOTE**: THIS IS GENERATED DOCUMENTATION
