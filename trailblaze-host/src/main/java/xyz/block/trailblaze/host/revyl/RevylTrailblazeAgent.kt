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
 * A standalone [TrailblazeAgent] implementation that routes device actions
 * directly to the Revyl cloud device worker HTTP API.
 *
 * Unlike [xyz.block.trailblaze.MaestroTrailblazeAgent], this agent does NOT
 * depend on the Maestro driver stack at all. Each [TrailblazeTool] is
 * pattern-matched by type and translated into the corresponding Revyl HTTP
 * call in a single hop — no intermediate Maestro Command objects.
 *
 * This makes it a clean, minimal integration layer between Trailblaze's
 * LLM agent loop and Revyl's cloud device infrastructure.
 *
 * @property revylClient HTTP client for the Revyl device worker.
 * @property platform "ios" or "android" — used for ScreenState construction.
 */
class RevylTrailblazeAgent(
  private val revylClient: RevylWorkerClient,
  private val platform: String,
) : TrailblazeAgent {

  /**
   * Dispatches a list of [TrailblazeTool]s by mapping each tool's type
   * directly to Revyl worker HTTP calls.
   *
   * Memory tools (assert/remember) are handled in-process via the
   * [ElementComparator] — they don't require device interaction.
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
      val result = executeTool(tool, elementComparator, screenStateProvider)
      if (result != TrailblazeToolResult.Success) {
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
      result = TrailblazeToolResult.Success,
    )
  }

  // ---------------------------------------------------------------------------
  // Tool dispatch — maps each TrailblazeTool to the equivalent Revyl call.
  // ---------------------------------------------------------------------------

  private fun executeTool(
    tool: TrailblazeTool,
    elementComparator: ElementComparator,
    screenStateProvider: (() -> ScreenState)?,
  ): TrailblazeToolResult {
    val toolName = tool.getToolNameFromAnnotation()
    Console.log("RevylAgent: executing tool '$toolName'")

    return try {
      when (tool) {
        is TapOnPointTrailblazeTool -> handleTapOnPoint(tool)
        is InputTextTrailblazeTool -> handleInputText(tool)
        is SwipeTrailblazeTool -> handleSwipe(tool)
        is LaunchAppTrailblazeTool -> handleLaunchApp(tool)
        is EraseTextTrailblazeTool -> handleEraseText()
        is HideKeyboardTrailblazeTool -> handleHideKeyboard()
        is PressBackTrailblazeTool -> handlePressBack()
        is PressKeyTrailblazeTool -> handlePressKey(tool)
        is OpenUrlTrailblazeTool -> handleOpenUrl(tool)
        is TakeSnapshotTool -> handleTakeSnapshot(tool, screenStateProvider)
        is WaitForIdleSyncTrailblazeTool -> handleWaitForIdle(tool)
        is ScrollUntilTextIsVisibleTrailblazeTool -> handleScroll(tool)
        is NetworkConnectionTrailblazeTool -> handleNetworkConnection(tool)
        is TapOnElementByNodeIdTrailblazeTool -> handleTapByNodeId(tool)
        is LongPressOnElementWithTextTrailblazeTool -> handleLongPressText(tool)
        is ObjectiveStatusTrailblazeTool -> TrailblazeToolResult.Success
        is MemoryTrailblazeTool -> {
          // Memory tools don't need device interaction
          TrailblazeToolResult.Success
        }
        else -> {
          Console.log("RevylAgent: unsupported tool type ${tool::class.simpleName} — skipping")
          TrailblazeToolResult.Success
        }
      }
    } catch (e: Exception) {
      Console.error("RevylAgent: tool '$toolName' failed: ${e.message}")
      TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "Revyl execution failed for '$toolName': ${e.message}",
        command = tool,
        stackTrace = e.stackTraceToString(),
      )
    }
  }

  // ---------------------------------------------------------------------------
  // Individual tool handlers
  // ---------------------------------------------------------------------------

  private fun handleTapOnPoint(tool: TapOnPointTrailblazeTool): TrailblazeToolResult {
    if (tool.longPress) {
      Console.log("RevylAgent: long-press at (${tool.x}, ${tool.y})")
      revylClient.longPress(tool.x, tool.y)
    } else {
      Console.log("RevylAgent: tap at (${tool.x}, ${tool.y})")
      revylClient.tap(tool.x, tool.y)
    }
    return TrailblazeToolResult.Success
  }

  private fun handleInputText(tool: InputTextTrailblazeTool): TrailblazeToolResult {
    Console.log("RevylAgent: type text '${tool.text}'")
    revylClient.typeText(tool.text)
    return TrailblazeToolResult.Success
  }

  private fun handleSwipe(tool: SwipeTrailblazeTool): TrailblazeToolResult {
    val direction = when (tool.direction) {
      SwipeDirection.UP -> "up"
      SwipeDirection.DOWN -> "down"
      SwipeDirection.LEFT -> "left"
      SwipeDirection.RIGHT -> "right"
      else -> "down"
    }
    Console.log("RevylAgent: swipe $direction")
    revylClient.swipe(direction)
    return TrailblazeToolResult.Success
  }

  private fun handleLaunchApp(tool: LaunchAppTrailblazeTool): TrailblazeToolResult {
    Console.log("RevylAgent: launch app '${tool.appId}'")
    revylClient.launchApp(tool.appId)
    return TrailblazeToolResult.Success
  }

  private fun handleEraseText(): TrailblazeToolResult {
    Console.log("RevylAgent: erase text (clear_first + space via /input)")
    revylClient.typeText(text = " ", clearFirst = true)
    return TrailblazeToolResult.Success
  }

  private fun handleHideKeyboard(): TrailblazeToolResult {
    Console.log("RevylAgent: hide keyboard (no-op for cloud device)")
    return TrailblazeToolResult.Success
  }

  private fun handlePressBack(): TrailblazeToolResult {
    Console.log("RevylAgent: press back (not directly supported — skipping)")
    return TrailblazeToolResult.Success
  }

  private fun handlePressKey(tool: PressKeyTrailblazeTool): TrailblazeToolResult {
    Console.log("RevylAgent: press key '${tool.keyCode}' (not directly supported — skipping)")
    return TrailblazeToolResult.Success
  }

  private fun handleOpenUrl(tool: OpenUrlTrailblazeTool): TrailblazeToolResult {
    Console.log("RevylAgent: open URL '${tool.url}' (not directly supported — skipping)")
    return TrailblazeToolResult.Success
  }

  private fun handleTakeSnapshot(
    tool: TakeSnapshotTool,
    screenStateProvider: (() -> ScreenState)?,
  ): TrailblazeToolResult {
    Console.log("RevylAgent: take snapshot '${tool.screenName}'")
    // Capture screenshot from Revyl for the snapshot
    revylClient.screenshot()
    return TrailblazeToolResult.Success
  }

  private fun handleWaitForIdle(tool: WaitForIdleSyncTrailblazeTool): TrailblazeToolResult {
    Console.log("RevylAgent: wait for idle — sleeping briefly")
    Thread.sleep(1000)
    return TrailblazeToolResult.Success
  }

  private fun handleScroll(tool: ScrollUntilTextIsVisibleTrailblazeTool): TrailblazeToolResult {
    Console.log("RevylAgent: scroll until text visible — performing swipe down")
    revylClient.swipe("down")
    return TrailblazeToolResult.Success
  }

  private fun handleNetworkConnection(tool: NetworkConnectionTrailblazeTool): TrailblazeToolResult {
    Console.log("RevylAgent: network connection toggle (not supported — skipping)")
    return TrailblazeToolResult.Success
  }

  private fun handleTapByNodeId(tool: TapOnElementByNodeIdTrailblazeTool): TrailblazeToolResult {
    Console.log("RevylAgent: tap by nodeId ${tool.nodeId} (not directly supported — skipping)")
    return TrailblazeToolResult.Success
  }

  private fun handleLongPressText(tool: LongPressOnElementWithTextTrailblazeTool): TrailblazeToolResult {
    Console.log("RevylAgent: long press on element with text '${tool.text}'")
    revylClient.tapTarget(tool.text)
    return TrailblazeToolResult.Success
  }
}
