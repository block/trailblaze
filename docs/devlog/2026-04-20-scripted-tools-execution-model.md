---
title: "Scripted Tools Execution Model (QuickJS + Synchronous Host Bridge)"
type: decision
date: 2026-04-20
---

# Trailblaze Decision 038: Scripted Tools Execution Model (QuickJS + Synchronous Host Bridge)

> **Amended 2026-04-20** — two amendments landed together. First, the authoring
> surface consolidated onto TS **toolset files** (inline `script: { source: "..." }`
> is deprecated). Second, the **Option-2 amendment** collapsed the former PR A3
> (in-process QuickJS toolsets) and PR A4 (subprocess MCP) into a single host-side
> PR A3: a **bun/node subprocess speaking stdio MCP**, using the real
> `@modelcontextprotocol/sdk`. The author's `.ts` file is now a standard MCP
> server, runnable under any MCP client. QuickJS moves exclusively to PR A5
> (on-device bundle). Current phases: PR A3 = subprocess MCP host runtime;
> PR A5 = on-device QuickJS bundle. See
> `docs/devlog/2026-04-20-scripted-tools-toolset-consolidation.md` for the full
> amendments and next-agent handoff plan.

## Context

Trailblaze has two active-but-incomplete threads for non-Kotlin tool authoring:

- **Decision 025** (`docs/devlog/2026-02-20-scripted-tools-vision.md`) established the high-level
  TypeScript/QuickJS vision and positioned scripts as `DelegatingTrailblazeTool` implementations
  that emit further tool calls. Its defined script API surface is strictly one-way:

  ```typescript
  trailblaze.memory.get(key)
  trailblaze.memory.has(key)
  trailblaze.emit(toolName, params)
  ```

  Scripts can read memory and emit tool calls. They cannot query live screen state, cannot wait
  for a condition, cannot try-then-fallback based on observed result. This is "static composition
  with memory-based branching," which Decision 037's `tools:` YAML mode largely already covers —
  so 025 as specified doesn't add much over what inline YAML does.

- **An earlier prototype** introduced a proto-typed **bidirectional** RPC surface.
  `commands.proto` defines `TrailblazeService.Execute` with query messages
  (`IsVisibleCommand`, `HasTextCommand`, `GetElementTextCommand`, `GetElementCountCommand`,
  `CaptureScreenCommand`) plus a typed `CommandResultData` carrying the typed return values
  (`visible: bool`, `has_text: bool`, `element_text: string`, etc.). This is the piece that
  turns "one-way emit" into "observe and react."

The two threads have never been combined. Decision 025's emit-only API is inadequate without
something like that prototype's query surface; the query surface needs a script runtime to be
useful to non-Kotlin authors.

### Who actually needs this

Two real audiences drive the priority:

1. **Downstream test authors** who want conditional logic without writing Kotlin. The motivating
   pattern is things like an API-29 stabilization loop — 60 seconds of polling the view
   hierarchy for the main screen to appear, then staying stable for N consecutive checks.
   Today that's ~80 lines of Kotlin inside one tool class. A scripted version fits in
   ~15 lines of JS if it can `execute()` assertions and branch on the result.

2. **External Trailblaze binary-release consumers** who literally cannot contribute Kotlin
   because they consume the published Trailblaze artifacts and ship their own YAML + scripts.
   For this audience, scripting isn't a convenience — it's the *only* surface for tools with
   real logic. Decision 029 anticipated custom tools; this decision makes them addable without
   a Kotlin compiler in the loop.

### Why not the MCP TypeScript SDK inside QuickJS

The `@modelcontextprotocol/sdk` npm package is designed around MCP's wire protocol
(JSON-RPC + stdio/SSE/HTTP transports). None of those transports exist in QuickJS. Making the
SDK run in-process requires a custom in-process `Transport` implementation, Node polyfills for
`process`/`Buffer`/streams, and bundling zod+deps — and after all that work, every tool call is
still a JSON-RPC round-trip between two pieces of code living in the same process. All the
overhead, none of the isolation benefit. Running the real MCP SDK inside QuickJS is an explicit
non-goal.

The agent-becomes-an-MCP-client use case that motivates some of the earlier prototype's
thinking is still valuable — but as a **separate, out-of-process** integration (host-only,
see PR C below), not as an embedded runtime.

## Decision

Trailblaze supports TypeScript/JavaScript as a first-class tool authoring surface, with scripts
running in embedded QuickJS. The scripting bridge rolls out in **four progressive PRs**, each
unlocking a specific capability. Each PR ships with its own follow-up devlog capturing the
design decisions that get finalized as the code lands.

### PR A — Bridge primitive *(this roadmap's first shippable PR)*

Scope: prove the plumbing.

- New `:trailblaze-scripting` module, depends on `:trailblaze-common`.
- `ScriptTrailblazeTool` implementing `DelegatingTrailblazeTool`. Fields: `source: String`
  (JS source) and `params: Map<String, String>`.
- `TrailblazeScriptEngine` — QuickJS host. Evaluates JS source with a simple `input` global
  containing memory + params; script returns a YAML string.
- Returned YAML decodes via existing `TrailblazeYaml.Default.decodeTools(...)`, unwrapped
  to `List<ExecutableTrailblazeTool>`.
- `@TrailblazeToolClass(name = "script", isForLlm = false, isRecordable = false)` — per
  Decision 025 §5, the expanded tool calls are what record, so Android on-device replay
  never runs JS.
- JS engine: bare **quickjs-kt** (`dokar3/quickjs-kt` 1.0.5) for minimum complexity.
  Single-artifact Maven Central dependency, Apache-2.0, wraps QuickJS directly with a
  Kotlin-idiomatic API. No Gradle plugin, no TS→JS build step, no service bridge.
  Users author `.js` directly or pre-compile `.ts` with their own toolchain. Zipline
  is the natural upgrade once TypeScript-authoring ergonomics start mattering —
  documented in Open Questions below.

What this does NOT do: no live state access, no synchronous callbacks, no TS authoring
pipeline, no Android runtime support (recording replay handles Android trivially). Static
composition with memory inputs only. Proof of plumbing.

### PR A2 — Synchronous tool execution *(the load-bearing PR)*

Scope: make scripting actually useful.

Adds a synchronous host binding:

```typescript
declare const trailblaze: {
  execute(toolName: string, params: Record<string, unknown>): TrailblazeToolResult;
  memory: { get(k: string): string | undefined; has(k: string): boolean };
};
```

`TrailblazeToolResult` mirrors the Kotlin sealed interface
(`docs/../trailblaze-models/.../TrailblazeToolResult.kt`), so scripts pattern-match the same
shape:

```typescript
const result = trailblaze.execute("assertVisibleWithText", { text: "Login" });
if (result.type === "Success") {
  trailblaze.execute("tapOnElementWithText", { text: "Login" });
} else {
  trailblaze.execute("launchApp", { appId: "com.example" });
}
```

This is the load-bearing capability because it unlocks the patterns that matter:

- Try/fallback composition.
- Polling / stabilization loops (e.g., waiting for a main screen to appear and stay stable).
- Custom assertions built from existing primitives.
- Scripted tools calling other scripted tools (composition of composition).

It also effectively **subsumes most of what a dedicated query API would provide** —
`isVisible(text)` is just `execute("assertVisibleWithText", {text})` and check `Success`.

Design concerns that land in this PR:

- **Reentrance model.** JS → Kotlin → JS (another scripted tool) → Kotlin is legitimate and
  must work. Single scripting dispatcher, bounded recursion depth (~16) to prevent runaway.
- **Timeout discipline.** Wall-clock timeout on every `execute()` call; total script budget via
  QuickJS interrupt handler; no silent hangs.
- **Error-variant semantics.** Recoverable `Error.*` variants return as JS objects so the script
  can branch. `Error.FatalError` throws on the JS side — it means "stop the world."
- **Recording.** The top-level script call AND the expanded primitive calls both record, so
  replay on a JVM that has never loaded the script file is unaffected.
- **Thread safety.** `TrailblazeToolExecutionContext` is already mutated by tool execution;
  reentering through a script call must preserve the same invariants. Explicit test.

### PR B — Typed query ergonomics *(optional polish)*

Scope: nicer API for common checks.

Adopts the earlier prototype's `commands.proto` to codegen typed bindings:

```typescript
trailblaze.isVisible(text): boolean
trailblaze.hasText(text): boolean
trailblaze.getElementText(selector): string
trailblaze.captureScreen(): ScreenState
```

**Not a new capability** — everything here is expressible via `execute()` + result inspection
already in PR A2. This layer exists because `trailblaze.isVisible("Login")` reads better than
`trailblaze.execute("assertVisibleWithText", {text: "Login"}).type === "Success"`, and because
proto-codegen gives us a single source of truth for the script-side and host-side types.

If dog-fooding shows PR A2 is already ergonomic enough in practice, PR B slips indefinitely
without loss.

### PR C — MCP subprocess client *(future, host-only, additive)*

Scope: terminal external-systems tools, reusing the MCP ecosystem.

Reuses the earlier prototype's `ExternalToolExecutor` sketch. Trailblaze spawns an MCP subprocess (any
language — TypeScript, Python, Rust), speaks MCP JSON-RPC, registers the subprocess's
advertised tools into the Trailblaze registry. Tool invocations route through the external
process; results marshal back.

Host-only by design — Android can't spawn subprocesses with network-like IPC. The clean mapping:

| MCP | Trailblaze |
|---|---|
| `{content: [{type: 'text', text}]}` | `TrailblazeToolResult.Success(message = text)` |
| protocol error / tool error | `TrailblazeToolResult.Error.ExceptionThrown` |

This is **additive**: PRs A/A2/B give you in-process composition; PR C gives you out-of-process
integration. Neither blocks the other.

### Architectural principle: two orthogonal categories

| | Scripted (PR A / A2 / B) | MCP subprocess (PR C) |
|---|---|---|
| Tool kind | Composition / orchestration (emits more tool calls) | Terminal (returns a result) |
| Process | In-process QuickJS | Separate OS process |
| Android | Yes (via recording replay) | No |
| External APIs (HTTP/DB/npm) | No (sandboxed) | Yes (anything) |
| Authoring surface | Tiny MCP-shaped shim | Full MCP SDK |

The categories are *orthogonal* — a Trailblaze deployment can have both, and they don't fight
for the same namespace. Composition tools (scripted) and terminal tools (MCP subprocess) are
solving different problems.

## Open Questions

Flagged here; each will be resolved in its respective PR's follow-up devlog.

- **When to upgrade from bare quickjs-kt to Zipline.** PR A ships bare quickjs-kt (1.0.5)
  to minimize the build-tool surface. Zipline becomes compelling once TypeScript ergonomics
  start mattering — its Gradle plugin handles esbuild bundling, tsc type checking, and typed
  `ZiplineService` interfaces between Kotlin and JS. Zipline is also QuickJS-backed, so
  sandboxing is identical; the upgrade is a clean engine swap with no change to the
  `ScriptTrailblazeTool` bridge contract. Likely inflection point: when external consumers
  or downstream authors actually start writing non-trivial TS.
- **Proto source of truth.** PR B will need `commands.proto` somewhere. Either extract into a
  new `:trailblaze-api` module (mirrors the earlier prototype) or inline into
  `:trailblaze-models`. Defer to PR B — depends on whether we also want the proto for
  JSON-wire representation of `TrailblazeToolResult` or just for codegen.
- **Script caching / hot reload.** PR A re-evaluates source on every invocation. For hot
  paths this is fine (QuickJS parse is microseconds). For production trails with large
  scripts, bytecode caching with LRU is the answer. Defer to PR A2 where perf actually
  starts to matter.
- **Script-side `emit` vs return-value.** PR A's contract is "return a YAML string." PR A2
  could also expose `emit(toolName, params)` for a more imperative feel. Decide when PR A2
  design lands — depends on whether authors find the YAML-return model awkward in practice.
- **Memory writes from scripts.** Decision 025 explicitly excluded `memory.set()`. PR A2's
  `execute()` will naturally mutate memory when the invoked tool writes it (e.g.,
  `rememberText`). Whether to *also* expose direct `memory.set()` is a separate call.
  Tentative: no, keep memory writes routed through tool calls, consistent with recording.

## Consequences

**Positive**

- Unblocks dynamic tool authoring for binary-release Trailblaze consumers (the #1
  motivating use case in this decision).
- Composition of Trailblaze tools in a language most engineers already know.
- Turns Decision 025's vision into four concrete shippable PRs, each independently
  valuable.
- Keeps MCP client integration (PR C) on the table as additive future work without
  forcing it now.
- Clean category split (composition vs terminal) means the two authoring paths don't
  compete for the same use cases.

**Negative**

- QuickJS adds a native library dependency (~400KB) to anyone depending on the new
  `:trailblaze-scripting` module. Opt-in — core modules don't change.
- Two languages in the tool-authoring codebase (Kotlin + TypeScript/JS). Contributors
  need guidance on which to reach for. Decision 025's principle already covers this:
  anything non-trivial (conditional, HTTP, memory mutation) was previously Kotlin;
  now some of those become scripted.
- Debugging scripted tools is harder than Kotlin — no IDE stepping into the JS, QuickJS
  stack traces are less ergonomic than Kotlin ones.
- Reentrance and timeout design (PR A2) is nontrivial. Expect iteration after first
  real use.
- Follow-up devlog discipline is homework. Each PR's devlog has to actually get written
  or the decision trail fragments.
- **Reputation risk**: if PR A ships and sits without PR A2 for long, external consumers
  will try it, find scripts can't do the things they need, and write it off. PR A should
  land with PR A2 ideally in the same release cycle, or with a conspicuous README note.

## Related Decisions

- **Decision 005**: Tool Naming Conventions — scripted tools follow the same
  `<namespace>_<action>` naming, validated at script registration time.
- **Decision 009**: Kotlin as Primary Language — scripting is an additive layer;
  framework code stays Kotlin. API-calling tools (HTTP, gRPC) that can't sandbox stay
  Kotlin or go to PR C (MCP subprocess).
- **Decision 010**: Custom Tool Authoring — extends the authoring surface introduced
  there.
- **Decision 025**: Scripted Tools Vision — this decision builds directly on 025 and
  supplies the synchronous callback surface 025 lacked.
- **Decision 029**: Custom Tool Architecture — scripted tools are a new backing mode
  alongside Kotlin classes and YAML-defined tools.
- **Decision 037**: YAML-Defined Tools (`tools:` mode) — static composition lives in
  YAML; dynamic composition lives in scripts. The two modes complement: if your tool
  is pure param-substitution-then-emit, use `tools:` YAML (no script runtime needed).
  If it needs any live state, use a script.

## References

- Earlier prototype — source of the proto-typed bidirectional API that PRs A2/B will
  adopt (`commands.proto`, `TrailblazeService.Execute`, query commands,
  `CommandResultData`, `ExternalToolExecutor`).
- `docs/devlog/2026-02-20-scripted-tools-vision.md` — Decision 025, which this
  decision extends.
- `docs/devlog/2026-04-20-yaml-defined-tools.md` — Decision 037, the sibling
  static-composition authoring path.
