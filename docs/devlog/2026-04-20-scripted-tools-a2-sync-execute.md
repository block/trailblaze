---
title: "Scripted Tools PR A2 — Synchronous Tool Execution from JS"
type: decision
date: 2026-04-20
---

# Trailblaze Decision 038 PR A2: Synchronous Tool Execution from JS

Follow-up to Decision 038 PR A (the QuickJS bridge primitive). This devlog captures
the design decisions that land with PR A2, which ships exactly one capability: the
`trailblaze.execute()` callback surface that lets scripts call other Trailblaze tools.

## Scope

PR A2 turns scripted tools from "static composition" into "observe and react" by
adding a synchronous host binding that reenters Trailblaze's tool dispatcher from JS.

This is the single load-bearing capability — every downstream scripted-tools path
(PR A3 subprocess MCP, PR A4 on-device bundles, the inline `script:` tool PR A
ships) depends on it. Everything else we discussed as "nice-to-have" (memory.set,
keys/entries listing, device namespace, typed memory values) is explicitly out of
scope for this PR and will land later if real use cases demand it.

### In scope

- **`trailblaze.execute(toolName, params): TrailblazeToolResult`** — synchronous
  host binding exposed to JS. Dispatches into the Kotlin tool registry, runs the
  named tool, returns a result object the script can pattern-match on.
- **JS-side result mirror** — minimum viable: `{ type: "Success" | "Error", message?: string }`.
  Richer variant detail (MissingRequiredArgs, FatalError, etc.) is a later
  enhancement via `_meta.trailblaze`; see the conventions devlog.
- **Reentrance model** — JS → Kotlin → JS (a scripted tool calling another scripted
  tool) → Kotlin is legitimate. Single dispatcher per session; recursion depth
  bounded (~16) to catch typos.
- **Recording discipline** — consistent with Decision 025 and PR A, the `script:`
  delegating tool remains `isRecordable = false` (the wrapper is not replayed). Each
  primitive the script invokes via `trailblaze.execute()` records as its own entry
  in the replayable trail, same as if the agent had dispatched it directly. Replay
  on a JVM that has never loaded the script reruns that recorded primitive
  sequence; it does not re-evaluate JS.
- **Thread-safety invariants** — `TrailblazeToolExecutionContext` mutations hold
  across reentrance. Explicit test.

### Explicitly out of scope (moved to later PRs)

- `trailblaze.memory.set()` — deferred; needs a recordable setter primitive to
  preserve replay semantics. Revisit when a real use case lands.
- `trailblaze.memory.keys()` / `entries()` listing — deferred; cheap add when needed.
- `input.device.*` namespace — deferred; symmetric with YAML `{{device.x}}` when
  that lands.
- Typed memory values (lists/objects beyond strings) — deferred; bigger change
  touching storage and YAML interpolation semantics.
- **Timeout / interrupt discipline** — Decision 038's execution-model devlog
  (`2026-04-20-scripted-tools-execution-model.md`) lists per-`execute()` wall-clock
  timeouts + total budget via QuickJS interrupt handler as concerns that land with
  PR A2. Read that as the **target** model: `quickjs-kt` 1.0.5 exposes no interrupt
  handler, so a `while(true){}` in JS never yields and `withTimeout` cannot fire.
  Real wall-clock budget ships in a follow-up gated on either a `quickjs-kt`
  upgrade or the Zipline migration. PR A2 itself does not enforce timeouts.

## Design decisions

### Synchronous, not suspending

`trailblaze.execute()` exposes a synchronous signature to JS. Under the hood, the
Kotlin dispatcher uses `runBlocking` to call the tool's suspending `execute()`.
This is the only shape that works without threading Promise/await through QuickJS,
and it matches Decision 025's original design. Each `trailblaze.execute()` call is
its own blocking dispatch.

**Threading caveat.** `runBlocking` inside an already-suspending tool dispatch
can starve a shared coroutine pool if the evaluation thread is owned by a
dispatcher with limited parallelism. Today, `TrailblazeScriptEngine` uses
`Dispatchers.Default` (multi-threaded work-stealing), which avoids the
single-threaded-deadlock case. If we later move the engine to a
limited-parallelism or single-threaded dispatcher (e.g. for deterministic ordering
across concurrent sessions), the dispatcher must run on a dedicated executor or
use `Dispatchers.IO` so reentrant `runBlocking` can't deadlock. Worth explicit
thought any time the engine's threading changes.

### Errors as objects, not exceptions (at the JS boundary)

Recoverable errors return to JS as plain objects with `{ type: "Error", message }`.
Scripts branch on `result.type` without try/catch noise. Only `FatalError` throws
on the JS side — matching the Kotlin contract where fatal errors unwind the whole
session.

### Recording: only expanded primitives record

The `script:` wrapper does NOT appear in the replayable trail recording — it's
`isRecordable = false`, as PR A established. Each `trailblaze.execute()` call the
dispatcher routes through records as its own primitive entry (the dispatcher
invokes the same `logToolExecution` helper the agents use for normal dispatch).
Replay replays those primitives directly; no JS runs at replay time. This is PR
A's discipline extended to reentrant calls, not a new recording model.

### Reentrance depth cap

Bounded at ~16 frames. Five-deep composition is fine; an infinite-recursion typo
gets caught with a clear exception rather than a stack overflow. The exact number
is arbitrary — the point is fail-fast with a useful message.

## Why this is the load-bearing PR for everything after

The execute-callback is the one piece every downstream authoring path needs:

- **PR A3** (subprocess MCP toolsets) — author's Node-side tool handlers call
  `trailblaze.execute()` to invoke primitives.
- **PR A4** (on-device MCP toolsets in QuickJS) — same call, same binding, just
  a different evaluation context.
- **Inline `script:` tool** (PR A's surface) — gets `trailblaze.execute()` for
  free, immediately useful for polling loops and try/fallback patterns.

Shipping this primitive in isolation keeps PR A2 reviewable and unblocks all
three paths simultaneously.

## References

- `docs/devlog/2026-04-20-scripted-tools-execution-model.md` — Decision 038, the
  four-PR roadmap this PR implements phase two of.
- `docs/devlog/2026-04-20-scripted-tools-mcp-conventions.md` — the MCP-extension
  conventions PRs A3/A4 use. PR A2's JS result mirror aligns with the minimum
  (`isError`-only) shape documented there.
- `docs/devlog/2026-04-20-scripted-tools-mcp-subprocess.md` — PR A3, which
  consumes `trailblaze.execute()` from Node-side SDK handlers.
- `docs/devlog/2026-04-20-scripted-tools-on-device-bundle.md` — PR A4, which
  consumes `trailblaze.execute()` from QuickJS-evaluated bundles.
