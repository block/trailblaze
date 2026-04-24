---
title: "Agentic Development Loop"
type: decision
date: 2026-03-09
---

# Trailblaze Decision 035: Agentic Development Loop

## Context

Mobile developers currently debug UI issues through a manual cycle: edit code, build, deploy, manually navigate to the screen, check the fix, repeat. This cycle is slow and breaks flow — the developer spends more time navigating to the right screen than actually thinking about the fix.

Coding agents (Claude Code, Cursor, etc.) can already edit code and run builds autonomously. What they can't do is interact with the device — tap buttons, navigate screens, verify UI state. Trailblaze fills this gap via MCP.

## Decision

### Vision

A coding agent autonomously:
1. Edits code to fix a bug or build a feature
2. Builds and deploys the app (the coding agent handles this natively)
3. Uses Trailblaze via MCP to control the device, test UI, and read results
4. Iterates until the fix is verified

Trailblaze is the **hands and eyes for the device**. The coding agent is the **brain for the code**. They communicate via MCP. The coding agent talks to Trailblaze like a user — high-level goals, not individual taps.

### Architecture

```
Coding Agent (Claude Code)              Trailblaze MCP Server
┌─────────────────────────┐             ┌──────────────────────────┐
│ • Reads/writes code     │   MCP       │ • MultiAgentV3 handles   │
│ • Runs builds           │◄──────────►│   multi-step navigation  │
│ • Sends goals:          │  (STDIO)    │ • Screen analysis (vision)│
│   "navigate to settings"│             │ • Course correction       │
│ • Reads file paths for  │             │ • Trail record/replay     │
│   logcat, screenshots   │             │ • Crash detection         │
│ • Never sees screenshots│             │ • Logcat capture          │
│   or view hierarchies   │             │ • Returns text summaries  │
└─────────────────────────┘             └──────────────────────────┘
```

### Design Principles

**1. MCP is the primary interface.** All device interaction goes through MCP. The CLI is for builds (coding agent handles natively) and batch test runs in CI.

**2. Text-only MCP responses.** No screenshots, no view hierarchies in responses. This protects the coding agent's context window. Trailblaze absorbs all vision/UI data internally and returns natural language summaries.

**3. File paths for raw data.** MCP responses include `sessionDir` pointing to logcat, screenshots, session logs. The coding agent reads these with its native file tools when needed (e.g., reading crash stack traces).

**4. Goal-level interaction.** The coding agent says "navigate to account settings", not "tap menu, tap settings, tap account." MultiAgentV3 handles multi-step execution with course correction.

**5. Blaze once, trail forever.** Setup navigation is explored once by AI (costs LLM). After that, it replays deterministically (free, instant). After every rebuild, the agent replays the setup trail to get back to the screen under test without AI cost.

**6. Mode-aware behavior.** When `TRAILBLAZE_AS_AGENT`, `step()` uses MultiAgentV3 for multi-step goals. When `MCP_CLIENT_AS_AGENT`, `step()` keeps single-action behavior for clients that want fine-grained control.

### The Dev Loop in Practice

```
Developer: "The withdraw button doesn't work after entering an amount"

Coding Agent:
  1. Reads the relevant code, identifies the bug
  2. Fixes the code
  3. Runs: ./gradlew installDebug
  4. Calls: trail(RUN, "navigate-to-money-tab")     ← instant replay, no AI
  5. Calls: step("enter $50 and tap Withdraw")       ← AI navigates
  6. Calls: verify("withdrawal confirmation shown")  ← AI checks
  7. Result: "Verification failed — error dialog shown: 'Invalid amount'"
  8. Reads: <sessionDir>/logcat.log                  ← finds stack trace
  9. Fixes the bug based on the stack trace
  10. Repeats from step 3
```

Steps 4-6 take seconds. The developer's fix-build-test cycle drops from minutes to seconds for the navigation portion.

### MCP Tools

| Tool | Purpose | Multi-step? |
|---|---|---|
| `device()` | Connect to devices (LIST/CONNECT/ANDROID/IOS) | No |
| `step()` | Execute a UI goal ("navigate to settings") | Yes — MultiAgentV3 |
| `verify()` | Assert something about the screen ("login button is visible") | No — single screen analysis |
| `ask()` | Question about screen state ("what error is shown?") | No — single screen analysis |
| `trail()` | Manage trails (START/SAVE/RUN/LIST/END) | No |
| `setAppTarget()` | Set or create the target app by package name | No |

#### step() — Goal-Level Execution

When `TrailblazeMcpMode == TRAILBLAZE_AS_AGENT`:

```
step("navigate to settings")
→ Builds single-objective YAML from the goal
→ Calls runYamlBlocking() with MULTI_AGENT_V3
→ MultiAgentV3 runs multi-step: tap menu → tap Settings → done
→ Captures final screen as NL summary
→ Returns: {
    "success": true,
    "result": "Navigated to settings. Screen shows: Account, Notifications, Privacy.",
    "sessionDir": "/path/to/logs/session_abc/"
  }
```

#### MCP Response Format

All tool responses include:

| Field | Purpose |
|---|---|
| `success` | Did the action succeed? |
| `result` | NL summary of what happened and current screen state |
| `sessionDir` | Absolute path to session logs (logcat, screenshots, hierarchies) |
| `appState` | RUNNING, CRASHED, NOT_RESPONDING, NOT_RUNNING |

The coding agent uses `sessionDir` to read raw data when needed:
- `<sessionDir>/logcat.log` — crash stack traces
- `<sessionDir>/screenshots/` — visual state (only when debugging Trailblaze itself)
- `<sessionDir>/session.log` — detailed execution log

### Trailhead: Setup as a Checkpoint

> See [Decision 026: Trail YAML v2 Syntax](2026-03-06-trail-yaml-v2-syntax.md) for the full trailhead specification.

The trailhead is the setup portion of a test — launch the app, sign in, navigate to the target screen. In the dev loop:

1. **First time**: The agent blazes the setup ("launch app and navigate to Money tab"). Trailblaze explores via AI.
2. **Recording saved**: The trailhead steps get recorded as a trail.
3. **Every rebuild after**: `trail(RUN, "navigate-to-money-tab")` replays the setup instantly. No AI cost, deterministic.

If the app's UI changes (new onboarding flow, redesigned nav), the trail breaks. The system falls back to re-blazing from the NL descriptions and saves a new recording. The developer doesn't need to intervene.

### Recording Optimization

> See [Decision 034: Recording Optimization Pipeline](2026-03-09-recording-optimization-pipeline.md) for the full specification.

In the dev loop, recordings are a **cache**, not a commitment:

- **One-shot post-processing**: after the first blaze, compute best-effort selectors and extract memory variables
- **If replay works**: saved an LLM call
- **If replay fails**: use data from both runs (blaze + failed replay) to refine selectors once
- **If still fails**: fall back to NL and keep going

The trailhead recording is the most valuable to optimize — it's replayed dozens of times during a debugging session. Test steps may blaze every time since the code under test is changing.

### Dynamic App Targets

External developers create app targets on the fly:

```
setAppTarget(packageName="com.example.myapp", alias="myapp")
```

- If an existing target matches the package, switches to it
- Otherwise, creates a lightweight `DynamicAppTarget` with the package name
- Dynamic targets persist for the MCP session (not across restarts)
- Built-in app targets continue to work with their custom tools

### Crash Detection and Recovery

When the app crashes during a step:

1. `step()` detects the crash via `ExceptionalScreenState` or process check
2. Response includes `appState: CRASHED` and `sessionDir`
3. The coding agent reads `<sessionDir>/logcat.log` for the stack trace
4. The coding agent fixes the code, rebuilds, and replays the trailhead to get back to the crash point

This closes the loop — the agent can autonomously detect crashes, read the cause, fix the code, and retry.

### Documentation for Developers

#### .mcp.json — Drop-in MCP Config

```json
{
  "mcpServers": {
    "trailblaze": {
      "command": "./trailblaze",
      "args": ["mcp"]
    }
  }
}
```

#### Agent Instructions Template (for CLAUDE.md)

```markdown
## Mobile UI Testing with Trailblaze

Trailblaze is connected as an MCP server for mobile device control.

### Quick start
1. Connect: device(action=ANDROID) or device(action=IOS)
2. Set app: setAppTarget(packageName="com.example.myapp", alias="myapp")
3. Interact: step("navigate to the sign-in screen")
4. Verify: verify("the sign-in form is visible")
5. Ask: ask("what error message is shown?")

### After code changes
1. Build: ./gradlew installDebug (or your build command)
2. Replay setup: trail(action=RUN, name="navigate-to-signin")
3. Test your change: step("enter email and tap Next")
4. Check results: verify("password screen appears")

### Recording reusable setup
First time:
1. step("launch myapp and navigate to the target screen")
2. trail(action=SAVE, name="my-setup-trail")

After that: trail(action=RUN, name="my-setup-trail") — instant, free

### On failures
- Read sessionDir from the step() response for logcat and screenshots
- step() returns appState: CRASHED when the app crashes
- Read <sessionDir>/logcat.log for crash stack traces

### Guidelines
- Talk to Trailblaze like a user: "navigate to settings" not "tap menu icon"
- One goal at a time. Read the result before deciding the next step.
- Use trail(RUN) after rebuilds to restore state (free, no AI cost)
- Never plan multiple steps ahead — always base next action on current screen
```

## Implementation Phases

### Phase 1: Enhanced step() — Goal-Level Execution (Critical)

Make `step()` accept user-level goals and execute via MultiAgentV3 when in `TRAILBLAZE_AS_AGENT` mode.

**Files:**
- `trailblaze-server/.../StepTool.kt`
- `trailblaze-host/.../TrailblazeMcpBridgeImpl.kt`

**Approach:** Build single-objective YAML from the goal, call `runYamlBlocking()` with MultiAgentV3, capture final screen as NL summary. `verify()` and `ask()` remain single-action.

### Phase 2: Enriched MCP Responses (Critical)

Add `sessionDir` and `appState` to all MCP tool responses.

**Files:**
- `trailblaze-server/.../StepTool.kt` — StepResult, VerifyResult, AskResult
- `trailblaze-server/.../TrailblazeMcpSessionContext.kt`

**Approach:** Wire `LogsRepo.getSessionDir()` through to StepTool. Check `AdbCommandUtil.isAppRunning()` on errors to determine `appState`.

### Phase 3: setAppTarget() — Dynamic App Targets (High)

External developers create app targets by package name.

**Files:**
- `trailblaze-server/.../DeviceManagerToolSet.kt`
- `trailblaze-models/.../TrailblazeHostAppTarget.kt`
- `trailblaze-host/.../TrailblazeMcpBridgeImpl.kt`

### Phase 4: YAML v2 Syntax with Trailhead (High)

> See [Decision 026](2026-03-06-trail-yaml-v2-syntax.md)

Implement the v2 YAML parser with `config`, `trailhead`, and `trail` sections. Mapping-based format, flat `recording` syntax, compact tool syntax.

### Phase 5: Recording Optimization Pipeline (Medium)

> See [Decision 034](2026-03-09-recording-optimization-pipeline.md)

Raw capture during blazing, post-processing for selectors/slots/generalization, validation loop for test authoring, best-effort caching for dev loop.

### Phase 6: Documentation (High)

Ship `.mcp.json` example and agent instructions template so developers can start immediately.

### Priority

| Phase | What | Effort | Impact |
|---|---|---|---|
| **Phase 1** | Enhanced step() with MultiAgentV3 | Medium | **Critical** — enables goal-level interaction |
| **Phase 2** | sessionDir + appState in responses | Small | **Critical** — closes the feedback loop |
| **Phase 6** | Documentation | Small | **High** — unblocks developers immediately |
| **Phase 3** | setAppTarget() dynamic creation | Small | **High** — unblocks external developers |
| **Phase 4** | YAML v2 with trailhead | Medium | **High** — enables setup checkpoints |
| **Phase 5** | Recording optimization pipeline | Large | **Medium** — improves trail stability |

Phases 1 + 2 + 6 are the MVP. Once those ship, a developer can add Trailblaze as an MCP server to their coding agent and run the full autonomous dev loop.

## Key Files Reference

| File | Purpose |
|---|---|
| `trailblaze-server/.../StepTool.kt` | MCP tools: step(), verify(), ask() |
| `trailblaze-server/.../TrailTool.kt` | Trail management: START/SAVE/RUN/LIST/END |
| `trailblaze-server/.../DeviceManagerToolSet.kt` | Device connection, app targets, runPrompt |
| `trailblaze-host/.../TrailblazeMcpBridgeImpl.kt` | Bridge: runYaml, runYamlBlocking, device selection |
| `trailblaze-models/.../TrailblazeMcpBridge.kt` | Bridge interface |
| `trailblaze-models/.../AgentImplementation.kt` | MULTI_AGENT_V3 enum |
| `trailblaze-models/.../TrailblazeMcpMode.kt` | TRAILBLAZE_AS_AGENT vs MCP_CLIENT_AS_AGENT |
| `trailblaze-models/.../TrailblazeHostAppTarget.kt` | App target abstract class |
| `trailblaze-models/.../ScreenAnalysis.kt` | ExceptionalScreenState (crash detection) |
| `trailblaze-agent/.../MultiAgentV3Runner.kt` | MultiAgentV3 implementation |
| `trailblaze-server/.../TrailblazeMcpSessionContext.kt` | Per-MCP-session state |
| `trailblaze-report/.../LogsRepo.kt` | Session log storage |
| `trailblaze-host/.../TrailblazeCli.kt` | CLI commands |
| `trailblaze-common/.../AdbCommandUtil.kt` | ADB shell commands, isAppRunning() |

## Consequences

**Positive:**
- Developers get autonomous fix-build-test cycles for mobile UI
- Setup navigation replays instantly after rebuilds (no AI cost)
- Crash detection with automatic logcat access closes the debugging loop
- Goal-level interaction protects the coding agent's context window
- Trail recordings make the dev loop faster with each iteration
- Same infrastructure supports manual recording, dev loop, and CI

**Negative:**
- Depends on MCP support in the coding agent (Claude Code, Cursor)
- MultiAgentV3 execution adds latency to the first blaze of each goal
- `TRAILBLAZE_AS_AGENT` mode changes step() behavior — existing MCP clients need awareness
- Trail recordings can break when app UI changes significantly (mitigated by NL fallback)

## Related Decisions

- [Decision 026: Trail YAML v2 Syntax](2026-03-06-trail-yaml-v2-syntax.md) — trailhead, trail, config sections
- [Decision 034: Recording Optimization Pipeline](2026-03-09-recording-optimization-pipeline.md) — post-processing, selectors, memory slots
