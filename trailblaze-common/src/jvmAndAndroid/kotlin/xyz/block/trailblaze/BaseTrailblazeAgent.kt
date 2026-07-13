package xyz.block.trailblaze

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeAgent
import xyz.block.trailblaze.api.TrailblazeAgent.RunTrailblazeToolsResult
import xyz.block.trailblaze.exception.TrailblazeToolExecutionException
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.logs.model.TraceId.Companion.TraceOrigin
import xyz.block.trailblaze.toolcalls.DelegatingTrailblazeTool
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.HostLocalExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.ReadOnlyTrailblazeTool
import xyz.block.trailblaze.toolcalls.SnapshotCache
import xyz.block.trailblaze.toolcalls.ToolBatchScope
import xyz.block.trailblaze.toolcalls.ToolExecutionContextThreadLocal
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.commands.memory.MemoryTrailblazeTool
import xyz.block.trailblaze.toolcalls.interpolateMemoryInTool
import xyz.block.trailblaze.toolcalls.isSuccess
import xyz.block.trailblaze.toolcalls.withAuthoredFailureContent
import xyz.block.trailblaze.toolcalls.isVerificationToolInstance
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.utils.ElementComparator
import xyz.block.trailblaze.utils.NoOpElementComparator

/**
 * Shared base for [TrailblazeAgent] implementations.
 *
 * Owns the sequential tool-execution loop, [MemoryTrailblazeTool] dispatch,
 * trace-ID generation, early-exit-on-failure, and [RunTrailblazeToolsResult]
 * construction. Subclasses provide agent-specific tool dispatch via
 * [executeTool] and context construction via [buildExecutionContext].
 */
abstract class BaseTrailblazeAgent(
  /**
   * Shared [AgentMemory] for the trail. Defaults to a fresh instance for the common case where
   * the agent owns its memory; callers that need to thread external memory through the agent
   * (e.g. the on-device `RunYamlRequestHandler` syncing host snapshots in and out) supply
   * their own instance so writes are visible across the boundary.
   */
  open override val memory: AgentMemory = AgentMemory(),
) : TrailblazeAgent, TrailblazeAgentContext {

  fun clearMemory() {
    memory.clear()
  }

  /**
   * Optional handle to the session's tool repo. When set, [runTrailblazeTools] resolves any
   * incoming [OtherTrailblazeTool] (the generic "unknown-at-decode-time" type the YAML decoder
   * produces for names it doesn't recognize) via [TrailblazeToolRepo.toolCallToTrailblazeTool].
   *
   * The primary motivating case is Decision 038 subprocess MCP — tools get registered into the
   * repo at session start (after YAML decode), so without this resolver a trail that names a
   * subprocess tool would reach the agent as an unresolvable [OtherTrailblazeTool] and every
   * driver-specific `executeTool` would fail. Subclasses that know their runner has a repo
   * (host-side runners in `TrailblazeHostYamlRunner`) override this; subclasses that don't
   * leave it `null` and pay the pre-resolution path no cost.
   */
  protected open val trailblazeToolRepo: TrailblazeToolRepo? = null

  /** Build the agent-specific [TrailblazeToolExecutionContext]. Called once per run. */
  protected abstract fun buildExecutionContext(
    traceId: TraceId,
    screenState: ScreenState?,
    screenStateProvider: (() -> ScreenState)?,
  ): TrailblazeToolExecutionContext

  /**
   * Public entry to [buildExecutionContext] for callers outside the standard [runTrailblazeTools]
   * dispatch path — notably the in-process Koog strategy-graph agent, which hands a per-call
   * execution context to `TrailblazeToolRepo.asToolRegistry` for the rare dynamic (subprocess-MCP)
   * tools that execute against a context rather than through this agent. Captures a fresh screen
   * state via [screenStateProvider] when a tool reads `context.screenState`. Lives on the base (not
   * a single driver) so every driver agent — web, Revyl, on-device — can be driven by the Koog
   * strategy graph through the same seam.
   */
  fun buildKoogToolExecutionContext(
    traceId: TraceId?,
    screenStateProvider: () -> ScreenState,
  ): TrailblazeToolExecutionContext = buildExecutionContext(
    traceId = traceId ?: TraceId.generate(TraceOrigin.TOOL),
    screenState = null,
    screenStateProvider = screenStateProvider,
  )

  /**
   * Dispatch a single tool. Implementations must add the tool (or its
   * expanded sub-tools for [DelegatingTrailblazeTool]) to [toolsExecuted].
   */
  protected abstract fun executeTool(
    tool: TrailblazeTool,
    context: TrailblazeToolExecutionContext,
    toolsExecuted: MutableList<TrailblazeTool>,
  ): TrailblazeToolResult

  /**
   * Dispatches [tools] sequentially, normally against a context built fresh for this call.
   *
   * When a [ToolBatchScope] is active on this thread (opened by [runInSharedToolBatch] around,
   * e.g., a recording's per-tool replay loop), this instead reuses the scope's shared context +
   * [SnapshotCache] frame — see [runInSharedToolBatch] for why and [ToolBatchScope] for the
   * lifecycle. Callers that never open a scope are unaffected.
   */
  override fun runTrailblazeTools(
    tools: List<TrailblazeTool>,
    traceId: TraceId?,
    screenState: ScreenState?,
    elementComparator: ElementComparator,
    screenStateProvider: (() -> ScreenState)?,
  ): RunTrailblazeToolsResult {
    val resolvedTraceId = traceId ?: TraceId.generate(TraceOrigin.TOOL)

    // When a shared tool-batch scope is open on this thread (opened by [runInSharedToolBatch]
    // around e.g. a recording's per-tool replay loop), reuse the scope's single execution context
    // + [SnapshotCache] frame + [ToolExecutionContextThreadLocal] install rather than building our
    // own. This keeps cross-tool device state that lives on the context IDENTITY (e.g. the Android
    // clipboard cache on `AndroidDeviceCommandExecutor`) alive across the group, matching the
    // legacy single-`- tools:`-block behavior. The scope owns teardown, so this branch neither pops
    // the frame nor clears the ThreadLocal.
    //
    // `screenState` is still refreshed on EVERY dispatch into the batch (not just the first) by
    // reassigning the shared context's mutable `screenState` field — each call here corresponds to
    // one recorded tool, and tools that read `context.screenState` directly (rather than
    // re-capturing via `screenStateProvider`, e.g. `TapOnTrailblazeTool`, `ClearTextTrailblazeTool`)
    // would otherwise act on the batch's first-tool snapshot for the rest of the recording. Only the
    // context/executor IDENTITY is shared; the screen capture itself stays per-tool, matching what
    // per-tool recorded replay did before this change. The assignment is unconditional: a null
    // incoming `screenState` clears the previous dispatch's cached value, re-arming the context's
    // lazy capture-on-read (see [TrailblazeToolExecutionContext.screenState]) so a tool that reads
    // the field still sees CURRENT UI — dispatchers that skip the eager capture stay correct.
    if (ToolBatchScope.isActive()) {
      val context = ToolBatchScope.contextOrBuild {
        buildExecutionContext(resolvedTraceId, screenState, screenStateProvider)
      }
      context.screenState = screenState
      return dispatchTools(tools, context, elementComparator)
    }

    val context = buildExecutionContext(resolvedTraceId, screenState, screenStateProvider)
    // Install the per-batch context in the scripted-tool composition ThreadLocal so any
    // QuickJS-bundled author tool that calls `trailblaze.call(...)` mid-dispatch can route
    // back into the same session's tool repo (#2749). The install/clear pair brackets the
    // entire sequential loop so every tool in the batch sees the same context, and the
    // `finally` guarantees the slot is freed even if a tool throws — leaking the slot
    // across batches would let one session observe another's context if the same thread
    // gets reused. The ThreadLocal lives in this module (not in
    // `:trailblaze-quickjs-tools`) so the install site doesn't need a compile-time dep on
    // QuickJS — the binding consumes the slot on the read side instead.
    ToolExecutionContextThreadLocal.install(context)
    // Wrap the whole batch in a single [SnapshotCache] frame so query tools issued by
    // the same batch (e.g. `findMatches` called multiple times across siblings) share one
    // captured view hierarchy instead of re-fetching it every call. Action tools
    // (non-read-only, non-verification) invalidate the slot on success so a post-action query
    // re-captures rather than reading a pre-action tree. Outside this frame,
    // [SnapshotCache.snapshot] falls back to direct capture, so tests that drive a tool's
    // `execute` without a batch still work.
    SnapshotCache.pushFrame()
    return try {
      dispatchTools(tools, context, elementComparator)
    } finally {
      SnapshotCache.popFrame()
      ToolExecutionContextThreadLocal.clear()
    }
  }

  /**
   * The sequential tool-execution loop, shared by the self-owned-context path and the
   * [ToolBatchScope]-reuse path in [runTrailblazeTools]. Assumes the [SnapshotCache] frame and
   * [ToolExecutionContextThreadLocal] install are already established (by the caller in the
   * self-owned path, by the scope in the reuse path); it manages neither. Logs per tool, invalidates
   * the snapshot after each mutating tool, and early-exits on the first non-success.
   */
  private fun dispatchTools(
    tools: List<TrailblazeTool>,
    context: TrailblazeToolExecutionContext,
    elementComparator: ElementComparator,
  ): RunTrailblazeToolsResult {
    val toolsExecuted = mutableListOf<TrailblazeTool>()
    var lastSuccessResult: TrailblazeToolResult = TrailblazeToolResult.Success()
    for (tool in tools) {
      val resolved = resolveDynamicTool(tool)
      val result =
        when (resolved) {
          // Memory tools execute in-process wherever this loop runs (host loop for host agents,
          // device loop for on-device agents) — so this loop IS their memory-interpolation
          // boundary. `toolsExecuted` keeps the AUTHORED instance (here and on every other
          // branch): it feeds the LLM chat history and the verify ledger, which must see the
          // token-bearing form — both for fidelity and so `rememberSensitive` values never
          // reach the LLM context.
          is MemoryTrailblazeTool -> {
            toolsExecuted.add(resolved)
            val memoryResolvedTool = interpolateMemoryInTool(resolved, memory)
            try {
              memoryResolvedTool.execute(memory = memory, elementComparator = elementComparator)
            } catch (e: TrailblazeToolExecutionException) {
              // Same authored-identity rule as `withAuthoredFailureContent` below: memory tools
              // throw with `tool = this`, which is now the RESOLVED instance, and both the
              // exception's tool and its message (built from the embedded result) render into
              // LLM-facing error content. Rebuild with the authored instance AND scrub any
              // rememberSensitive value the resolved prompt spliced into the error message
              // (e.g. a `rememberText` miss embeds the resolved prompt) so neither the args nor
              // the message ride the failure metadata.
              if (e.tool !== memoryResolvedTool || memoryResolvedTool === resolved) throw e
              throw TrailblazeToolExecutionException(
                tool = resolved,
                trailblazeToolResult = e.trailblazeToolResult.withAuthoredFailureContent(resolved, memory),
                cause = e,
              )
            }
          }
          // Host-local executables (e.g. subprocess MCP tools) bypass driver-specific dispatch
          // — they round-trip through host-side transport and don't belong to any device /
          // browser / cloud driver. Done in the base so every agent picks it up uniformly.
          //
          // Every host-local dispatch emits a `TrailblazeToolLog`. The advertised tool
          // name flows through the `HostLocalExecutableTrailblazeTool` marker and the raw
          // args through `RawArgumentTrailblazeTool` — both already handled by
          // `toLogPayload()` / `getToolNameFromAnnotation()`, so the log identifies the
          // tool by its dynamic name without needing a class-level `@TrailblazeToolClass`.
          // Recording, reports, and downstream debuggers all depend on this entry being
          // present — gating on a marker interface (the prior shape) left scripted /
          // subprocess MCP dispatches invisible to recordings and let the recording's
          // auto-save silently swap them for unrelated fallbacks.
          is HostLocalExecutableTrailblazeTool -> {
            toolsExecuted.add(resolved)
            // Host-locals execute right here, so this loop is their memory boundary too. For
            // the common concrete types (QuickJS / subprocess scripted tools, both
            // `RawArgumentTrailblazeTool`) this is a pass-through — they resolve their args
            // JSON at the engine boundary themselves — but a custom class-backed HostLocal
            // gets its string fields resolved like every other tool.
            val memoryResolvedTool = interpolateMemoryInTool(resolved, memory)
            val timeBeforeExecution = Clock.System.now()
            // Catch throws so the log emit still fires on the exception path. The contract
            // is "every dispatch logs" — if `execute` lets an exception escape (custom
            // HostLocal author, transport bug, etc.), the prior shape would skip the log
            // and re-open #2924. Convert to a `TrailblazeToolResult.Error.ExceptionThrown`,
            // log it, then surface it as a result so the early-exit branch downstream
            // treats it the same as a returned error. `CancellationException` re-throws so
            // structured concurrency for session teardown / agent abort stays intact.
            val hostResult: TrailblazeToolResult = try {
              runBlocking { memoryResolvedTool.execute(context) }
            } catch (e: CancellationException) {
              throw e
            } catch (e: Throwable) {
              // `resolved` (authored), not `memoryResolvedTool`: the embedded command renders
              // into LLM-facing error content and must keep the token-bearing form.
              TrailblazeToolResult.Error.ExceptionThrown.fromThrowable(e, resolved)
            }
            logToolExecution(
              tool = memoryResolvedTool,
              timeBeforeExecution = timeBeforeExecution,
              context = context,
              result = hostResult,
              // Flag this dispatch as host-side so session viewers / reports can badge it as
              // such, since the log payload is otherwise indistinguishable from an RPC-routed
              // tool's device-emitted log.
              dispatchedHostSide = true,
              rawTool = resolved.takeIf { it !== memoryResolvedTool },
            )
            hostResult
          }
          else -> executeTool(resolved, context, toolsExecuted)
          // Failure metadata carries the AUTHORED instance, mirroring `toolsExecuted`: a tool
          // that failed after boundary interpolation stamps `command = this` with the RESOLVED
          // instance, which would surface resolved memory values (incl. rememberSensitive
          // secrets) in LLM-facing error content — both in the command and in any resolved value
          // the tool spliced into its error message, so scrub both.
        }.withAuthoredFailureContent(resolved, memory)
      if (!result.isSuccess()) {
        return RunTrailblazeToolsResult(
          inputTools = tools,
          executedTools = toolsExecuted,
          result = result,
        )
      }
      // Default-invalidate: any tool that just ran could have mutated device
      // state, so the [SnapshotCache] tree captured by an earlier query in
      // this batch is potentially stale. Two opt-out paths preserve the
      // captured tree for a follow-up query:
      //
      //  - [ReadOnlyTrailblazeTool] (e.g. `findMatches`) explicitly declares
      //    the tool does not mutate state.
      //  - `isVerification = true` (assertion tools) — successful execution
      //    IS the assertion verdict, and verifications never mutate.
      //
      // The annotation flags `isRecordable` and `surfaceToLlm` are NOT used
      // here. `isRecordable = false` is set on delegation wrappers like
      // `TapTrailblazeTool` (the LLM-side tap) that absolutely mutate the
      // device — gating on it would let post-tap queries read stale trees.
      if (resolved !is ReadOnlyTrailblazeTool && !resolved.isVerificationToolInstance()) {
        SnapshotCache.invalidateCurrent(traceTag = context.traceId?.traceId)
      }
      lastSuccessResult = result
    }

    return RunTrailblazeToolsResult(
      inputTools = tools,
      executedTools = toolsExecuted,
      result = lastSuccessResult,
    )
  }

  /**
   * Builds a `nestedToolExecutor` for [TrailblazeToolExecutionContext.nestedToolExecutor] that
   * dispatches a nested `ctx.tools.X()` call directly against the context [contextProvider]
   * resolves, via [dispatchTools] — instead of rebuilding a fresh context through
   * [runTrailblazeTools] every nested call, which would build a brand-new context (and on
   * Android, a brand-new `AndroidDeviceCommandExecutor`, dropping any cross-call device-state
   * cache — the clipboard round trip is the canary). See
   * `docs/devlog/2026-07-03-batched-tool-execution-scope.md`'s "Deliberately out of scope"
   * section for the full rationale.
   *
   * `contextProvider` (not the context itself) so callers can pass a `{ context }` lambda that
   * closes over a `lateinit var` still being initialized at the call site — see
   * `MaestroTrailblazeAgent.buildExecutionContext` / `PlaywrightTrailblazeAgent.buildExecutionContext`,
   * both of which construct `nestedToolExecutor` inside the same `TrailblazeToolExecutionContext(...)`
   * call that assigns the `context` variable it reads.
   *
   * Thread-hop-safe by construction: `contextProvider` resolves a plain Kotlin variable, not a
   * ThreadLocal read, so it's correct regardless of which coroutine thread the nested call
   * resumes on (unlike wrapping the nested calls in a [ToolBatchScope], which is thread-scoped).
   *
   * Assumes — same as [dispatchTools] — that a [SnapshotCache] frame and
   * [ToolExecutionContextThreadLocal] install are already active on the dispatching thread,
   * which holds today because the only production callers
   * (`xyz.block.trailblaze.quickjs.tools.SessionScopedHostBinding.executeResolved`,
   * `xyz.block.trailblaze.scripting.callback.JsScriptingCallbackDispatcher.dispatchCallTool`)
   * only invoke `nestedToolExecutor` while a scripted tool is executing inside an active
   * [runTrailblazeTools] dispatch. If that assumption is ever violated, [SnapshotCache] and
   * [ToolExecutionContextThreadLocal] degrade gracefully (documented on their own kdocs) rather
   * than throwing, and emit their own `[SnapshotCache]` / clobber diagnostics.
   */
  protected fun nestedToolExecutorFor(
    contextProvider: () -> TrailblazeToolExecutionContext,
  ): suspend (TrailblazeTool) -> TrailblazeToolResult = { nestedTool ->
    val context = contextProvider()
    // Mark everything dispatched under this call as a nested `ctx.tools.X()` sub-call so its log is
    // stamped `isRecordable = false` (see `TrailblazeToolExecutionContext.nestedDispatchDepth` for
    // the full rationale). The try/finally is load-bearing: it restores the outer level even if the
    // nested tool THROWS (a throw propagates out of `dispatchTools` uncaught), so a later top-level
    // tool on this shared context isn't left mis-stamped non-recordable.
    context.nestedDispatchDepth.incrementAndGet()
    try {
      dispatchTools(
        tools = listOf(nestedTool),
        context = context,
        elementComparator = NoOpElementComparator,
      ).result
    } finally {
      context.nestedDispatchDepth.decrementAndGet()
    }
  }

  /**
   * Run [block] inside a shared tool-batch scope: every [runTrailblazeTools] call made on this
   * thread while [block] runs shares ONE execution context + ONE [SnapshotCache] frame + ONE
   * [ToolExecutionContextThreadLocal] install (all built lazily on the first dispatch), instead of
   * each call building its own.
   *
   * The motivating caller is recorded replay
   * ([xyz.block.trailblaze.rules.TrailblazeRunnerUtil.runRecordedTools]): a step's recorded tools
   * dispatch one-at-a-time, and sharing the context keeps cross-tool device state (e.g. the Android
   * clipboard cache on `AndroidDeviceCommandExecutor`) alive across the group so a
   * `setClipboard → pasteClipboard` recording replays correctly. Per-tool failure attribution,
   * capture hooks, cancellation, and per-tool logging all stay in the caller's loop — only the
   * context/frame lifetime is hoisted here.
   *
   * Nested calls (a scope already open) pass through and reuse the outer scope. The
   * `TRAILBLAZE_DISABLE_BATCHED_TOOL_EXECUTION` env var turns this into a plain pass-through (each
   * dispatch builds its own context — the pre-batch behavior) for a one-line revert.
   */
  suspend fun <R> runInSharedToolBatch(block: suspend () -> R): R {
    if (isBatchedToolExecutionDisabled() || ToolBatchScope.isActive()) {
      return block()
    }
    ToolBatchScope.enter()
    return try {
      block()
    } finally {
      ToolBatchScope.exit()
    }
  }

  /**
   * Kill-switch for [runInSharedToolBatch]: forces every dispatch back to its own execution context
   * (the pre-batch behavior). Read per call so it flips on a running daemon. Primarily a host/CI
   * safety valve — on-device instrumentation has no easy env channel. `1` or `true`
   * (case-insensitive) disables.
   */
  private fun isBatchedToolExecutionDisabled(): Boolean {
    val raw = System.getenv("TRAILBLAZE_DISABLE_BATCHED_TOOL_EXECUTION") ?: return false
    return raw == "1" || raw.equals("true", ignoreCase = true)
  }

  /**
   * Translates an [OtherTrailblazeTool] into its registered concrete tool when the repo knows
   * the name. Non-`OtherTrailblazeTool` inputs pass through. When no repo is wired or the name
   * isn't registered, the original [OtherTrailblazeTool] is returned — downstream dispatch
   * will surface a clear unsupported-tool error at that point.
   *
   * Catches only the narrow set of exceptions [TrailblazeToolRepo.toolCallToTrailblazeTool]
   * can raise on an unresolvable tool — `IllegalStateException` from its `error(...)` branch,
   * and `SerializationException` if the `raw` args don't fit the registered serializer. Other
   * throwables (notably `CancellationException`) propagate so structured concurrency stays
   * intact and programming errors aren't silently swallowed.
   */
  private fun resolveDynamicTool(tool: TrailblazeTool): TrailblazeTool {
    if (tool !is OtherTrailblazeTool) return tool
    val repo = trailblazeToolRepo ?: return tool
    return try {
      repo.toolCallToTrailblazeTool(toolName = tool.toolName, toolContent = tool.raw.toString())
    } catch (e: IllegalStateException) {
      Console.log("[BaseTrailblazeAgent] Could not resolve '${tool.toolName}' via repo: ${e.message}")
      tool
    } catch (e: kotlinx.serialization.SerializationException) {
      Console.log(
        "[BaseTrailblazeAgent] Serializer mismatch resolving '${tool.toolName}': ${e.message}",
      )
      tool
    }
  }

  /**
   * Helper for agents that support [DelegatingTrailblazeTool]. Expands
   * the tool, logs it, and executes each expanded sub-tool via [executeExpanded].
   * Adds expanded tools (not the delegating tool) to [toolsExecuted].
   */
  protected fun executeDelegatingTool(
    tool: DelegatingTrailblazeTool,
    context: TrailblazeToolExecutionContext,
    toolsExecuted: MutableList<TrailblazeTool>,
    executeExpanded: (ExecutableTrailblazeTool) -> TrailblazeToolResult,
  ): TrailblazeToolResult {
    val mappedTools = tool.toExecutableTrailblazeTools(context)
    logDelegatingTool(tool, context.traceId, mappedTools)
    var lastResult: TrailblazeToolResult = TrailblazeToolResult.Success()
    for (mappedTool in mappedTools) {
      toolsExecuted.add(mappedTool)
      val result = executeExpanded(mappedTool)
      if (!result.isSuccess()) return result
      lastResult = result
    }
    return lastResult
  }
}
