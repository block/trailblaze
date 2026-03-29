---
title: "AI Fallback"
type: decision
date: 2026-01-29
---

# Trailblaze Decision 021: AI Fallback

## Context

A core value proposition of Trailblaze is that **natural language is always the source of truth** for test definitions. As described in [Decision 002](../../devlog/2025-10-01-trail-recording-format.md), trail recordings (`.trail.yaml` files) are an *optimization*—they capture successful executions as deterministic tool sequences that can replay without LLM involvement, reducing costs and ensuring consistency.

However, recordings are inherently tied to the application state at the time they were captured. When the application changes—a new onboarding popup appears, button text is updated, a feature flag changes the UI flow—recorded tool calls may fail. Rather than treating this as an immediate test failure, Trailblaze can leverage the natural language source of truth to attempt recovery.

This is **AI Fallback**: when recorded steps fail, Trailblaze falls back to AI interpretation of the natural language steps, allowing tests to navigate through UI inconsistencies and complete successfully.

## Decision

**Trailblaze implements AI Fallback as a configurable execution feature that re-interprets natural language steps when recorded tool calls fail, distinguishing these recoveries with a specific test result status.**

### Natural Language as Source of Truth

Every Trailblaze test is defined by natural language steps:

```yaml
- prompts:
    - step: Launch the app and sign in with user@example.com
    - step: Navigate to Settings
    - step: Verify the account email is displayed
```

These steps represent the *intent* of the test. A recording captures *one way* to accomplish that intent:

```yaml
- prompts:
    - step: Navigate to Settings
      recording:
        tools:
          - tapOnElementWithAccessibilityText:
              accessibilityText: Settings
          - waitForElementWithText:
              text: Account Settings
```

When the recording fails (e.g., the "Settings" button was renamed to "Preferences"), the natural language step "Navigate to Settings" still clearly describes what should happen. AI Fallback uses this to recover.

### How AI Fallback Works

1. **Recorded execution begins**: Trailblaze executes the recorded tool calls for each step
2. **Tool call fails**: A tool call returns an error (element not found, assertion failed, timeout, etc.)
3. **Fallback triggered**: Instead of failing immediately, Trailblaze switches to AI mode for the current step
4. **LLM interprets step**: The natural language step is sent to the LLM, which analyzes the current screen state and determines the appropriate actions
5. **Execution continues**: If the LLM successfully completes the step, execution proceeds to the next step (which may continue in recorded or fallback mode depending on configuration)
6. **Result marked**: The test result is marked with a distinct status indicating AI Fallback was used

### Configuration Options

AI Fallback can be enabled or disabled based on execution context:

| Configuration | Behavior |
| :--- | :--- |
| `aiFallback: enabled` | When recorded steps fail, fall back to AI interpretation |
| `aiFallback: disabled` | Recorded step failures immediately fail the test |

**When to enable fallback:**

- CI pipelines where test stability is prioritized over strict determinism
- Tests running against frequently-changing areas of the application
- Environments where minor UI inconsistencies are expected (e.g., feature flags, A/B tests)

**When to disable fallback:**

- Recording new trails (fallback would mask recording issues)
- Validating that recordings are up-to-date
- Performance-critical pipelines where LLM latency is unacceptable
- Debugging specific recording failures

### Test Result Statuses

AI Fallback introduces a distinct test result status to provide visibility into how tests succeeded:

| Status | Description |
| :--- | :--- |
| `PASSED` | Test succeeded using recordings only (no AI involvement) |
| `PASSED_WITH_AI_FALLBACK` | Test succeeded, but one or more steps required AI fallback |
| `PASSED_AI_MODE` | Test ran entirely in AI mode (no recording or recording intentionally skipped) |
| `FAILED` | Test failed (even after AI fallback attempts, if enabled) |

The `PASSED_WITH_AI_FALLBACK` status is critical for several reasons:

1. **Recording staleness detection**: A high rate of fallback-assisted passes indicates recordings need updating
2. **Pipeline health monitoring**: Teams can track fallback usage over time and set thresholds
3. **Debugging context**: When investigating test behavior, knowing fallback was used helps explain differences from expected execution
4. **Cost awareness**: AI fallback incurs LLM costs; tracking helps with budget planning

### Interaction with Step-Level Recordability

As noted in [Decision 002](../../devlog/2025-10-01-trail-recording-format.md), individual steps can be marked `recordable: false` to always use AI interpretation:

```yaml
- step: Verify the total matches the expected value
  recordable: false  # Always uses AI
```

AI Fallback is different—it applies to steps that *have* recordings but whose recordings fail at runtime. The two features are complementary:

- **`recordable: false`**: Intentionally always use AI (design decision)
- **AI Fallback**: Gracefully recover when recordings unexpectedly fail (resilience mechanism)

### Fallback Scope and Continuation

When AI Fallback is triggered for a step:

1. **Step scope**: The LLM re-interprets only the failing step, not the entire test
2. **Screen context**: The LLM receives the current screen state (screenshot, view hierarchy)
3. **Continuation**: After successful fallback, the next step attempts recorded execution first (if available)
4. **Cascading fallback**: If subsequent recorded steps also fail, fallback is triggered for each independently

This step-by-step approach minimizes LLM usage while maximizing recovery opportunities.

### Example Scenario

Consider a test with this step:

```yaml
- step: Dismiss any promotional popups and navigate to the main screen
  recording:
    tools:
      - waitForElementWithText:
          text: Welcome to MyApp
      - tapOnElementWithAccessibilityText:
          accessibilityText: Home
```

**Without AI Fallback:** If a new "What's New" popup appears before the Welcome screen, the `waitForElementWithText` call fails, and the test fails immediately.

**With AI Fallback:** The tool call fails, fallback is triggered, the LLM sees the "What's New" popup, dismisses it, then proceeds to navigate to the main screen. The test passes with `PASSED_WITH_AI_FALLBACK` status.

## Consequences

**Positive:**

- Tests are more resilient to minor UI changes, reducing flakiness
- Natural language remains the authoritative test definition, with recordings as an optimization
- Clear visibility into fallback usage enables informed decisions about recording maintenance
- Teams can balance determinism and resilience based on their specific needs
- Recordings can remain valid longer, reducing maintenance burden

**Negative:**

- AI Fallback incurs LLM costs when triggered
- Fallback-assisted passes may mask recordings that need updating if not monitored
- Execution time increases when fallback is triggered (LLM latency)
- Test behavior may vary slightly between recorded and fallback execution paths
- Requires monitoring and alerting on fallback rates to maintain recording health
