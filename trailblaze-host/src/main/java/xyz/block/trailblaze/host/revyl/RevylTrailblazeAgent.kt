package xyz.block.trailblaze.host.revyl

import kotlinx.coroutines.runBlocking
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.TrailblazeAgentContext
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeAgent
import xyz.block.trailblaze.api.TrailblazeAgent.RunTrailblazeToolsResult
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.revyl.RevylCliClient
import xyz.block.trailblaze.revyl.RevylDefaults
import xyz.block.trailblaze.revyl.RevylScreenState
import xyz.block.trailblaze.revyl.tools.RevylExecutableTool
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.commands.ObjectiveStatusTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.memory.MemoryTrailblazeTool
import xyz.block.trailblaze.toolcalls.getToolNameFromAnnotation
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.utils.ElementComparator

/**
 * [TrailblazeAgent] implementation that routes all device actions through
 * the Revyl CLI binary via [RevylCliClient].
 *
 * Each [TrailblazeTool] is mapped to the corresponding `revyl device`
 * subcommand. The CLI handles auth, backend proxying, and AI-powered
 * target grounding transparently.
 *
 * @property cliClient CLI-based client for Revyl device interactions.
 * @property platform "ios" or "android" — used for ScreenState construction.
 * @property trailblazeLogger Logger for tool execution and snapshot events.
 * @property trailblazeDeviceInfoProvider Provides device info for logging context.
 * @property sessionProvider Provides current session for logging operations.
 */
class RevylTrailblazeAgent(
  private val cliClient: RevylCliClient,
  private val platform: String,
  override val trailblazeLogger: TrailblazeLogger,
  override val trailblazeDeviceInfoProvider: () -> TrailblazeDeviceInfo,
  override val sessionProvider: TrailblazeSessionProvider,
) : TrailblazeAgent, TrailblazeAgentContext {

  override val memory = AgentMemory()

  /**
   * Dispatches a list of [TrailblazeTool]s by mapping each tool to
   * a `revyl device` CLI command via [RevylCliClient].
   *
   * @param tools Ordered list of tools to execute sequentially.
   * @param traceId Optional trace ID for log correlation.
   * @param screenState Cached screen state from the most recent LLM turn.
   * @param elementComparator Comparator for memory-based assertions.
   * @param screenStateProvider Lazy provider for a fresh device screenshot.
   * @return Execution result containing the input tools, executed tools, and outcome.
   */
  override fun runTrailblazeTools(
    tools: List<TrailblazeTool>,
    traceId: TraceId?,
    screenState: ScreenState?,
    elementComparator: ElementComparator,
    screenStateProvider: (() -> ScreenState)?,
  ): RunTrailblazeToolsResult {
    val executed = mutableListOf<TrailblazeTool>()

    for (tool in tools) {
      executed.add(tool)
      val result = executeTool(tool, screenStateProvider)
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

  private fun executeTool(
    tool: TrailblazeTool,
    screenStateProvider: (() -> ScreenState)?,
  ): TrailblazeToolResult {
    val toolName = tool.getToolNameFromAnnotation()
    Console.log("RevylAgent: executing tool '$toolName'")

    return try {
      when (tool) {
        is RevylExecutableTool -> {
          runBlocking {
            tool.executeWithRevyl(cliClient, buildRevylToolContext(screenStateProvider))
          }
        }
        is ObjectiveStatusTrailblazeTool -> TrailblazeToolResult.Success()
        is MemoryTrailblazeTool -> TrailblazeToolResult.Success()
        else -> {
          val unsupportedToolName = tool::class.simpleName ?: toolName
          Console.log("RevylAgent: unsupported tool $unsupportedToolName")
          TrailblazeToolResult.Error.ExceptionThrown(
            errorMessage = "Unsupported tool '$unsupportedToolName' for RevylTrailblazeAgent",
            command = tool,
            stackTrace = "",
          )
        }
      }
    } catch (e: Exception) {
      Console.error("RevylAgent: tool '$toolName' failed: ${e.message}")
      TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "CLI execution failed for '$toolName': ${e.message}",
        command = tool,
        stackTrace = e.stackTraceToString(),
      )
    }
  }

  /**
   * Builds a [TrailblazeToolExecutionContext] for executing [RevylExecutableTool]
   * instances, using this agent's logger, session provider, and device info.
   *
   * @param screenStateProvider Optional lazy provider for fresh screenshots.
   * @return A context suitable for Revyl native tool execution.
   */
  private fun buildRevylToolContext(
    screenStateProvider: (() -> ScreenState)?,
  ): TrailblazeToolExecutionContext {
    val deviceInfo = trailblazeDeviceInfoProvider()
    val effectiveScreenStateProvider = screenStateProvider
      ?: { RevylScreenState(cliClient, platform) }
    return TrailblazeToolExecutionContext(
      screenState = null,
      traceId = null,
      trailblazeDeviceInfo = deviceInfo,
      sessionProvider = sessionProvider,
      screenStateProvider = effectiveScreenStateProvider,
      trailblazeLogger = trailblazeLogger,
      memory = memory,
    )
  }
}
