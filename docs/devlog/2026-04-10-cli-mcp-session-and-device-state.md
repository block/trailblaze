---
title: "CLI and MCP Session Management: Device State and Multi-Terminal Behavior"
type: devlog
date: 2026-04-10
---

# CLI and MCP Session Management: Device State and Multi-Terminal Behavior

## Summary

Investigation into how the Trailblaze daemon manages device state across CLI
invocations, MCP proxy sessions, and the desktop GUI. The daemon maintains a
single "current device" globally in memory. This works well for single-terminal
workflows but creates surprises when multiple terminals or MCP clients interact
with the same daemon. Documents current behavior, user-facing guidance, and
future improvement paths.

## What We Discovered

### The daemon is a singleton

All clients share one daemon process on the machine:

```
Terminal A (CLI)  ─┐
Terminal B (CLI)  ─┼── HTTP POST /mcp ──▶  Daemon (:52525)
Claude Code (MCP) ─┤                       (single process, in-memory state)
Desktop GUI       ─┘
```

The daemon listens on `localhost:52525`. CLI commands talk to it via short-lived
HTTP requests. MCP clients connect through a persistent STDIO-to-HTTP proxy
(`trailblaze mcp` / `McpProxy.kt`). The desktop GUI runs in the same JVM.

### MCP sessions are per-client, device state is global

Each `trailblaze mcp` proxy gets its own MCP session ID from the daemon
(tracked via `mcp-session-id` header). Multiple Claude Code instances each
get independent sessions. But device state — which device is "currently
selected" — is daemon-global. It's a single slot, not scoped per session.

When any client calls `--device android/emulator-5554`, the daemon switches
its global device binding. Every other client's next call (if they omit
`--device`) will use that device.

### Settings persistence gap

Two `trailblaze-settings.json` files exist:

- `~/.trailblaze/trailblaze-settings.json` — written by the desktop GUI
- `.trailblaze/trailblaze-settings.json` — project-level, also GUI-managed

Both contain `lastSelectedDeviceInstanceIds`. The CLI reads this on startup
as the initial default but **does not write it back** when switching devices.
If the daemon restarts, it reverts to whatever the GUI last saved.

### Parallel multi-device works (with explicit device IDs)

Tested: two CLI commands running simultaneously on different devices:

```
15:12:04  Android: blaze "Tap 'Tap Me'" (6.2s)
15:12:15  iOS:     blaze "Tap next" (7.6s)    ← overlapping with Android
```

The daemon created separate sessions per device (`_yaml_6258` for Android,
`_yaml_315` for iOS), each with independent logs, screenshots, and video.
No cross-contamination. This is a natural consequence of sessions being
keyed by device instance ID internally.

### What "Switching device" means

When the CLI prints `Switching device — starting new session`, it means the
daemon is creating a new session for the target device. The previous device's
session remains valid — it's not torn down. But the daemon's "current device"
pointer moves to the new one.

## User-Facing Guidance

### For single-device workflows (most users)

This just works. Run `trailblaze blaze --device ios` once to pick your device.
Subsequent calls (`trailblaze blaze "Tap login"`, `trailblaze ask "What screen?"`)
reuse that device automatically. The daemon remembers your last selection.

### For multi-device or multi-terminal workflows

The daemon is shared across all terminals on the machine. Changing the device
in one terminal affects all others. To work with multiple devices safely:

**Always pass `--device` on every call.** There is no per-terminal device
affinity. Without `--device`, you get whichever device was last selected by
any client — another terminal, Claude Code, or the desktop GUI.

```bash
# Terminal A — always specify the device
trailblaze blaze --device android/emulator-5554 "Tap login"
trailblaze ask --device android/emulator-5554 "What screen is this?"

# Terminal B — always specify the device
trailblaze blaze --device ios/E5BDD6FB "Tap login"
trailblaze ask --device ios/E5BDD6FB "What screen is this?"
```

This is safe for parallel execution — the daemon handles concurrent requests
to different devices without conflict.

### For MCP clients (Claude Code, Cursor, etc.)

Each MCP client gets its own session for transport, but shares the daemon's
device state. If Claude Code switches to Android and a CLI terminal switches
to iOS, the next unqualified call from either side uses iOS.

The MCP proxy (`McpProxy.kt`) replays the last `device` tool call on daemon
restart (line 84-85, 398-400), so a single MCP client survives daemon restarts
and re-binds to its device. But two MCP clients replaying different devices
will race.

### What to watch out for

1. **Daemon restart resets to GUI's last selection.** The CLI doesn't persist
   its device choice. If the daemon restarts, the device reverts to whatever
   `lastSelectedDeviceInstanceIds` says in `trailblaze-settings.json`.

2. **The desktop GUI can change your device.** Selecting a device in the GUI
   updates the daemon's current device and writes to settings. CLI users
   may not realize the GUI affected their session.

3. **`config llm` changes are global too.** Switching the LLM affects all
   clients. This is intentional (one LLM config per workspace) but worth
   noting for multi-terminal awareness.

## How It Could Be Better

### Short term: CLI persists device selection

The CLI should write `lastSelectedDeviceInstanceIds` to
`trailblaze-settings.json` when `--device` is used. This ensures daemon
restarts preserve the CLI user's last choice instead of silently reverting
to the GUI's selection. Small change, removes the biggest surprise.

### Medium term: Per-MCP-session device binding

The daemon already tracks MCP session IDs. It could maintain a device binding
per session instead of globally. Each MCP proxy (and each CLI invocation that
carries a session ID) would get its own device without affecting others.

This would require:
- Daemon maps `mcp-session-id → device` instead of a global device slot
- CLI invocations carry a session ID (could be derived from a per-terminal
  env var like `TRAILBLAZE_SESSION` or a file in `/tmp`)
- Fallback: if no session ID, use global default (backward compatible)

This is the clean solution but touches the daemon's session management layer.

### Long term: Named sessions

```bash
# Terminal A
trailblaze session start --name android-test --device android/emulator-5554
trailblaze blaze --session android-test "Tap login"

# Terminal B
trailblaze session start --name ios-test --device ios/E5BDD6FB
trailblaze blaze --session ios-test "Tap login"
```

Named sessions are explicit, portable across terminals, and composable.
An agent could manage multiple named sessions for cross-platform testing.
This builds on per-session device binding but adds a user-facing identity
layer.

## Verified Behavior (Test Matrix)

Ran 8 commands across Android and iOS, with LLM enabled and disabled:

| Phase | Command | Android | iOS | Result |
|-------|---------|---------|-----|--------|
| Cloud LLM | `ask` | Described demo app | Described iOS test app | Pass |
| Cloud LLM | `blaze` | Tapped button, count 0→1 | Tapped Next | Pass |
| No LLM (`none`) | `ask` | Described from a11y tree | Described iOS test app | Pass |
| No LLM (`none`) | `blaze` | Tapped button, count 1→2 | Tapped Use Email | Pass |

All `blaze` objectives in no-LLM mode completed with `callCount: 0` and
`llmExplanation: "Completed via recording"` — confirming the snapshot/recording
path works independently of any configured LLM.

## Key Files

- `McpProxy.kt` — STDIO-to-HTTP proxy, tracks `lastDeviceToolCall` for replay
- `trailblaze-settings.json` — persisted settings (GUI-managed, CLI reads but doesn't write)
- `TrailblazeMcpBridge` — daemon-side session and device management
