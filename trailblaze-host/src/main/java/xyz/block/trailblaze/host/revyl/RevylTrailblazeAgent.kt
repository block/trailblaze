package xyz.block.trailblaze.host.revyl

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import xyz.block.trailblaze.BaseTrailblazeAgent
import xyz.block.trailblaze.logToolExecution
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.revyl.RevylCliClient
import xyz.block.trailblaze.revyl.RevylScreenState
import xyz.block.trailblaze.revyl.tools.RevylExecutableTool
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.commands.ObjectiveStatusTrailblazeTool
import xyz.block.trailblaze.toolcalls.getToolNameFromAnnotation
import xyz.block.trailblaze.util.Console

/**
 * [BaseTrailblazeAgent] implementation that routes all device actions through
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
  override val trailblazeToolRepo: TrailblazeToolRepo? = null,
) : BaseTrailblazeAgent() {

  override fun buildExecutionContext(
    traceId: TraceId,
    screenState: ScreenState?,
    screenStateProvider: (() -> ScreenState)?,
  ): TrailblazeToolExecutionContext {
    val deviceInfo = trailblazeDeviceInfoProvider()
    val effectiveScreenStateProvider = screenStateProvider
      ?: { RevylScreenState(cliClient, platform) }
    return TrailblazeToolExecutionContext(
      screenState = screenState,
      traceId = traceId,
      trailblazeDeviceInfo = deviceInfo,
      sessionProvider = sessionProvider,
      screenStateProvider = effectiveScreenStateProvider,
      trailblazeLogger = trailblazeLogger,
      memory = memory,
    )
  }

  override fun executeTool(
    tool: TrailblazeTool,
    context: TrailblazeToolExecutionContext,
    toolsExecuted: MutableList<TrailblazeTool>,
  ): TrailblazeToolResult {
    val toolName = tool.getToolNameFromAnnotation()
    Console.log("RevylAgent: executing tool '$toolName'")
    toolsExecuted.add(tool)

    val timeBeforeExecution = Clock.System.now()
    val result = try {
      when (tool) {
        is RevylExecutableTool -> {
          runBlocking { tool.executeWithRevyl(cliClient, context) }
        }
        is ObjectiveStatusTrailblazeTool -> TrailblazeToolResult.Success()
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
    if (tool is RevylExecutableTool) {
      logToolExecution(tool, timeBeforeExecution, context, result)
    }
    return result
  }
}
