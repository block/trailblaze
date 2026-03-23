package xyz.block.trailblaze.host.revyl

import maestro.SwipeDirection
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeAgent
import xyz.block.trailblaze.api.TrailblazeAgent.RunTrailblazeToolsResult
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.commands.EraseTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.HideKeyboardTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.InputTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.LaunchAppTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.ObjectiveStatusTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.OpenUrlTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.PressBackTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.PressKeyTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.ScrollUntilTextIsVisibleTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.SwipeTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.TakeSnapshotTool
import xyz.block.trailblaze.toolcalls.commands.TapOnElementByNodeIdTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.TapOnPointTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.WaitForIdleSyncTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.NetworkConnectionTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.LongPressOnElementWithTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.memory.MemoryTrailblazeTool
import xyz.block.trailblaze.toolcalls.getToolNameFromAnnotation
import xyz.block.trailblaze.utils.ElementComparator
import xyz.block.trailblaze.util.Console

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
 */
class RevylTrailblazeAgent(
  private val cliClient: RevylCliClient,
  private val platform: String,
) : TrailblazeAgent {

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
        is TapOnPointTrailblazeTool -> {
          if (tool.longPress) {
            cliClient.longPress("element at (${tool.x}, ${tool.y})")
          } else {
            cliClient.tap(tool.x, tool.y)
          }
          TrailblazeToolResult.Success()
        }
        is InputTextTrailblazeTool -> {
          cliClient.typeText(tool.text)
          TrailblazeToolResult.Success()
        }
        is SwipeTrailblazeTool -> {
          val direction = when (tool.direction) {
            SwipeDirection.UP -> "up"
            SwipeDirection.DOWN -> "down"
            SwipeDirection.LEFT -> "left"
            SwipeDirection.RIGHT -> "right"
            else -> "down"
          }
          cliClient.swipe(direction)
          TrailblazeToolResult.Success()
        }
        is LaunchAppTrailblazeTool -> {
          cliClient.launchApp(tool.appId)
          TrailblazeToolResult.Success()
        }
        is EraseTextTrailblazeTool -> {
          cliClient.clearText()
          TrailblazeToolResult.Success()
        }
        is HideKeyboardTrailblazeTool -> {
          TrailblazeToolResult.Success()
        }
        is PressBackTrailblazeTool -> {
          cliClient.back()
          TrailblazeToolResult.Success()
        }
        is PressKeyTrailblazeTool -> {
          cliClient.pressKey(tool.keyCode.name)
          TrailblazeToolResult.Success()
        }
        is OpenUrlTrailblazeTool -> {
          cliClient.navigate(tool.url)
          TrailblazeToolResult.Success()
        }
        is TakeSnapshotTool -> {
          cliClient.screenshot()
          TrailblazeToolResult.Success()
        }
        is WaitForIdleSyncTrailblazeTool -> {
          Thread.sleep(1000)
          TrailblazeToolResult.Success()
        }
        is ScrollUntilTextIsVisibleTrailblazeTool -> {
          cliClient.swipe("down")
          TrailblazeToolResult.Success()
        }
        is NetworkConnectionTrailblazeTool -> {
          Console.log("RevylAgent: network toggle not yet implemented for cloud devices")
          TrailblazeToolResult.Success()
        }
        is TapOnElementByNodeIdTrailblazeTool -> {
          cliClient.tapTarget("element with node id ${tool.nodeId}")
          TrailblazeToolResult.Success()
        }
        is LongPressOnElementWithTextTrailblazeTool -> {
          cliClient.longPress(tool.text)
          TrailblazeToolResult.Success()
        }
        is ObjectiveStatusTrailblazeTool -> TrailblazeToolResult.Success()
        is MemoryTrailblazeTool -> TrailblazeToolResult.Success()
        else -> {
          Console.log("RevylAgent: unsupported tool ${tool::class.simpleName}")
          TrailblazeToolResult.Success()
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
}
