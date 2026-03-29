---
title: "Recording Memory Template Substitution"
type: decision
date: 2026-02-20
---

# Trailblaze Decision 024: Recording Memory Template Substitution

## Context

Trailblaze recordings are a core differentiator: an LLM-driven session records the exact tool
calls it made, and subsequent runs replay those recordings deterministically **without any LLM
involvement**. See Decision 002 for the recording format.

A gap exists in how recordings capture tool parameters that originated from `AgentMemory`.

### The Problem: Literal Values in Recordings

When a trail step is driven by the LLM, `AgentMemory.interpolateVariables()` resolves template
variables _before_ the tool executes:

```
${merchant_email}  →  "trailblaze+merchant.coffee-shop.abc123@example.com"
```

The tool then executes with the resolved value. When the session is recorded, the tool is
serialized with the already-resolved literal string. The resulting recording looks like:

```yaml
- step: Launch Square app signed in as the coffee shop merchant
  recording:
    tools:
    - myapp_ios_launchAppSignedIn:
        email: trailblaze+merchant.coffee-shop.abc123@example.com  # ← literal
        password: password
```

On a future replay, if a different `account.json` has been committed (e.g., after the staging
account was regenerated), `merchantFactory_loadAccount` runs and puts a _new_ email into memory
— but the recording still hardcodes the old email. The replay will attempt to log in with a
stale address and fail.

The recording should instead capture:

```yaml
- myapp_ios_launchAppSignedIn:
    email: ${merchant_email}   # ← template variable
    password: password
```

This way, replay always uses the current session's memory value regardless of which account was
loaded.

### Current State of `TrailblazeToolLog`

The log entry that drives recording generation (`TrailblazeToolLog`) does not currently store
memory state:

```kotlin
data class TrailblazeToolLog(
  override val trailblazeTool: TrailblazeTool,  // already-interpolated parameters
  val toolName: String,
  val successful: Boolean,
  // ... no memory snapshot
)
```

`generateRecordedYaml()` receives only `List<TrailblazeLog>` and has no access to the memory
state at the time each tool executed.

## Decision

### Approach: Memory Snapshot per Tool Log + Post-Processing Reverse Substitution

Rather than changing tool execution to preserve pre-interpolation state (which would require
threading the raw template strings through the full call chain), recording generation performs
a **post-processing reverse substitution** using a memory snapshot captured at tool execution
time.

#### Step 1: Add `memorySnapshot` to `TrailblazeToolLog`

```kotlin
data class TrailblazeToolLog(
  override val trailblazeTool: TrailblazeTool,
  val toolName: String,
  val successful: Boolean,
  override val traceId: TraceId?,
  val exceptionMessage: String? = null,
  override val durationMs: Long,
  override val session: SessionId,
  override val timestamp: Instant,
  val memorySnapshot: Map<String, String>? = null,  // ← new field
)
```

When a tool executes in `MaestroTrailblazeAgent.handleExecutableTool()`, the current
`AgentMemory.variables` map is captured as an immutable snapshot and stored alongside the tool
log. The snapshot is taken _after_ the tool executes (so memory written by the tool itself is
also captured).

The field is nullable and defaults to null for backward compatibility with existing log files
and serialized sessions.

#### Step 2: Reverse Substitution in `generateRecordedYaml()`

During recording generation, for each `TrailblazeToolLog` that has a non-null `memorySnapshot`,
the tool's serialized YAML is post-processed to substitute literal values back to `${key}`:

```
"trailblaze+merchant.coffee-shop.abc123@example.com"  →  "${merchant_email}"
"ML4XV8YWNMESK"                                       →  "${merchant_token}"
```

The algorithm:
1. Serialize the tool to its YAML/JSON representation
2. For each `(key, value)` pair in the `memorySnapshot`, if `value` appears as a string
   parameter in the serialized tool, replace it with `${key}`
3. Prefer longer/more specific values first to avoid partial substring collisions

#### Mitigating False Positives

Not all parameter values that happen to match a memory value should be templated. Mitigations:

- **Minimum value length**: Only substitute values of 8+ characters. Short values like `"US"`,
  `"password"`, or `"true"` are too ambiguous.
- **Known memory-writing tools**: Give higher confidence to substitutions where the memory key
  was written by a known provisioning tool (`merchantFactory_*`, `rememberWithAi`, etc.). These
  can be substituted regardless of length.
- **Exact match only**: Only substitute exact full-field matches, not substrings within a
  longer string.

#### Why Not Pre-Interpolation Capture?

An alternative is to not call `interpolateVariables()` before recording — store the raw
template syntax and resolve it only at execution time. This is cleaner in theory but requires
significant changes to the tool execution path: the raw template form would need to be
threaded through serialization, stored separately from the runtime form, and kept in sync. The
memory snapshot + post-processing approach is additive and localized to the recording
generation layer with no changes to execution semantics.

## Consequences

**Positive:**

- Recordings using memory variables become durable across session boundaries — replaying with a
  different loaded account still works correctly
- No changes to tool execution semantics; post-processing is isolated to recording generation
- Backward compatible: existing recordings without `memorySnapshot` continue to work
- The memory snapshot also provides useful debugging context (what was in memory when a tool
  ran)

**Negative:**

- Risk of false-positive substitutions for short or coincidentally matching values (mitigated
  by length threshold and exact-match-only rule)
- `TrailblazeToolLog` grows slightly in serialized size (one map per tool log)
- The reverse substitution logic needs its own tests to verify edge cases (multiple keys with
  same value, values that are substrings of other values, etc.)

## Related Decisions

- Decision 002: Trail Recording Format (YAML) — defines the recording schema this improves
- Decision 023: Merchant Factory Provisioning Trails — the primary consumer of this improvement
  (`${merchant_email}`, `${merchant_token}` being the most common affected variables)
