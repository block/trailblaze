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
| `-h`, `--help` | Show this help message and exit. | - |
| `-V`, `--version` | Print version information and exit. | - |

## Commands

| Command | Description |
|---------|-------------|
| `run` | Run a .trail.yaml file on a connected device |
| `list-devices` | List all connected devices |
| `config` | View and modify Trailblaze configuration |
| `status` | Check if the Trailblaze daemon is running |
| `stop` | Stop the Trailblaze daemon |
| `auth` | Check and display LLM authentication status |

---

### `trailblaze run`

Run a .trail.yaml file on a connected device

**Synopsis:**

```
trailblaze run [OPTIONS] <<trailFile>>
```

**Arguments:**

| Argument | Description | Required |
|----------|-------------|----------|
| `<<trailFile>>` | Path to the .trail.yaml file to execute | Yes |

**Options:**

| Option | Description | Default |
|--------|-------------|---------|
| `--headless` | Run without GUI (MCP server mode) | - |
| `-d`, `--device` | Device ID to run on (e.g., 'emulator-5554'). If not specified, uses first available device. | - |
| `--use-recorded-steps` | Use recorded tool sequences instead of LLM inference | - |
| `--set-of-mark` | Enable Set of Mark mode (default: true) | - |
| `-v`, `--verbose` | Enable verbose output | - |
| `--host` | Force host-based execution (run agent on this machine, not on device). Required for V3 agent testing. | - |
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
