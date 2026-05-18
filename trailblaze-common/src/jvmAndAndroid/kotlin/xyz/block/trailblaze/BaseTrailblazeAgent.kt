package xyz.block.trailblaze

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeAgent
import xyz.block.trailblaze.api.TrailblazeAgent.RunTrailblazeToolsResult
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.logs.model.TraceId.Companion.TraceOrigin
import xyz.block.trailblaze.toolcalls.DelegatingTrailblazeTool
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.HostLocalExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.ToolExecutionContextThreadLocal
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.commands.memory.MemoryTrailblazeTool
import xyz.block.trailblaze.toolcalls.isSuccess
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.utils.ElementComparator

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
   * Dispatch a single tool. Implementations must add the tool (or its
   * expanded sub-tools for [DelegatingTrailblazeTool]) to [toolsExecuted].
   */
  protected abstract fun executeTool(
    tool: TrailblazeTool,
    context: TrailblazeToolExecutionContext,
    toolsExecuted: MutableList<TrailblazeTool>,
  ): TrailblazeToolResult

  override fun runTrailblazeTools(
    tools: List<TrailblazeTool>,
    traceId: TraceId?,
    screenState: ScreenState?,
    elementComparator: ElementComparator,
    screenStateProvider: (() -> ScreenState)?,
  ): RunTrailblazeToolsResult {
    val resolvedTraceId = traceId ?: TraceId.generate(TraceOrigin.TOOL)
    val context = buildExecutionContext(resolvedTraceId, screenState, screenStateProvider)
    val toolsExecuted = mutableListOf<TrailblazeTool>()
    var lastSuccessResult: TrailblazeToolResult = TrailblazeToolResult.Success()

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
    try {
      for (tool in tools) {
        val resolved = resolveDynamicTool(tool)
        val result =
          when (resolved) {
            is MemoryTrailblazeTool -> {
              toolsExecuted.add(resolved)
              resolved.execute(memory = memory, elementComparator = elementComparator)
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
              val timeBeforeExecution = Clock.System.now()
              // Catch throws so the log emit still fires on the exception path. The contract
              // is "every dispatch logs" — if `execute` lets an exception escape (custom
              // HostLocal author, transport bug, etc.), the prior shape would skip the log
              // and re-open #2924. Convert to a `TrailblazeToolResult.Error.ExceptionThrown`,
              // log it, then surface it as a result so the early-exit branch downstream
              // treats it the same as a returned error. `CancellationException` re-throws so
              // structured concurrency for session teardown / agent abort stays intact.
              val hostResult: TrailblazeToolResult = try {
                runBlocking { resolved.execute(context) }
              } catch (e: CancellationException) {
                throw e
              } catch (e: Throwable) {
                TrailblazeToolResult.Error.ExceptionThrown.fromThrowable(e, resolved)
              }
              logToolExecution(
                tool = resolved,
                timeBeforeExecution = timeBeforeExecution,
                context = context,
                result = hostResult,
                // Flag this dispatch as host-side so session viewers / reports can badge it as
                // such, since the log payload is otherwise indistinguishable from an RPC-routed
                // tool's device-emitted log.
                dispatchedHostSide = true,
              )
              hostResult
            }
            else -> executeTool(resolved, context, toolsExecuted)
          }
        if (!result.isSuccess()) {
          return RunTrailblazeToolsResult(
            inputTools = tools,
            executedTools = toolsExecuted,
            result = result,
          )
        }
        lastSuccessResult = result
      }

      return RunTrailblazeToolsResult(
        inputTools = tools,
        executedTools = toolsExecuted,
        result = lastSuccessResult,
      )
    } finally {
      ToolExecutionContextThreadLocal.clear()
    }
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
