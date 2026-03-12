---
title: MCP Integration
---

Trailblaze exposes a clean **5-tool MCP API** for mobile UI automation and test authoring.

## Quick Start

```
# Connect to a device
device(action=ANDROID)

# Take actions using natural language
step("Tap the login button")
step("Enter 'test@example.com' in the email field")

# Verify results
verify("Welcome screen is visible")

# Save your session as a reusable test
trail(action=SAVE, name="login_test")
```

## The 5 MCP Tools

| Tool | Purpose | Example |
|------|---------|---------|
| `device` | Connect to a device | `device(action=ANDROID)` |
| `step` | Take an action toward a goal | `step("Tap the submit button")` |
| `verify` | Check if something is true | `verify("Error message is visible")` |
| `ask` | Ask a question, get an answer | `ask("What is the current balance?")` |
| `trail` | Manage trails (save/run/list) | `trail(action=SAVE, name="my_test")` |

## Two Workflows

### 1. Device Control (Exploration)

For quick exploration and automation without creating reusable tests:

```
device(action=ANDROID)                    # Connect
step("Open the Settings app")             # Interact
step("Scroll down to About")
ask("What Android version is shown?")     # Query
```

Your session is automatically recorded. Save it anytime with `trail(action=SAVE, name="...")`.

### 2. Test Authoring

For creating reusable test cases:

```
trail(action=START, name="checkout_flow", platform=ANDROID)   # Start named trail
step("Add item to cart")
step("Proceed to checkout")
step("Enter payment details")
verify("Order confirmation is displayed")
trail(action=SAVE)                                            # Save the trail
```

Run saved trails later:
```
trail(action=RUN, name="checkout_flow")   # Deterministic replay
```

## Tool Reference

### device

Connect to a mobile device.

| Action | Description |
|--------|-------------|
| `ANDROID` | Auto-connect to first Android device |
| `IOS` | Auto-connect to first iOS device |
| `LIST` | List available devices |
| `CONNECT` | Connect by specific device ID |

```
device(action=ANDROID)
device(action=LIST)
device(action=CONNECT, deviceId="emulator-5554")
```

### step

Execute an action using natural language. Trailblaze's inner agent analyzes the screen and performs the necessary UI interactions.

```
step("Tap the Login button")
step("Enter 'hello@example.com' in the email field")
step("Scroll down until the Submit button is visible")
step("Swipe left to dismiss the notification")
```

Returns: Success/failure status with screen summary.

### verify

Check if a condition is true on the current screen.

```
verify("The welcome message is visible")
verify("The cart shows 3 items")
verify("No error messages are displayed")
```

Returns: `{ passed: true/false, reason: "..." }`

### ask

Ask a question about the current screen state.

```
ask("What is the title of this screen?")
ask("How many items are in the list?")
ask("What error message is shown?")
```

Returns: The answer as a string.

### trail

Manage trails (reusable test recordings).

| Action | Description |
|--------|-------------|
| `START` | Begin a named trail (optionally connect to device) |
| `SAVE` | Save current session as a trail file |
| `RUN` | Execute a saved trail (deterministic, no AI) |
| `LIST` | List available devices or trails |
| `END` | End session without saving |

```
# Start a named trail
trail(action=START, name="login_flow", platform=ANDROID)

# Save session (works anytime, even without START)
trail(action=SAVE, name="my_test")

# Run a saved trail
trail(action=RUN, name="login_flow")

# List trails matching a filter
trail(action=LIST, filter="login")

# List available devices
trail(action=LIST)
```

## Trail Files

Trails are saved as `.trail.yaml` files in the `trails/` directory:

```yaml
- config:
    id: login-flow
    title: Login Flow
    source:
      type: HANDWRITTEN

- prompts:
    - step: "Tap the login button"
      recording:
        tools:
          - tapOnElementByNodeId:
              nodeId: 42
    - verify: "Welcome message is visible"
```

**Deterministic execution**: When you run a trail with `trail(action=RUN)`, Trailblaze replays the recorded tool calls without AI. This ensures consistent, fast test execution.

## Setup

### Running the Server

```shell
./trailblaze
```

This starts:
- MCP server on `http://localhost:52525/mcp`
- Web UI for monitoring

### Connecting from Goose

1. Download [Goose Desktop](https://block.github.io/goose/docs/getting-started/installation/)
2. Add a new extension:
   - **ID**: `trailblaze`
   - **Name**: `Trailblaze`
   - **Type**: `streamable_http`
   - **URI**: `http://localhost:52525/mcp`

### Connecting from Firebender/Cursor

- **ID**: `trailblaze`
- **Name**: `Trailblaze`
- **Description**: `A tool to facilitate the creation and execution of mobile ui tests using natural language using the Trailblaze library.`
- **Type**: `streamable_http`
- **URI**: `http://localhost:52525/mcp` (use your configured HTTP port if different from the default)

Add to `~/.firebender/firebender.json`:

```json
{
  "mcp": {
    "Trailblaze": {
      "url": "http://localhost:52525/mcp"
    }
  }
}
```

Then click **Refresh All** in MCP settings.

## Architecture

```
MCP Client (Claude, Goose, Firebender)
              │
    ┌─────────┴─────────┐
    ▼                   ▼
 device              trail
(connect)         (test author)
    │                   │
    └─────────┬─────────┘
              ▼
      step / verify / ask
    (inner agent executes)
              │
              ▼
      Trail Recording Layer
    (captures actions taken)
              │
              ▼
        .trail.yaml file
```

The **inner agent** handles all screen analysis and UI interaction. MCP clients work with natural language - no need to deal with coordinates or view hierarchies.

## Advanced: Two-Tier Integration

For MCP clients that want finer control, Trailblaze offers **two-tier tools** where your client acts as the outer agent (strategist) and Trailblaze provides screen analysis:

| Tool | Purpose |
|------|---------|
| `getScreenAnalysis` | Ask Trailblaze to analyze screen and recommend an action |
| `executeUiAction` | Execute a specific UI action |

This enables:
- **Cross-system orchestration** - Coordinate mobile UI with database, filesystem, API calls
- **Custom replanning logic** - Your client decides when to retry or try alternatives
- **Cost optimization** - Trailblaze uses a cheap vision model for screen analysis

See the [Two-Tier Integration Guide](./two-tier-integration.md) for details.

## Troubleshooting

### Device not found

```
device(action=LIST)   # See what's available
```

- Android: Ensure USB debugging is enabled and `adb devices` shows your device
- iOS: Ensure device is trusted and developer mode is enabled

### Trail not found

```
trail(action=LIST, filter="login")   # Search for trails
```

Trails are stored in the `trails/` directory relative to where Trailblaze is running.

### Tools not appearing

After restarting Trailblaze:
- In Goose: Re-enable the extension
- In Firebender: Click "Refresh All" in MCP settings

## Development

Test with the [MCP Inspector](https://github.com/modelcontextprotocol/inspector):

```shell
DANGEROUSLY_OMIT_AUTH=true npm exec @modelcontextprotocol/inspector
```

Add Trailblaze using Streamable HTTP transport: `http://localhost:52525/mcp`
