package xyz.block.trailblaze

import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeAgent
import xyz.block.trailblaze.api.TrailblazeAgent.RunTrailblazeToolsResult
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.logs.model.TraceId.Companion.TraceOrigin
import xyz.block.trailblaze.toolcalls.DelegatingTrailblazeTool
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.commands.memory.MemoryTrailblazeTool
import xyz.block.trailblaze.toolcalls.isSuccess
import xyz.block.trailblaze.utils.ElementComparator

/**
 * Shared base for [TrailblazeAgent] implementations.
 *
 * Owns the sequential tool-execution loop, [MemoryTrailblazeTool] dispatch,
 * trace-ID generation, early-exit-on-failure, and [RunTrailblazeToolsResult]
 * construction. Subclasses provide agent-specific tool dispatch via
 * [executeTool] and context construction via [buildExecutionContext].
 */
abstract class BaseTrailblazeAgent : TrailblazeAgent, TrailblazeAgentContext {

  open override val memory = AgentMemory()

  fun clearMemory() {
    memory.clear()
  }

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
      val result =
        when (tool) {
          is MemoryTrailblazeTool -> {
            toolsExecuted.add(tool)
            tool.execute(memory = memory, elementComparator = elementComparator)
          }
          else -> executeTool(tool, context, toolsExecuted)
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
