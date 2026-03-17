# Trailblaze CLI

Trailblaze - AI-powered UI automation

## Usage

```
trailblaze [OPTIONS] [COMMAND]
```

## Global Options

| Option | Description | Default |
|--------|-------------|---------|
| `--headless` | Start in headless mode (MCP server only, no GUI) | - |
| `-p`, `--port` | HTTP port for the Trailblaze server (default: 52525) | - |
| `--https-port` | HTTPS port for the Trailblaze server (default: 8443) | - |
| `-h`, `--help` | Show this help message and exit. | - |
| `-V`, `--version` | Print version information and exit. | - |

## Commands

| Command | Description |
|---------|-------------|
| `run` | Run a .trail.yaml file or directory of trail files on a connected device |
| `mcp` | Start the MCP server |
| `list-devices` | List all connected devices |
| `config` | View and modify Trailblaze configuration |
| `status` | Check if the Trailblaze daemon is running |
| `stop` | Stop the Trailblaze daemon |
| `help` | When no COMMAND is given, the usage help for the main command is displayed. |

---

### `trailblaze run`

Run a .trail.yaml file or directory of trail files on a connected device

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
| `--headless` | Run without GUI (MCP server mode) | - |
| `-d`, `--device` | Device ID to run on (e.g., 'emulator-5554'). If not specified, uses first available device. | - |
| `-a`, `--agent` | Agent: TRAILBLAZE_RUNNER, TWO_TIER_AGENT, MULTI_AGENT_V3. Default: TRAILBLAZE_RUNNER | - |
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
| `--markdown` | Generate a markdown report after execution | - |
| `--no-daemon` | Run in-process without delegating to or starting a persistent daemon. The server shuts down when the run completes. | - |
| `-p`, `--port` | HTTP port for the Trailblaze server (overrides parent --port if set) | - |
| `--https-port` | HTTPS port for the Trailblaze server (overrides parent --https-port if set) | - |
| `--compose-port` | RPC port for Compose driver connections (default: 52600) | - |
| `--capture-video` | Record device screen video for the session (on by default, use --no-capture-video to disable) | - |
| `--capture-logcat` | Capture logcat output filtered to the app under test (local dev mode) | - |
| `--capture-all` | Enable all capture streams: video, logcat (local dev mode) | - |
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

### `trailblaze list-devices`

List all connected devices

**Synopsis:**

```
trailblaze list-devices [OPTIONS]
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
| `agent` | Agent implementation | TRAILBLAZE_RUNNER, TWO_TIER_AGENT, MULTI_AGENT_V3 |
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
trailblaze config agent TWO_TIER_AGENT               # Set agent implementation
trailblaze config set-of-mark false                  # Disable Set of Mark
trailblaze config models                             # List available LLM models
trailblaze config agents                             # List agent implementations
trailblaze config drivers                            # List driver types
```

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

### `trailblaze status`

Check if the Trailblaze daemon is running

**Synopsis:**

```
trailblaze status [OPTIONS]
```

**Options:**

| Option | Description | Default |
|--------|-------------|---------|
| `-h`, `--help` | Show this help message and exit. | - |
| `-V`, `--version` | Print version information and exit. | - |

---

### `trailblaze stop`

Stop the Trailblaze daemon

**Synopsis:**

```
trailblaze stop [OPTIONS]
```

**Options:**

| Option | Description | Default |
|--------|-------------|---------|
| `-f`, `--force` | Force stop (kill process) if graceful shutdown fails | - |
| `-h`, `--help` | Show this help message and exit. | - |
| `-V`, `--version` | Print version information and exit. | - |

---

### `trailblaze help`

When no COMMAND is given, the usage help for the main command is displayed. If a COMMAND is specified, the help for that command is shown.

**Synopsis:**

```
trailblaze help [OPTIONS] [<COMMAND>]
```

**Arguments:**

| Argument | Description | Required |
|----------|-------------|----------|
| `<COMMAND>` | The COMMAND to display the usage help message for. | No |

**Options:**

| Option | Description | Default |
|--------|-------------|---------|
| `-h`, `--help` | Show usage help for the help command and exit. | - |

<hr/>

**NOTE**: THIS IS GENERATED DOCUMENTATION
