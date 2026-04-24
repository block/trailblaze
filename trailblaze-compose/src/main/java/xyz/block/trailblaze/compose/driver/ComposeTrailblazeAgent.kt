package xyz.block.trailblaze.compose.driver

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import xyz.block.trailblaze.BaseTrailblazeAgent
import xyz.block.trailblaze.logToolExecution
import xyz.block.trailblaze.api.AgentActionType
import xyz.block.trailblaze.api.AgentDriverAction
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.compose.driver.tools.ComposeClickTool
import xyz.block.trailblaze.compose.driver.tools.ComposeExecutableTool
import xyz.block.trailblaze.compose.driver.tools.ComposeRequestDetailsTool
import xyz.block.trailblaze.compose.driver.tools.ComposeScrollTool
import xyz.block.trailblaze.compose.driver.tools.ComposeTypeTool
import xyz.block.trailblaze.compose.driver.tools.ComposeVerifyElementVisibleTool
import xyz.block.trailblaze.compose.driver.tools.ComposeVerifyTextVisibleTool
import xyz.block.trailblaze.compose.driver.tools.ComposeWaitTool
import xyz.block.trailblaze.compose.target.ComposeTestTarget
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.toolcalls.DelegatingTrailblazeTool
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.commands.memory.MemoryTrailblazeTool
import xyz.block.trailblaze.toolcalls.isSuccess
import xyz.block.trailblaze.util.Console

/**
 * Compose implementation of [TrailblazeAgent].
 *
 * This agent executes tools directly against a [ComposeTestTarget] without any Maestro or
 * Playwright dependency. It uses the Compose semantics tree for element identification and
 * ComposeTestTarget APIs for interaction.
 *
 * Tools that implement [ComposeExecutableTool] are executed via
 * [ComposeExecutableTool.executeWithCompose] with the current ComposeTestTarget. Generic
 * [ExecutableTrailblazeTool] instances (like TakeSnapshotTool) are executed via the standard
 * [ExecutableTrailblazeTool.execute] path.
 */
class ComposeTrailblazeAgent(
  val target: ComposeTestTarget,
  override val trailblazeLogger: TrailblazeLogger,
  override val trailblazeDeviceInfoProvider: () -> TrailblazeDeviceInfo,
  override val sessionProvider: TrailblazeSessionProvider,
  private val viewportWidth: Int = DEFAULT_VIEWPORT_WIDTH,
  private val viewportHeight: Int = DEFAULT_VIEWPORT_HEIGHT,
  override val trailblazeToolRepo: TrailblazeToolRepo? = null,
) : BaseTrailblazeAgent() {

  companion object {
    /**
     * Default viewport width for the Compose desktop test window.
     *
     * 1280x800 (16:10) approximates a typical desktop application window. This is used as the
     * capture dimensions for screenshots and element coordinate mapping.
     */
    const val DEFAULT_VIEWPORT_WIDTH = 1280

    /** Default viewport height for the Compose desktop test window. See [DEFAULT_VIEWPORT_WIDTH]. */
    const val DEFAULT_VIEWPORT_HEIGHT = 800
  }

  private val pendingDetailRequests =
    AtomicReference<Set<ComposeViewHierarchyDetail>>(emptySet())

  val screenStateProvider: () -> ScreenState = {
    val details = pendingDetailRequests.getAndSet(emptySet())
    ComposeScreenState(target, viewportWidth, viewportHeight, requestedDetails = details)
  }

  override fun buildExecutionContext(
    traceId: TraceId,
    screenState: ScreenState?,
    screenStateProvider: (() -> ScreenState)?,
  ): TrailblazeToolExecutionContext {
    val trailblazeDeviceInfo = trailblazeDeviceInfoProvider()
    return TrailblazeToolExecutionContext(
      screenState = screenState,
      traceId = traceId,
      trailblazeDeviceInfo = trailblazeDeviceInfo,
      sessionProvider = sessionProvider,
      screenStateProvider = screenStateProvider ?: this.screenStateProvider,
      trailblazeLogger = trailblazeLogger,
      memory = memory,
      maestroTrailblazeAgent = null,
    )
  }

  override fun executeTool(
    tool: TrailblazeTool,
    context: TrailblazeToolExecutionContext,
    toolsExecuted: MutableList<TrailblazeTool>,
  ): TrailblazeToolResult {
    return when (tool) {
      is ComposeExecutableTool -> {
        toolsExecuted.add(tool)
        val result = executeComposeToolBlocking(tool, context)
        if (result.isSuccess() && tool is ComposeRequestDetailsTool) {
          pendingDetailRequests.set(tool.include.toSet())
        }
        result
      }
      is ExecutableTrailblazeTool -> {
        toolsExecuted.add(tool)
        executeToolBlocking(tool, context)
      }
      is DelegatingTrailblazeTool -> {
        executeDelegatingTool(tool, context, toolsExecuted) { mappedTool ->
          if (mappedTool is ComposeExecutableTool) {
            executeComposeToolBlocking(mappedTool, context)
          } else {
            executeToolBlocking(mappedTool, context)
          }
        }
      }
      else ->
        throw TrailblazeException(
          message =
            buildString {
              appendLine("Unhandled Trailblaze tool ${tool::class.java.simpleName} - $tool.")
              appendLine("ComposeTrailblazeAgent supports:")
              appendLine("- ${ComposeExecutableTool::class.java.simpleName}")
              appendLine("- ${ExecutableTrailblazeTool::class.java.simpleName}")
              appendLine("- ${DelegatingTrailblazeTool::class.java.simpleName}")
              appendLine("- ${MemoryTrailblazeTool::class.java.simpleName}")
            },
        )
    }
  }

  private fun executeComposeToolBlocking(
    tool: ComposeExecutableTool,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult = runBlocking {
    // Capture screen state BEFORE execution for the screenshot overlay.
    // Catch Throwable because ComposeUiTest throws AssertionError (not Exception) when
    // there are multiple root nodes (e.g., dialog overlays).
    val preScreenState = try { screenStateProvider() } catch (_: Throwable) { null }

    val timeBeforeExecution = Clock.System.now()
    val result = tool.executeWithCompose(target, context)
    val executionTimeMs =
      Clock.System.now().toEpochMilliseconds() - timeBeforeExecution.toEpochMilliseconds()
    logToolExecution(tool, timeBeforeExecution, context, result)

    // Log AgentDriverLog with pre-action screenshot for visualization
    logAgentDriverAction(
      tool, result, preScreenState, executionTimeMs, timeBeforeExecution, context.traceId
    )

    result
  }

  private fun executeToolBlocking(
    tool: ExecutableTrailblazeTool,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult = runBlocking {
    val timeBeforeExecution = Clock.System.now()
    val result = tool.execute(context)
    logToolExecution(tool, timeBeforeExecution, context, result)
    result
  }

  /**
   * Logs an [AgentDriverLog] with the pre-action screenshot for unified visualization.
   */
  private fun logAgentDriverAction(
    tool: ComposeExecutableTool,
    result: TrailblazeToolResult,
    preScreenState: ScreenState?,
    executionTimeMs: Long,
    startTime: Instant,
    traceId: TraceId?,
  ) {
    if (preScreenState == null) return
    try {
      val session = sessionProvider.invoke()
      val screenshotFilename = if (preScreenState.screenshotBytes != null) {
        trailblazeLogger.logScreenState(session, preScreenState)
      } else {
        null
      }

      val action = mapToolToAgentDriverAction(tool, result)

      val log = TrailblazeLog.AgentDriverLog(
        viewHierarchy = preScreenState.viewHierarchy,
        trailblazeNodeTree = preScreenState.trailblazeNodeTree,
        screenshotFile = screenshotFilename,
        action = action,
        durationMs = executionTimeMs,
        timestamp = startTime,
        session = session.sessionId,
        deviceWidth = preScreenState.deviceWidth,
        deviceHeight = preScreenState.deviceHeight,
        traceId = traceId,
      )
      trailblazeLogger.log(session, log)
    } catch (e: Throwable) {
      // Catch Throwable because ComposeUiTest throws AssertionError (not Exception)
      // when there are multiple root nodes (e.g., dialog overlays).
      Console.log("Warning: Failed to log AgentDriverAction: ${e.message}")
    }
  }

  /**
   * Maps a Compose tool to an [AgentDriverAction] for unified logging visualization.
   * Resolves element coordinates via Compose semantics tree for click and verify tools.
   */
  private fun mapToolToAgentDriverAction(
    tool: ComposeExecutableTool,
    result: TrailblazeToolResult,
  ): AgentDriverAction {
    val succeeded = result.isSuccess()
    return when (tool) {
      is ComposeClickTool -> {
        val (x, y) = resolveComposeElementCenter(tool.elementId, tool.testTag, tool.text)
        AgentDriverAction.TapPoint(x = x, y = y)
      }
      is ComposeTypeTool ->
        AgentDriverAction.EnterText(text = tool.text)
      is ComposeScrollTool ->
        AgentDriverAction.Scroll(forward = true)
      is ComposeWaitTool ->
        AgentDriverAction.WaitForSettle(timeoutMs = tool.seconds * 1000L)
      is ComposeVerifyTextVisibleTool -> {
        val (x, y) = resolveComposeTextCenter(tool.text)
        AgentDriverAction.AssertCondition(
          conditionDescription = "Verify text visible: ${tool.text}",
          x = x, y = y,
          isVisible = true,
          textToDisplay = tool.text,
          succeeded = succeeded,
        )
      }
      is ComposeVerifyElementVisibleTool -> {
        val (x, y) = resolveComposeElementCenter(tool.elementId, tool.testTag, null)
        AgentDriverAction.AssertCondition(
          conditionDescription = "Verify element visible: ${tool.element.ifBlank { tool.testTag ?: tool.elementId ?: "unknown" }}",
          x = x, y = y,
          isVisible = true,
          succeeded = succeeded,
        )
      }
      else -> AgentDriverAction.OtherAction(AgentActionType.TAP_POINT)
    }
  }

  /**
   * Resolves a Compose element to center coordinates via the semantics tree.
   * Uses the same resolution logic as [ComposeExecutableTool.resolveElement].
   * Returns (0, 0) if the element can't be found.
   */
  private fun resolveComposeElementCenter(
    elementId: String?,
    testTag: String?,
    text: String?,
  ): Pair<Int, Int> {
    return try {
      val screenState = screenStateProvider()
      val context = TrailblazeToolExecutionContext(
        screenState = screenState,
        traceId = null,
        trailblazeDeviceInfo = trailblazeDeviceInfoProvider(),
        sessionProvider = sessionProvider,
        screenStateProvider = screenStateProvider,
        trailblazeLogger = trailblazeLogger,
        memory = memory,
        maestroTrailblazeAgent = null,
      )
      val matcher = ComposeExecutableTool.resolveElement(elementId, testTag, text, context)
        ?: return 0 to 0
      val nthIndex = ComposeExecutableTool.getNthIndex(elementId, context)
      val matchingNodes = target.allSemanticsNodes().filter { matcher.matches(it) }
      val semanticsNode = if (nthIndex > 0 && nthIndex < matchingNodes.size) {
        matchingNodes[nthIndex]
      } else {
        matchingNodes.firstOrNull() ?: return 0 to 0
      }
      val bounds = semanticsNode.boundsInWindow
      val centerX = ((bounds.left + bounds.right) / 2).toInt()
      val centerY = ((bounds.top + bounds.bottom) / 2).toInt()
      centerX to centerY
    } catch (_: Exception) {
      0 to 0
    }
  }

  /**
   * Resolves text content to center coordinates via the Compose semantics tree.
   * Returns (0, 0) if the text can't be found.
   */
  private fun resolveComposeTextCenter(text: String): Pair<Int, Int> {
    return try {
      val textMatcher = hasText(text, substring = false)
      val nodes = target.allSemanticsNodes().filter { textMatcher.matches(it) }
      if (nodes.isNotEmpty()) {
        val bounds = nodes.first().boundsInWindow
        val centerX = ((bounds.left + bounds.right) / 2).toInt()
        val centerY = ((bounds.top + bounds.bottom) / 2).toInt()
        centerX to centerY
      } else {
        0 to 0
      }
    } catch (_: Throwable) {
      0 to 0
    }
  }
}
