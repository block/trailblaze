package xyz.block.trailblaze

import kotlinx.coroutines.runBlocking
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeAgent
import xyz.block.trailblaze.api.TrailblazeAgent.RunTrailblazeToolsResult
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.logs.model.TraceId.Companion.TraceOrigin
import xyz.block.trailblaze.toolcalls.DelegatingTrailblazeTool
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.HostLocalExecutableTrailblazeTool
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
          // Per-tool session-log entries (`logToolExecution`) are deliberately NOT emitted
          // here: `TrailblazeLog.TrailblazeToolLog` serializes the full tool instance, and
          // dynamically-built host-local tools (like `SubprocessTrailblazeTool`) aren't
          // `@Serializable` — their state (session closures, arg JsonObjects) doesn't fit
          // the static polymorphic registry. Session start/end logs still capture the
          // trail YAML + outcomes; richer per-call telemetry is tracked as follow-up.
          is HostLocalExecutableTrailblazeTool -> {
            toolsExecuted.add(resolved)
            runBlocking { resolved.execute(context) }
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
