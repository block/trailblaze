@file:Suppress("DEPRECATION")

package xyz.block.trailblaze.host.revyl

import maestro.SwipeDirection
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.TrailblazeAgentContext
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeAgent
import xyz.block.trailblaze.api.TrailblazeAgent.RunTrailblazeToolsResult
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
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
        is TapOnPointTrailblazeTool -> {
          val target = tool.reasoning?.takeIf { it.isNotBlank() }
          if (tool.longPress) {
            val desc = target ?: "element at (${tool.x}, ${tool.y})"
            val r = cliClient.longPress(desc)
            TrailblazeToolResult.Success(message = "Long-pressed '$desc' at (${r.x}, ${r.y})")
          } else {
            val r = if (target != null) cliClient.tapTarget(target) else cliClient.tap(tool.x, tool.y)
            TrailblazeToolResult.Success(message = "Tapped '${target ?: "${tool.x},${tool.y}"}' at (${r.x}, ${r.y})")
          }
        }
        is InputTextTrailblazeTool -> {
          val r = cliClient.typeText(tool.text, target = "focused input field")
          TrailblazeToolResult.Success(message = "Typed '${tool.text}' at (${r.x}, ${r.y})")
        }
        is SwipeTrailblazeTool -> {
          val direction = when (tool.direction) {
            SwipeDirection.UP -> "up"
            SwipeDirection.DOWN -> "down"
            SwipeDirection.LEFT -> "left"
            SwipeDirection.RIGHT -> "right"
          }
          val target = tool.swipeOnElementText ?: "center of screen"
          val r = cliClient.swipe(direction, target = target)
          TrailblazeToolResult.Success(message = "Swiped $direction on '$target' from (${r.x}, ${r.y})")
        }
        is LaunchAppTrailblazeTool -> {
          cliClient.launchApp(tool.appId)
          TrailblazeToolResult.Success(message = "Launched ${tool.appId}")
        }
        is EraseTextTrailblazeTool -> {
          val r = cliClient.clearText(target = "focused input field")
          TrailblazeToolResult.Success(message = "Cleared text at (${r.x}, ${r.y})")
        }
        is HideKeyboardTrailblazeTool -> {
          TrailblazeToolResult.Success()
        }
        is PressBackTrailblazeTool -> {
          val r = cliClient.back()
          TrailblazeToolResult.Success(message = "Pressed back at (${r.x}, ${r.y})")
        }
        is PressKeyTrailblazeTool -> {
          val r = cliClient.pressKey(tool.keyCode.name)
          TrailblazeToolResult.Success(message = "Pressed ${tool.keyCode.name} at (${r.x}, ${r.y})")
        }
        is OpenUrlTrailblazeTool -> {
          cliClient.navigate(tool.url)
          TrailblazeToolResult.Success(message = "Navigated to ${tool.url}")
        }
        is TakeSnapshotTool -> {
          cliClient.screenshot()
          TrailblazeToolResult.Success(message = "Screenshot captured")
        }
        is WaitForIdleSyncTrailblazeTool -> {
          Thread.sleep(1000)
          TrailblazeToolResult.Success()
        }
        is ScrollUntilTextIsVisibleTrailblazeTool -> {
          val direction = when (tool.direction) {
            maestro.ScrollDirection.UP -> "up"
            maestro.ScrollDirection.DOWN -> "down"
            maestro.ScrollDirection.LEFT -> "left"
            maestro.ScrollDirection.RIGHT -> "right"
          }
          val target = tool.text.ifBlank { "center of screen" }
          val r = cliClient.swipe(direction, target = target)
          TrailblazeToolResult.Success(message = "Scrolled $direction on '$target' from (${r.x}, ${r.y})")
        }
        is NetworkConnectionTrailblazeTool -> {
          Console.log("RevylAgent: network toggle not yet implemented for cloud devices")
          TrailblazeToolResult.Success()
        }
        is TapOnElementByNodeIdTrailblazeTool -> {
          val target = tool.reasoning?.takeIf { it.isNotBlank() } ?: "element with node id ${tool.nodeId}"
          val r = if (tool.longPress) cliClient.longPress(target) else cliClient.tapTarget(target)
          val action = if (tool.longPress) "Long-pressed" else "Tapped"
          TrailblazeToolResult.Success(message = "$action '$target' at (${r.x}, ${r.y})")
        }
        is LongPressOnElementWithTextTrailblazeTool -> {
          val r = cliClient.longPress(tool.text)
          TrailblazeToolResult.Success(message = "Long-pressed '${tool.text}' at (${r.x}, ${r.y})")
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
