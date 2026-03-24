package xyz.block.trailblaze.revyl

import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeAgent
import xyz.block.trailblaze.api.TrailblazeAgent.RunTrailblazeToolsResult
import xyz.block.trailblaze.host.revyl.RevylCliClient
import xyz.block.trailblaze.host.revyl.RevylScreenState
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.revyl.tools.RevylExecutableTool
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.commands.ObjectiveStatusTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.memory.MemoryTrailblazeTool
import xyz.block.trailblaze.toolcalls.getToolNameFromAnnotation
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.utils.ElementComparator

/**
 * [TrailblazeAgent] that dispatches [RevylExecutableTool] instances against a
 * Revyl cloud device via [RevylCliClient].
 *
 * Unlike [RevylTrailblazeAgent] in trailblaze-host (which maps generic Trailblaze
 * mobile tools to CLI commands), this agent handles Revyl-specific tool types that
 * use natural language targeting and return resolved coordinates.
 *
 * @param cliClient CLI-based client for Revyl device interactions.
 * @param platform "ios" or "android" for ScreenState construction.
 */
class RevylToolAgent(
  private val cliClient: RevylCliClient,
  private val platform: String,
) : TrailblazeAgent {

  override fun runTrailblazeTools(
    tools: List<TrailblazeTool>,
    traceId: TraceId?,
    screenState: ScreenState?,
    elementComparator: ElementComparator,
    screenStateProvider: (() -> ScreenState)?,
  ): RunTrailblazeToolsResult {
    val executed = mutableListOf<TrailblazeTool>()
    val effectiveScreenStateProvider = screenStateProvider ?: { RevylScreenState(cliClient, platform) }

    for (tool in tools) {
      executed.add(tool)
      val result = dispatchTool(tool, effectiveScreenStateProvider)
      if (result !is TrailblazeToolResult.Success) {
        return RunTrailblazeToolsResult(
          inputTools = tools,
          executedTools = executed,
          result = result,
        )
      }
    }

    return RunTrailblazeToolsResult(
      inputTools = tools,
      executedTools = executed,
      result = TrailblazeToolResult.Success(),
    )
  }

  private fun dispatchTool(
    tool: TrailblazeTool,
    screenStateProvider: () -> ScreenState,
  ): TrailblazeToolResult {
    val toolName = tool.getToolNameFromAnnotation()
    Console.log("RevylToolAgent: executing '$toolName'")

    return try {
      when (tool) {
        is RevylExecutableTool -> {
          kotlinx.coroutines.runBlocking {
            tool.executeWithRevyl(cliClient, buildMinimalContext(screenStateProvider))
          }
        }
        is ObjectiveStatusTrailblazeTool -> TrailblazeToolResult.Success()
        is MemoryTrailblazeTool -> TrailblazeToolResult.Success()
        else -> {
          Console.log("RevylToolAgent: unsupported tool ${tool::class.simpleName}")
          TrailblazeToolResult.Error.UnknownTrailblazeTool(tool)
        }
      }
    } catch (e: Exception) {
      Console.error("RevylToolAgent: '$toolName' failed: ${e.message}")
      TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "Revyl tool '$toolName' failed: ${e.message}",
        command = tool,
        stackTrace = e.stackTraceToString(),
      )
    }
  }

  private fun buildMinimalContext(
    screenStateProvider: () -> ScreenState,
  ): TrailblazeToolExecutionContext {
    return TrailblazeToolExecutionContext(
      screenState = null,
      traceId = null,
      trailblazeDeviceInfo = xyz.block.trailblaze.devices.TrailblazeDeviceInfo.EMPTY,
      sessionProvider = object : xyz.block.trailblaze.logs.client.TrailblazeSessionProvider {
        override fun getSessionId(): String = ""
      },
      screenStateProvider = screenStateProvider,
      trailblazeLogger = xyz.block.trailblaze.logs.client.TrailblazeLogger.NOOP,
      memory = xyz.block.trailblaze.AgentMemory(),
    )
  }
}
