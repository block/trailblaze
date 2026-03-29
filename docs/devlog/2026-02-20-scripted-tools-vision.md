---
title: "Scripted Tools Vision (TypeScript/QuickJS)"
type: decision
date: 2026-02-20
---

# Trailblaze Decision 025: Scripted Tools Vision (TypeScript/QuickJS)

## Context

All Trailblaze tools are currently authored in Kotlin and registered at compile time. This
works well for stable, well-defined tools, but creates friction for **conditional logic** in
tools — branching behavior based on device state, memory values, or runtime conditions.

### Current Limitation: Conditional Logic Requires Kotlin

When a tool needs to do something like "if the merchant has a subscription, take path A;
otherwise take path B," the only option today is to write a `DelegatingTrailblazeTool` in
Kotlin:

```kotlin
class EnsureMerchantReadyTool(...) : DelegatingTrailblazeTool {
  override fun toExecutableTrailblazeTools(ctx): List<ExecutableTrailblazeTool> {
    return if (ctx.trailblazeAgent.memory.variables["merchant_token"] != null) {
      listOf(LaunchAppSignedInTool(...))
    } else {
      listOf(LoadAccountTool(...), LaunchAppSignedInTool(...))
    }
  }
}
```

This requires Kotlin knowledge, a full rebuild, and a code review cycle. It is not accessible
to test engineers who primarily work in TypeScript/JavaScript.

### Trail YAML Is Static

Trail YAML files are a flat list of steps. They are expressive for sequencing but have no
conditional syntax. The LLM handles selection and orchestration at a natural-language level,
but deterministic conditional logic (things that should always behave the same way regardless
of LLM interpretation) currently cannot be expressed without Kotlin.

### `DelegatingTrailblazeTool` Is the Right Pattern

The existing `DelegatingTrailblazeTool` interface is already the correct abstraction:

```kotlin
interface DelegatingTrailblazeTool : TrailblazeTool {
  fun toExecutableTrailblazeTools(ctx: TrailblazeToolExecutionContext): List<ExecutableTrailblazeTool>
}
```

It takes an execution context (device info, memory, screen state) and returns a list of
concrete tool calls. This is a pure function: context in, tool list out. Any scripting layer
that produces the same input/output contract would integrate naturally.

## Decision

### Vision: TypeScript as a First-Class Tool Authoring Surface

We intend to allow TypeScript files to define Trailblaze tools with conditional logic, compiled
to JavaScript ahead of time and executed at runtime via an embedded JavaScript engine. This is
a **future investment** — current work remains Kotlin-based.

### Design Principles

#### 1. One-to-One: TypeScript File → Named Tool(s)

A TypeScript file is the unit of authorship for scripted tools. A single file may export one
or more tool definitions, each conforming to Trailblaze's tool naming convention (Decision 005,
e.g., `merchantFactory_*`, `myapp_*`). The tool names defined in the script become first-class
citizens in the tool registry alongside Kotlin-defined tools.

```typescript
// merchant-factory/scripts/ensure-account.ts

export const merchantFactory_ensureAccount = tool({
  name: "merchantFactory_ensureAccount",
  description: "Loads a merchant account if not already in memory, otherwise no-ops.",
  params: { key: string() },
  run(ctx, { key }) {
    if (ctx.memory.has("merchant_token")) return [];
    return [{ tool: "merchantFactory_loadAccount", params: { key } }];
  },
});
```

#### 2. Precompile Step: TypeScript → JavaScript

TypeScript source is compiled to JavaScript (`tsc` or `esbuild`) at authoring time, not at
test execution time. The resulting `.js` files are bundled alongside the trail assets. No
TypeScript toolchain is required on CI runners or Android test devices.

```
scripts/ensure-account.ts  →  (tsc compile)  →  scripts/ensure-account.js
```

#### 3. Runtime: QuickJS

JavaScript is executed at runtime using **QuickJS**, a lightweight embeddable JS engine by
Fabrice Bellard. QuickJS is suitable because:

- Tiny binary footprint (~400KB native library)
- Runs on Android (ART) — not just JVM host mode
- No external process required — runs in-process via JNI bindings
- Sandboxed by default (no file system, no network, no `require`)
- Supports ES2020 including async/await via promise resolution driven by the host

Recommended binding: **`quickjs-kt`** (Kotlin-idiomatic, coroutine-backed async, Maven
Central). Cash App's **Zipline** library uses QuickJS under the hood for dynamic code loading
on Android.

#### 4. Restricted API Surface

Scripts have access to a **limited, intentionally small API**. This is enforced by the QuickJS
host — globals not explicitly provided simply do not exist in the script environment:

```typescript
declare const trailblaze: {
  memory: {
    get(key: string): string | undefined;
    has(key: string): boolean;
    // Note: memory.set() is NOT exposed. Scripts emit tool calls; they don't
    // mutate memory directly. Tool execution mutates memory via the Kotlin path.
  };
  emit(toolName: string, params: Record<string, unknown>): void;
};
```

Scripts decide **what tools to call** (`trailblaze.emit()`). Kotlin then executes those tool
calls through the normal execution path, which handles memory writes, logging, and recording.

**HTTP is explicitly excluded** from the on-device scripting surface. API-calling tools (like
`merchantFactory_*`) must remain Kotlin-based, as they require proper network error handling,
authentication (Cloudflare tokens, etc.), and robust retry logic that is already implemented
in Kotlin. If HTTP is needed in scripts running on the JVM host (not on device), that can be
revisited separately.

#### 5. Integration as `DelegatingTrailblazeTool`

A `ScriptDelegatingTool` Kotlin wrapper executes the compiled JavaScript and collects emitted
tool calls as the delegation output:

```kotlin
@TrailblazeToolClass("script", isRecordable = false)
data class ScriptDelegatingTool(
  val scriptPath: String,
  val params: Map<String, String> = emptyMap(),
) : DelegatingTrailblazeTool {
  override fun toExecutableTrailblazeTools(
    ctx: TrailblazeToolExecutionContext,
  ): List<ExecutableTrailblazeTool> {
    val js = loadScriptAsset(scriptPath)
    val engine = TrailblazeQuickJsEngine(memory = ctx.trailblazeAgent.memory)
    return engine.evaluate(js, params).toTrailblazeTools()
  }
}
```

Because `ScriptDelegatingTool` is `isRecordable = false`, the script itself does not appear
in recordings. The **expanded tool calls it emits** are what get recorded, just like any other
delegating tool. This means:

- On **Android on-device replay**, the recording's expanded tool calls execute directly —
  QuickJS never runs during replay
- On **LLM-driven sessions** (always JVM host), the script runs and emits tool calls that are
  then captured in the recording

This resolves the Android on-device constraint: QuickJS only needs to run where the LLM runs
(JVM host), and on-device replay uses pre-recorded tool calls.

### LLM as Dynamic Orchestrator

For account selection and similar decisions, **the LLM already provides dynamic logic at no
additional cost**. A trail step like "Sign in with a US coffee shop merchant that has a Free
subscription" causes the LLM to call `merchantFactory_loadAccount(account: COFFEE_SHOP)` —
no scripting required. TypeScript scripting is intended for **deterministic conditional logic**
that must behave identically on every run regardless of LLM inference, not for decisions that
are naturally expressed in natural language.

### Current State

**This vision is not yet implemented.** All conditional tool logic remains Kotlin-based.
The merchant factory module is the intended first real-world use case once scripting is
available, but its current implementation is pure Kotlin and meets current needs.

## Consequences

**Positive:**

- Conditional tool logic becomes accessible to test engineers without Kotlin expertise
- TypeScript is familiar to the broader mobile/web engineering community at Block
- The precompile step keeps runtime simple and eliminates scripting toolchain dependencies
  from test execution environments
- Compatible with Android on-device tests via the recording model (scripts run during
  authoring, not replay)
- The QuickJS sandbox naturally limits the blast radius of poorly-authored scripts
- Tool naming conventions are enforced at the script export level, maintaining consistency

**Negative:**

- Adds a precompile step to the test authoring workflow (TypeScript → JavaScript)
- Two-language codebase for tools (Kotlin + TypeScript); contributors need to know which to use
- QuickJS adds a native library dependency to the Android APK (~400KB)
- Debugging scripted tools is harder than debugging Kotlin tools (no IDE integration,
  stack traces from QuickJS are less ergonomic)
- API-calling tools (HTTP, gRPC) must remain Kotlin — TypeScript scripts cannot reach external
  services on device

## Related Decisions

- Decision 009: Kotlin as Primary Language — TypeScript scripting is an additive layer, not a
  replacement; Kotlin remains primary for framework code and API-calling tools
- Decision 010: Custom Tool Authoring — this decision extends the future directions noted there
- Decision 023: Merchant Factory Provisioning Trails — intended first real-world application
  for scripted conditional provisioning logic
