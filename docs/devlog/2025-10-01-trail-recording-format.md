---
title: "Trail Recording Format (YAML)"
type: decision
date: 2025-10-01
---

# Trail Recording Format (YAML)

Building on our monorepo structure, we needed a format for recording UI test interactions.

## Background

Trailblaze uses an LLM to interpret natural language test steps and execute them. We need a way to capture successful executions as **deterministic recordings** that can replay without LLM involvement, ensuring consistency and reducing costs.

## What we decided

Trail recordings use a **YAML format** (`.trail.yaml`) that captures the mapping from natural language steps to tool invocations.

### Format Structure

```yaml
- prompts:
    - step: Launch the app signed in with user@example.com
      recording:
        tools:
          - app_ios_launchAppSignedIn:
              email: user@example.com
              password: "12345678"
    - step: Add a pizza to the cart and click 'Review sale'
      recording:
        tools:
          - scrollUntilTextIsVisible:
              text: Pizza
              direction: DOWN
          - tapOnElementWithAccessibilityText:
              accessibilityText: Pizza
          - tapOnElementWithAccessibilityText:
              accessibilityText: Review sale 1 item
    - step: Verify the total is correct
      recordable: false  # Always uses AI, never replays from recording
```

### Step-Level Recordability

Each step has a `recordable` flag (default: `true`):
- **`recordable: true`**: Step can be recorded and replayed deterministically
- **`recordable: false`**: Step always requires AI interpretation, even in recorded mode

Use `recordable: false` for steps that need dynamic behavior (e.g., verification steps that should re-evaluate on each run).

> **Note:** This is separate from tool-level `isRecordable` (see [Tool Execution Modes](2026-01-01-tool-execution-modes.md)).

### Key Properties

- **Human-readable**: YAML is easy to inspect, edit, and version control
- **Deterministic**: Recordings replay exactly the same tool sequence
- **Step-aligned**: Each natural language step maps to its tool invocations
- **Platform-specific**: Trails are stored per platform/device (e.g., `ios-iphone.trail.yaml`)

### Storage Convention

Trails are organized by test case hierarchy:
```
trails/suite_{id}/section_{id}/case_{id}/
├── ios-iphone.trail.yaml
├── ios-ipad.trail.yaml
└── android-phone.trail.yaml
```

### Execution Modes

1. **AI Mode**: LLM interprets steps, executes tools, records successful runs
2. **Recorded Mode**: Replay existing `.trail.yaml` without LLM (fast, deterministic)

### Raw Maestro Blocks (Deprecated)

The trail format supports a `maestro:` block for raw Maestro commands:

```yaml
# Deprecated - avoid in new trails
- maestro:
    - tapOn:
        id: "com.example:id/button"
    - assertVisible:
        text: "Success"
```

**This is deprecated.** Prefer using Trailblaze tools instead:

```yaml
# Preferred - tools can be recorded and processed by the agent
- prompts:
    - step: Tap the submit button and verify success
      recording:
        tools:
          - tapOnElementWithText:
              text: Submit
          - assertVisible:
              text: Success
```

**Principle:** Trailblaze supports a limited subset of Maestro. Every supported Maestro command should have a corresponding Trailblaze tool that:
- Can be selected by the LLM agent
- Can be recorded in trails
- Provides a consistent abstraction across platforms

Raw `maestro:` blocks bypass the agent and recording system, making them harder to maintain and migrate.

### No Conditionals in Trail Recordings

Trail recordings intentionally contain **no conditional logic or branching**. A recording is simply a list of Trailblaze tool invocations that execute sequentially.

```yaml
# This is what a recording looks like - just tool calls, no conditionals
- prompts:
    - step: Navigate to settings
      recording:
        tools:
          - tapOnElementWithAccessibilityText:
              accessibilityText: Settings
          - waitForElementWithText:
              text: Account Settings
```

**Why no conditionals?**

1. **Simplicity**: Recordings are easy to read, review, and debug
2. **Determinism**: No runtime branching means predictable, reproducible execution
3. **Code is better for logic**: Conditional behavior belongs in custom Trailblaze tools (see [Tool Naming Convention](2026-01-14-tool-naming-convention.md) and [Custom Tool Authoring](2026-01-28-custom-tool-authoring.md))

**Where conditionals belong:**

- **Custom tools**: App-specific or platform-specific tools can contain arbitrary code, including conditionals. For example, a `myapp_ios_handleOptionalPopup` tool might check for and dismiss a popup if present.
- **Within a single natural language step**: Test authors can write conditionals in the step text for LLM interpretation (e.g., "If a popup appears, dismiss it"). However, this requires AI mode and cannot be recorded.

**What doesn't work:** Branching from one natural language step to different subsequent steps based on conditions. The step sequence in `trail.yaml` is always linear.

### Non-Goal: Code Generation

Trailblaze intentionally does **not** generate traditional test code (Playwright scripts, XCUITest, Espresso, etc.). While technically possible—recorded tool calls contain all necessary information—this is explicitly not a goal.

**Trailblaze is a runtime, not a codegen tool.**

Think of it like the difference between:
- **Java bytecode**: Runs on the JVM, not compiled to native code
- **Trail files**: Run on Trailblaze, not compiled to test scripts

The trail format is the artifact. Trailblaze interprets and executes it.

**Why not generate code?**

| Capability | Trail Runtime | Generated Code |
| :--- | :--- | :--- |
| Self-Heal | ✅ Re-derive from prompt when recording fails | ❌ Static—fails are just failures |
| Self-healing | ✅ Natural language is always available for recovery | ❌ Once generated, prompt is gone |
| Visual debugging | ✅ Desktop app replays with screenshots | ❌ Stack traces and logs only |
| Edit by non-engineers | ✅ Modify natural language steps | ❌ Must edit TypeScript/Swift/Kotlin |
| Cross-platform | ✅ One prompt, multiple recordings | ❌ Separate codegen per platform |

**Positioning clarity:**

Code generation would position Trailblaze as "yet another test recorder"—competing with Playwright Codegen, Appium Inspector, Maestro Studio, etc. These tools are mature and do codegen well.

Trailblaze's value is different: **tests defined in natural language, recorded for deterministic replay, with self-heal when recordings break**. The trail file is not an intermediate artifact to be compiled away—it's the test definition that retains its semantic meaning at runtime.

**What about exporting for debugging?**

For debugging purposes, Trailblaze could provide a "view as code" feature that shows what the equivalent Playwright/XCUITest code would look like—without actually generating runnable files. This helps developers understand what a recording does in familiar terms, while keeping the trail as the source of truth.

## What changed

**Positive:** Reproducible tests, reduced LLM costs on replay, easy debugging via readable YAML, version-controllable recordings.

**Negative:** Platform-specific recordings may diverge; recordings become stale if UI changes.
