package xyz.block.trailblaze.mcp.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeAgent
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.mcp.TrailblazeMcpBridge
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.utils.ElementComparator

/**
 * Adapter that implements [TrailblazeAgent] using [TrailblazeMcpBridge].
 *
 * This allows [DirectMcpAgent] to run on the host (server) side by delegating
 * tool execution to the MCP bridge, which in turn uses Maestro/ADB to interact
 * with the device.
 *
 * On-device, the agent uses [AndroidMaestroTrailblazeAgent] instead, which
 * executes tools directly via UI Automator.
 */
class BridgeTrailblazeAgent(
  private val mcpBridge: TrailblazeMcpBridge,
) : TrailblazeAgent {

  override fun runTrailblazeTools(
    tools: List<TrailblazeTool>,
    traceId: TraceId?,
    screenState: ScreenState?,
    elementComparator: ElementComparator,
    screenStateProvider: (() -> ScreenState)?,
  ): TrailblazeAgent.RunTrailblazeToolsResult {
    // Execute tools sequentially via the bridge
    val executedTools = mutableListOf<TrailblazeTool>()

    for (tool in tools) {
      try {
        // executeTrailblazeTool is suspend, but runTrailblazeTools is not.
        // Use Dispatchers.IO to avoid deadlocking if the caller is already on a coroutine thread.
        val result = runBlocking(Dispatchers.IO) {
          mcpBridge.executeTrailblazeTool(tool, traceId = traceId)
        }
        executedTools.add(tool)
        Console.log("[BridgeTrailblazeAgent] Executed ${tool::class.simpleName}: $result")
      } catch (e: Exception) {
        Console.error("[BridgeTrailblazeAgent] Failed to execute ${tool::class.simpleName}: ${e.message}")
        return TrailblazeAgent.RunTrailblazeToolsResult(
          inputTools = tools,
          executedTools = executedTools,
          result = TrailblazeToolResult.Error.ExceptionThrown(
            errorMessage = e.message ?: "Unknown error",
            stackTrace = e.stackTraceToString(),
          ),
        )
      }
    }

    return TrailblazeAgent.RunTrailblazeToolsResult(
      inputTools = tools,
      executedTools = executedTools,
      result = TrailblazeToolResult.Success(),
    )
  }
}
