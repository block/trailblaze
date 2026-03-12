# Trailblaze CLI

Trailblaze - AI-powered mobile UI automation

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
| `auth` | Check and display LLM authentication status |

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
| `--llm-provider` | LLM provider override (e.g.,  openai, anthropic, google) | - |
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
| `--stdio` | Use STDIO transport (stdin/stdout) instead of HTTP. Required for MCP client integrations. | - |
| `--tool-profile` | Tool profile: FULL or MINIMAL (only device/blaze/verify/ask/trail). Defaults to MINIMAL for --stdio, FULL for HTTP. | - |
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
trailblaze config [OPTIONS]
```

**Options:**

| Option | Description | Default |
|--------|-------------|---------|
| `--android-driver` | Android driver: HOST or ONDEVICE (instrumentation) | - |
| `--ios-driver` | iOS driver: HOST (only option for iOS) | - |
| `--llm-provider` | LLM provider: openai, anthropic, google, ollama, openrouter, etc. | - |
| `--llm-model` | LLM model ID (e.g., gpt-4-1, claude-sonnet-4-20250514, goose-gpt-4-1) | - |
| `--agent` | Agent implementation: TRAILBLAZE_RUNNER, TWO_TIER_AGENT, MULTI_AGENT_V3 | - |
| `--set-of-mark` | Enable/disable Set of Mark mode | - |
| `--ai-fallback` | Enable/disable AI fallback when recorded steps fail | - |
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

### `trailblaze auth`

Check and display LLM authentication status

**Synopsis:**

```
trailblaze auth [OPTIONS]
```

**Options:**

| Option | Description | Default |
|--------|-------------|---------|
| `-h`, `--help` | Show this help message and exit. | - |
| `-V`, `--version` | Print version information and exit. | - |

<hr/>

**NOTE**: THIS IS GENERATED DOCUMENTATION
