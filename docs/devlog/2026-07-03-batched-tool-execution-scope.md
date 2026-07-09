---
title: "Batched tool-execution scope: one context + one snapshot frame per recording"
type: decision
date: 2026-07-03
---

# Batched tool-execution scope: one context + one snapshot frame per recording

## Summary

Trailblaze dispatches a group of tools three different ways, and two of them build a **fresh
execution context per tool**. That fresh context silently drops cross-tool device state that lives
in the context (the clipboard round-trip is the proof case), and it re-frames the `SnapshotCache`
and re-allocates the per-context device executor N times for an N-tool recording.

This entry proposes a **batched execution scope**: a group of tool dispatches (a recording's tool
list today; a scripted tool's nested calls later) shares **one** execution context, **one**
`SnapshotCache` frame, and **one** `ToolExecutionContextThreadLocal` install — while keeping the
per-tool loop (and therefore per-tool failure attribution, self-heal handoff, capture hooks,
cancellation checks, and per-tool logs) exactly where it is. The recommended shape makes recorded
replay behave like the already-proven legacy `- tools:` batch path, from which the clipboard trail
is currently exiled.

Acceptance test: convert `trails/eval/android/sample-app/clipboard-round-trip.trail.yaml` from the
legacy `- tools:` form back to the unified `trail:`/`step:`+`recording:` form and prove it replays
green on-device with pure `--use-recorded-steps` (no self-heal).

## Background: three dispatch paths, two of them per-tool

| Path | Site | Batching |
|------|------|----------|
| Legacy `- tools:` (`ToolTrailItem`) | `AndroidTrailblazeRule` → `runTrailblazeTool(item.tools.map { it.trailblazeTool })` → `BaseTrailblazeAgent.runTrailblazeTools(tools = [all])` | **whole list in ONE call** — one context, one frame, one install |
| Recorded replay | `TrailblazeRunnerUtil.runRecordedTools` loops `runTrailblazeTool(listOf(oneTool))` | **one tool per call** — fresh context + fresh frame each |
| Nested scripted-tool (`ctx.tools.X()`) | `SessionScopedHostBinding.executeResolved` → `TrailblazeToolExecutionContext.nestedToolExecutor` → `runTrailblazeTools(tools = listOf(nestedTool))` | **one tool per call** — fresh context + fresh frame each |

`BaseTrailblazeAgent.runTrailblazeTools` already does the right thing for a *list*: it builds one
context, `SnapshotCache.pushFrame()`s once, installs the context ThreadLocal once, loops the tools
(logging + invalidating per tool), early-exits on the first failure, and pops/clears in a `finally`.
The problem is purely that recorded replay and nested-exec call it **once per tool** instead of
once per group.

## Root cause of the clipboard failure (concrete, not hand-wavy)

`clipboard-round-trip.trail.yaml` is deliberately pinned to legacy `- tools:` because converting it
to the unified form routes it through per-tool recorded replay, and then `mobile_pasteClipboard`
reports **"device clipboard is empty."** The mechanism:

1. `mobile_setClipboard` → `SetClipboardTrailblazeTool` → `AndroidDeviceCommandExecutor.setClipboard(text)`.
   On-device (Android 10+) `ClipboardManager.getPrimaryClip` is restricted to the *focused* app, and
   instrumentation runs in its own process — so the executor **cannot read the system clipboard
   back**. It instead caches the value in a `@Volatile private var lastSetClipboard` **on the
   executor instance**, and `getClipboard()` returns that cache.
2. `mobile_pasteClipboard` → `PasteClipboardTrailblazeTool` → `executor.getClipboard()` → reads
   `lastSetClipboard`.
3. `MaestroTrailblazeAgent.buildExecutionContext` constructs a **new** `AndroidDeviceCommandExecutor`
   **every call**, and `buildExecutionContext` runs **once per `runTrailblazeTools` call**.

So under the **legacy batch**: one `runTrailblazeTools` → one executor instance → `setClipboard`
writes `lastSetClipboard` and `pasteClipboard` reads it back on the *same* instance. ✅

Under **per-tool replay**: `setClipboard` runs on executor **A**, `pasteClipboard` on a fresh
executor **B** whose `lastSetClipboard` is `null` → "device clipboard is empty." ❌

The failure is **not** about UI settle or focus timing (settle lives in the driver gesture and each
tool still round-trips through it). It is about **per-context in-process state not surviving the
fresh context**. Clipboard is the canary; any current or future context-scoped device cache has the
same latent bug under per-tool replay. (iOS does not hit this — `mobile_setClipboard` writes the
real simulator pasteboard via `xcrun simctl`, which is process-independent — which is why the iOS
clipboard trail could unify without a framework change. Android's cross-process restriction is what
forces the in-process cache.)

## Goal & constraints the design MUST preserve

A batched mode must let a *group* of tools share one context + one frame **without losing**:

- **Per-tool failure attribution** — `runRecordedTools` returns
  `PromptRecordingResult.Failure(successfulTools, failedTool, failureResult)`; self-heal
  (`runRecordedPrompt` → `trailblazeRunner.recover`) and `logSelfHealInvoked` consume the failed
  tool + successful prefix as `TrailblazeToolYamlWrapper`s.
- **Self-heal handoff** — the `selfHeal` recovery path in `runRecordedPrompt`.
- **Pre/post capture hooks** — `onBeforeRecordedTool` / `onAfterRecordedTool` (Maestro→accessibility
  migration secondary-tree capture), fired per tool.
- **Logging granularity** — decoupled from context/frame lifetime; still one `TrailblazeToolLog`
  per tool, not one per batch.
- **`SnapshotCache` read-after-write** — action tools invalidate the frame on success so a following
  query re-captures; this must still hold *within* the shared frame.
- **Cancellation** — the per-tool `currentCoroutineContext().ensureActive()` that aborts long
  recordings promptly.

## Options

### Option A — one batched `runTrailblazeTools(wholeList)` per recording

`runRecordedTools` hands the entire recorded list to a single `runTrailblazeTools` call and
reconstructs per-tool results from `RunTrailblazeToolsResult(executedTools, result)`.

- ✅ One context (clipboard fixed), one frame, one screen-state capture, N per-tool logs (the base
  loop already logs each).
- ❌ **Failure attribution is fragile.** `executedTools` is a flat `List<TrailblazeTool>`; a
  `DelegatingTrailblazeTool` expands to several executed sub-tools and `MemoryTrailblazeTool` is
  handled specially, so the index no longer maps 1:1 back to the recorded
  `TrailblazeToolYamlWrapper` list. Rebuilding `Failure(successfulTools, failedTool)` as YAML
  wrappers means re-deriving a mapping the loop currently knows for free.
- ❌ **Hooks + `ensureActive` would have to move into the base loop.** The capture hooks are
  migration-specific and `suspend`; pushing them into `BaseTrailblazeAgent` couples generic dispatch
  to recording/migration concepts.

Net: the dispatch shape is right, but it forces recording concerns down into the base loop and makes
attribution lossy. Rejected.

### Option B — shared batch scope (RECOMMENDED)

Keep the per-tool loop exactly where it is; hoist only the **context + frame + install** up to a
scope that spans the whole group. A lazily-built, thread-scoped `ToolBatchScope` (a sibling of the
existing `SnapshotCache` / `ToolExecutionContextThreadLocal` thread-scoped primitives) represents
"these dispatches share one context + one frame."

- `BaseTrailblazeAgent.runInSharedToolBatch(block)` — `enter()` the scope, run `block`, `exit()`
  in `finally` (pop the frame + clear the ThreadLocal if this scope established them).
- `BaseTrailblazeAgent.runTrailblazeTools(...)` gains a top branch: **if a `ToolBatchScope` is
  active on this thread, reuse its context + frame** (build/install/push lazily on the first tool,
  torn down by `exit()`) instead of building its own. The per-tool dispatch loop, invalidation, and
  `RunTrailblazeToolsResult` construction are refactored into a private helper shared by both
  branches — no behavior change for the top-level (`- tools:`) path.
- `TrailblazeRunnerUtil` gains an optional `sharedToolBatch` bracket (wired to
  `agent::runInSharedToolBatch`). `runRecordedTools` wraps its **unchanged** per-tool loop in it, so
  every `runTrailblazeTool(listOf(tool))` inside the loop reuses the shared context + frame. When the
  bracket is not wired (`null`), behavior is byte-for-byte today's per-tool path.

Why this satisfies every constraint:

| Constraint | How it survives |
|-----------|-----------------|
| Per-tool failure attribution | Loop stays in `runRecordedTools`; `Failure(successfulTools, failedTool, failureResult)` built exactly as today |
| Self-heal handoff | `runRecordedPrompt` untouched |
| Capture hooks | `onBefore`/`onAfter` still fire per tool inside the loop |
| Logging granularity | Each per-tool `runTrailblazeTools` still logs its tool via `executeTool` → `logToolExecution` → N logs |
| SnapshotCache read-after-write | Shared frame; invalidate-on-success still runs per tool against that frame |
| Cancellation | `ensureActive()` still runs per tool |
| Cross-tool device state | Shared context → shared `AndroidDeviceCommandExecutor` → `lastSetClipboard` survives ✅ |

Crucially, recorded replay then behaves **at least as fresh as the legacy `- tools:` batch** — the
known-good baseline the clipboard trail already relies on — while going one step further: the legacy
batch freezes one `context.screenState` for its whole tool list (tools that need live state
re-capture through `screenStateProvider` / the driver — verified for `tapOnElementBySelector` via
`executeNodeSelectorTap` and for `assertVisibleBySelector` via its own fresh
`screenStateProvider?.invoke()` poll), whereas recorded replay's shared context still gets a fresh
`screenState` reassigned before every dispatch (see the mutable-field note below), so tools that read
`context.screenState` directly are unaffected either way. One executor, one frame — per-tool screen
capture is unchanged from pre-batch behavior.

Recorded replay is single-threaded (the loop runs under one `runBlocking`; each per-tool dispatch
completes before the next), so the thread-scoped `ToolBatchScope` is safe here with no
dispatcher-hop hazard.

### Option C — fix state locality only (move the clipboard cache off the context)

Move `lastSetClipboard` from the per-context executor to somewhere batch-stable (the agent, or
`AgentMemory`).

- ✅ Smallest change; fixes clipboard.
- ❌ Only fixes **clipboard**. Any other context-scoped device cache keeps the latent bug, and it
  does nothing for the perf/observability cost (N contexts, N frames, N executor allocations). It
  treats the symptom, not the class. The task explicitly asks for a batched mode, not a clipboard
  patch. Rejected as the primary fix (though "make context-scoped caches an explicit, documented
  hazard" is a worthwhile companion note).

## Recommended approach

**Option B, scoped to recorded replay for the first change.** It is the least invasive design that
fixes the whole *class* of cross-tool-state bugs, collapses the per-recording context/frame churn,
preserves every attribution/hook/logging/cancellation contract, and — because it reduces recorded
replay to the proven legacy-batch behavior — is low-risk to reason about.

### Mechanism sketch

```
// :trailblaze-common — new thread-scoped primitive, sibling to SnapshotCache
object ToolBatchScope {
  fun isActive(): Boolean
  fun enter()                       // mark active; context/frame built lazily on first dispatch
  fun exit()                        // pop frame + clear ThreadLocal if established; clear marker
  // internal: contextOrBuild { ... }, records install/push so exit() can undo exactly once
}

// BaseTrailblazeAgent
suspend fun <R> runInSharedToolBatch(block: suspend () -> R): R {
  if (batchedExecutionDisabled()) return block()          // kill-switch: pass-through
  ToolBatchScope.enter(); try { return block() } finally { ToolBatchScope.exit() }
}

override fun runTrailblazeTools(...): RunTrailblazeToolsResult =
  if (ToolBatchScope.isActive())
    dispatchTools(ToolBatchScope.contextOrBuild { buildExecutionContext(...) }, tools, elementComparator)
  else { /* today: build context, install, pushFrame, try { dispatchTools(...) } finally { pop; clear } */ }

// TrailblazeRunnerUtil (new optional ctor param `sharedToolBatch`, wired to agent::runInSharedToolBatch)
private suspend fun runRecordedTools(tools) =
  withToolBatch { /* the EXISTING per-tool loop, verbatim */ }
```

`AndroidTrailblazeRule` wires `sharedToolBatch = trailblazeAgent::runInSharedToolBatch`. Screen-state
capture stays per-tool (`screenStateProvider()` still fires before every dispatch) — only the
context/executor *identity* is shared. `TrailblazeToolExecutionContext.screenState` is reassigned
onto the shared context before each dispatch (mutable field, see its kdoc), so tools that read
`context.screenState` directly rather than re-capturing (e.g. `TapOnTrailblazeTool`,
`ClearTextTrailblazeTool`) still see current UI instead of the batch's first-tool snapshot — a
review round on this PR (Copilot/Codex) caught an earlier draft that skipped the redundant capture
and froze `screenState` at the first tool, which would have silently broken any such tool later in a
recording. The perf win is therefore the shared context/executor + one `SnapshotCache` frame + one
ThreadLocal install per recording, not fewer screen captures.

### Blast-radius mitigation

- **Kill-switch** `TRAILBLAZE_DISABLE_BATCHED_TOOL_EXECUTION=1` (read per-call so it flips on a
  running daemon, matching the repo's env-var conventions) turns `runInSharedToolBatch` into a
  pass-through — a one-line revert to per-tool contexts if a downstream pipeline regresses.
- **Opt-in wiring.** Only rules that pass `sharedToolBatch` get batching. Wire `AndroidTrailblazeRule`
  first (the acceptance-test path); the block Android rule and host rules can adopt the identical
  one-line wiring afterward. Unwired callers keep exact current behavior.
- **No public-API-surface change on `:trailblaze-models`.** `runInSharedToolBatch` is a concrete
  method on `BaseTrailblazeAgent`; the runner receives a bracket lambda, so the `TrailblazeAgent`
  interface and the models API baseline are untouched (no `apiDump`).
- **Compose/Playwright agents unaffected.** `ComposeRpcTrailblazeAgent` overrides
  `runTrailblazeTools` to add a post-batch screenshot around `super`; under Option B its per-tool
  reuse still routes through that override, so its screenshot-per-tool behavior is unchanged.

### Deliberately out of scope for the first change (follow-up)

- **Nested scripted-tool composition.** A scripted tool dispatched *inside* a scope has its nested
  `runTrailblazeTools(listOf(nestedTool))` calls reuse the scope **only when they run on the scope's
  thread**. QuickJS's `SessionScopedHostBinding` callback can resume on a different thread (the
  documented `ToolExecutionContextThreadLocal` dispatcher-hop hazard), so thread-local scope reuse is
  not guaranteed there. The robust nested-exec fix is to make the `nestedToolExecutor` closures
  dispatch against the **closure-captured** outer context (which they already hold) rather than
  rebuilding — thread-hop-safe, and a natural second PR. Kept separate so the acceptance test stays
  focused and the load-bearing change stays reviewable.

## Test plan

- **Acceptance (on-device) — DONE.** Converted `clipboard-round-trip.trail.yaml` to unified
  `trail:`/`step:`+`recording:` form (NL prompt per step, `recording:` carries the tools; preserved
  `driver: ANDROID_ONDEVICE_INSTRUMENTATION`) and removed the "kept in legacy form" header comment.
  Ran `clipboardRoundTrip` via the `GeneratedSampleAppTests` instrumentation harness on a real
  Android emulator with pure recorded replay (`-Ptrailblaze.aiEnabled=false`, no self-heal) — two
  consecutive clean passes (`tests="1" failures="0" errors="0"`). Logcat confirms the actual
  cross-tool state fix: `mobile_pasteClipboard` reads the value `mobile_setClipboard` wrote two
  tools earlier in the same step's recording, with no "device clipboard is empty" error. (A
  `TrailblazeNode selector found no match` flake on the unrelated "Submit" tap step showed up on a
  heavily-reused emulator; A/B-tested against the unmodified legacy `- tools:` form on the same
  device/session, which failed identically — confirming pre-existing local environment state, not
  a regression, and it cleared after a clean emulator reboot.)
- **Unit (observable contracts, per CLAUDE.md testing philosophy).** Extend `TrailblazeRunnerUtilTest`:
  with a `sharedToolBatch` wired, a set→read pair dispatched through the same batch observes the
  written value on read; failure attribution, self-heal handoff, hook firing, and per-tool cancellation
  still hold. Assert the *contract* (shared-context read-after-write; the `Failure`
  shape; that the bracket is entered/exited around the loop), **not** internal frame counts or
  install call counts.
- **No regression** on the legacy `- tools:` path (unchanged top-level branch) and on `BaseTrailblazeAgentTest`.

## Open questions

1. ~~Wire `sharedToolBatch` for the host + block Android rules in this PR, or as immediate
   follow-ups?~~ Resolved below — dedicated follow-up PRs, as leaned: the block Android rule
   (#4517) and the opensource host runners (#4518).
2. ~~Fold the nested-exec closure-captured-context fix into this PR or a dedicated second PR?~~
   Resolved below — dedicated second PR, as leaned.

## Follow-up: nested scripted-tool composition (closure-captured context)

Implements the fix sketched in "Deliberately out of scope" above. A scripted tool that composes
more than one framework tool call in its own `execute()` — e.g. `ctx.tools.setClipboard(...)` then
`ctx.tools.pasteClipboard(...)` inside one bundle — hits the identical bug class as the recorded-
replay case, through a different call path:

- `SessionScopedHostBinding.executeResolved` (QuickJS in-process dispatch) and
  `JsScriptingCallbackDispatcher.dispatchCallTool` (subprocess/HTTP transport) both consult
  `TrailblazeToolExecutionContext.nestedToolExecutor` for each nested call.
- `MaestroTrailblazeAgent.buildExecutionContext` (and the identically-shaped
  `PlaywrightTrailblazeAgent.buildExecutionContext`) wired that closure to re-enter
  `runTrailblazeTools(tools = listOf(nestedTool), ...)`. Absent an active `ToolBatchScope` (which
  only wraps recorded replay), that re-entry falls into the *self-owned-context* branch of
  `runTrailblazeTools` and calls `buildExecutionContext(...)` again — building a **new**
  `TrailblazeToolExecutionContext` (and therefore a new `AndroidDeviceCommandExecutor`) for every
  nested call in the same scripted-tool body.
- `ToolBatchScope` (the #4506 fix) can't paper over this: it's a thread-scoped primitive, and
  QuickJS's async binding callback can resume on a different coroutine-dispatcher thread than the
  one that entered the scope (see `ToolExecutionContextThreadLocal`'s kdoc on the same hazard). A
  scope opened on thread A could have its teardown silently skipped if a later nested call resumes
  on thread B — exactly the risk this repo's task brief for the fix called out.

**Fix.** `BaseTrailblazeAgent.dispatchTools` — the private sequential-loop helper `runTrailblazeTools`
already factors its two branches (self-owned-context, `ToolBatchScope`-reuse) through — is widened
from `private` to `protected`. `MaestroTrailblazeAgent` and `PlaywrightTrailblazeAgent`'s
`nestedToolExecutor` closures now call `dispatchTools(tools = listOf(nestedTool), context = context,
elementComparator = NoOpElementComparator)` directly against the closure-captured `context` local
(already in scope in both `buildExecutionContext` overrides via the `lateinit var context` pattern),
instead of re-entering `runTrailblazeTools`. This is thread-hop-safe by construction: `context` is a
plain Kotlin closure variable, not a ThreadLocal read, so it resolves to the same instance
regardless of which thread the nested call happens to resume on.

Bypassing `runTrailblazeTools` also means nested calls don't push a new `SnapshotCache` frame or
reinstall `ToolExecutionContextThreadLocal` — they run inside whichever frame/install the *outer*
dispatch already established. On the common (no thread-hop) path this is strictly more correct: the
nested call joins the same frame as its enclosing scripted tool, matching how any other batch of
sibling tools shares one frame. On a thread-hopped nested call, `SnapshotCache.snapshot`/
`invalidateCurrent` degrade gracefully to their documented no-frame behavior (direct capture, no-op
invalidate) rather than erroring — an accepted, pre-existing limits of the thread-local family, not
a new one introduced here.

**`ComposeRpcTrailblazeAgent` deliberately NOT changed.** Its `nestedToolExecutor` has the identical
shape, but two things make the trade-off different there: (1) it carries no context-scoped device
state analogous to `AndroidDeviceCommandExecutor` — Compose's mutable per-session state
(`pendingDetailRequests`, `screenStateProvider`) lives on the *agent*, not the context, so it already
survives a context rebuild; there's no demonstrated bug to fix. (2) `ComposeRpcTrailblazeAgent`
overrides `runTrailblazeTools` to capture a post-batch screenshot for the HTML report, and its
existing kdoc calls out that nested composition intentionally routes through that override so a
nested call still gets its own `AgentDriverLog` screenshot. Switching to `dispatchTools` would skip
that override and silently drop the screenshot for nested compositions. Revisit if a future
Compose-side context-scoped cache creates the same bug class — at that point the fix is the same
shape, applied with an explicit decision about the screenshot trade-off.

**Test.** `MaestroNestedToolCompositionTest` (`trailblaze-common`, `jvmAndAndroidTest`) exercises the
real `MaestroTrailblazeAgent.buildExecutionContext` wiring end-to-end: a scripted tool composes two
nested framework-tool calls (modeling `mobile_setClipboard` → `mobile_pasteClipboard`) against a
fake device-state cache keyed by the *context's* `AndroidDeviceCommandExecutor` instance (a plain,
non-data class, so default reference identity is exactly the right key — it fails the same way the
real clipboard round trip does if the two nested calls land on different executor instances).
Confirmed failing before the fix (`AssertionFailedError`, paste read an empty cache) and passing
after.

## Follow-up: opensource host runners

Closes out open question 1's opensource half. A follow-up PR wired `sharedToolBatch` into every
remaining opensource `TrailblazeRunnerUtil` construction site except one: the host runners for
Playwright (native + electron), Compose (in-process and RPC), Revyl, and the host-Maestro runner
(`BaseHostTrailblazeTest`). Each of these agents already extends `BaseTrailblazeAgent`, so each
site is `sharedToolBatch = { block -> <agent>.runInSharedToolBatch(block) }` — the identical
one-line pattern `AndroidTrailblazeRule` already used.

**One site deliberately left unwired: the on-device-RPC runner** (`HostOnDeviceRpcTrailblazeAgent`,
also in `TrailblazeHostYamlRunner.kt`). Review on #4518 (Codex, Copilot, and an internal bot
reviewer) surfaced two independent problems with wiring it there:

1. **No correctness benefit.** `executeToolViaRpc` serializes each recorded tool as its own
   single-tool `RunYamlRequest` — the device-side dispatch (and its `AndroidDeviceCommandExecutor`
   clipboard cache) resets between tools regardless of what the host-side context shares. Unlike
   the in-process runners, there's no cross-tool device state on this path for `sharedToolBatch` to
   preserve.
2. **A real thread-hop hazard.** When Maestro→accessibility migration capture is enabled, this
   runner's `onBeforeRecordedTool`/`onAfterRecordedTool` hooks call `agent.captureScreenState()`, a
   suspend RPC call (via a Ktor `HttpClient`) whose continuation isn't guaranteed to resume on the
   entering thread. `ToolBatchScope` is thread-scoped (see its kdoc's `THREAD_HOP` note) and can't
   recover from that hop — it would leak the pushed `SnapshotCache` frame / installed ThreadLocal
   on the original thread.

Notably, this runner's *other* code path — nested scripted-tool composition — did NOT need the same
exclusion. It doesn't override `buildExecutionContext`, so it inherited the "Follow-up: nested
scripted-tool composition" fix above via `MaestroTrailblazeAgent` for free. That fix is safe here
specifically because it dispatches against a closure-captured `context` variable rather than a
ThreadLocal, so it has no analogous thread-hop hazard.

A real fix for the on-device-RPC recorded-replay path would need to batch across the RPC boundary
itself (the device side would need its own `ToolBatchScope` spanning the whole recording, not just
one RPC call) — a separate, more invasive change with no demonstrated need yet. Revisit if a
clipboard-style bug is ever reported for this specific path.

With this follow-up, every `TrailblazeRunnerUtil` construction site in the repo is now either wired
or deliberately-and-documented excluded.
