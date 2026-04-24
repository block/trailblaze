---
title: "MCP API Redesign: verify→blaze, Mode Defaults, iOS launchApp Fix"
type: devlog
date: 2026-03-17
---

# MCP API Redesign: verify→blaze, Mode Defaults, iOS launchApp Fix

## Summary

A session of design discussion and bug fixes covering: collapsing `verify()` into `blaze(hint="VERIFY")`, removing the standalone `verify` MCP tool, making `TRAILBLAZE_AS_AGENT` vs `MCP_CLIENT_AS_AGENT` mode configurable with flavor-appropriate defaults, and fixing iOS `launchApp` failures on system apps.

---

## What Changed (Already Landed)

### Session progress UI: child tool blocks inside objectives
- **Problem:** In MCP mode, tool logs (e.g. `launchApp`) arrive *after* `ObjectiveCompleteLog` due to fire-and-forget timing. They were rendering as a separate sibling row rather than inside the objective's expanded section.
- **Fix:** `buildProgressItems` (SessionProgressHelpers.kt) now gathers `toolsBetween` before emitting the `ObjectiveItem` so a `ToolBlockItem` always follows its parent. The composable (`SessionProgressComposable.kt`) was updated to pass the child tool block into `ObjectiveStepRow` and render it inside the `AnimatedVisibility` expanded section. Sibling rendering with `padding(start=32.dp)` was removed.
- **Files:** `trailblaze-ui/src/commonMain/kotlin/xyz/block/trailblaze/ui/tabs/session/SessionProgressComposable.kt`, `SessionProgressHelpers.kt`

### iOS launchApp on system apps
- **Problem:** `launchApp(appId="com.apple.mobilecal", launchMode=REINSTALL)` always failed on iOS system apps because `clearState=true` triggers `simctl erase`/uninstall, which is prohibited for system-defined apps. The exception was thrown before the actual launch.
- **Fix:** `Orchestra.kt` now catches `clearAppState` and `setPermissions` failures individually, logs warnings, and proceeds to `maestro.launchApp()` rather than aborting. This makes `launchApp` resilient for system apps without breaking user app behavior.
- **File:** `trailblaze-android/src/main/java/xyz/block/trailblaze/android/maestro/orchestra/Orchestra.kt`

---

## What Changed (Landed in Follow-up)

### Collapse `verify()` into `blaze(hint="VERIFY")`

**Decision:** Drop the standalone `verify()` MCP tool. `toolHint` is the right abstraction for "what kind of tools should the inner agent use" — no need for a separate tool.

**Vocabulary:**
- `blaze(goal, hint="VERIFY")` → read-only assertion tools, recorded as `VerificationStep`, returns `passed: Boolean?`
- `blaze(goal)` → interactive, recorded as `DirectionStep`
- `ask(question)` → pure vision analysis, not recorded (unchanged)

**What landed in `StepToolSet.kt`:**
- `isVerify = toolHint?.uppercase()?.trim() == "VERIFY"` detected at start of `blaze()`
- `RecommendationContext.hint` set to "Verify this assertion using read-only tools only. Do not tap, swipe, or type." for verify mode
- Early returns (`objectiveAppearsAchieved`, `objectiveAppearsImpossible`) return `passed = true/false` when `isVerify`
- `promptStep` is `VerificationStep(verify = goal)` vs `DirectionStep(step = goal)` depending on `isVerify`
- `RecordedStepType.VERIFY` used for recording instead of `STEP`
- `passed: Boolean? = null` added to `StepResult`
- `VerifyResult` data class removed
- `"verify"` removed from `McpToolProfile.MINIMAL_TOOL_NAMES`
- `McpRealDeviceIntegrationTest` updated to use `blaze("...", hint="VERIFY")`

### Mode default configurable per build flavor

**Decision:** `TRAILBLAZE_AS_AGENT` stays the internal default; OSS CLI gets `MCP_CLIENT_AS_AGENT`.

**What landed:**
- `var defaultMode: TrailblazeMcpMode = TrailblazeMcpMode.TRAILBLAZE_AS_AGENT` added to `TrailblazeMcpServer` alongside `defaultToolProfile`
- Both session creation sites in `TrailblazeMcpServer` now pass `mode = defaultMode`
- `TrailblazeCli.kt` sets `app.trailblazeMcpServer.defaultMode = TrailblazeMcpMode.MCP_CLIENT_AS_AGENT` for both HTTP and direct STDIO transports

---

## Design Context (for future reference)

### Two-tier tool architecture
- **Session level:** `toolProfile=MINIMAL` sets the baseline for the whole session (what the outer MCP client sees)
- **Call level:** `toolHint` on `blaze()` overrides for a single call (what Trailblaze's inner agent can use)
- `VERIFY` hint = OBSERVATION + VERIFICATION tools (read-only, no tap/swipe/type)
- `NAVIGATION` hint = MINIMAL + launchApp/openUrl/scroll

### `ask()` stays as a distinct tool
Kept separate because it has no device interaction at all — pure vision analysis of a screenshot. Returns information, not pass/fail. `blaze(toolHint=VERIFY)` can use tools and returns pass/fail. The naming asymmetry (`blaze` = imperative, `ask` = interrogative) is intentional and reflects the different character of the operation.

### MCP session timeline view (future work, not started)
A `TRAILBLAZE_AS_AGENT` MCP session currently re-uses the objective/trail timeline view, which feels forced for interactive blaze sessions. Discussed:
- Each `blaze()` call → renders like an objective row (goal + tools + screenshots)
- `verify` calls (now blaze+hint) → lighter assertion row
- `ask()` calls → minimal annotation or hidden
- "Save as trail step" affordance on blaze rows
- This requires detecting session type from logs (`McpAgentRunLog` presence) and rendering differently from trail sessions. Not yet started.

### `TrailblazeMcpMode` context
- `MCP_CLIENT_AS_AGENT`: Client is the agent, Trailblaze exposes primitives. Recommended for OSS.
- `TRAILBLAZE_AS_AGENT`: Trailblaze is the agent, client sends goals via `blaze`/`ask`. Current active usage with `MINIMAL` toolset start arg.
- Mode is already configurable at runtime via `config(action=SET, key="mode", value=...)`. The missing piece is just the default.
