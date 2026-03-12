package xyz.block.trailblaze.playwright

import com.microsoft.playwright.TimeoutError
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.TrailblazeAgentContext
import xyz.block.trailblaze.api.AgentActionType
import xyz.block.trailblaze.api.AgentDriverAction
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeAgent
import xyz.block.trailblaze.api.TrailblazeNodeSelectorGenerator
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.logDelegatingTool
import xyz.block.trailblaze.logToolExecution
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.logs.model.TraceId.Companion.TraceOrigin
import xyz.block.trailblaze.playwright.tools.PlaywrightExecutableTool
import xyz.block.trailblaze.playwright.tools.PlaywrightNativeClickTool
import xyz.block.trailblaze.playwright.tools.PlaywrightNativeHoverTool
import xyz.block.trailblaze.playwright.tools.PlaywrightNativeNavigateTool
import xyz.block.trailblaze.playwright.tools.PlaywrightNativePressKeyTool
import xyz.block.trailblaze.playwright.tools.PlaywrightNativeRequestDetailsTool
import xyz.block.trailblaze.playwright.tools.PlaywrightNativeScrollTool
import xyz.block.trailblaze.playwright.tools.PlaywrightNativeSelectOptionTool
import xyz.block.trailblaze.playwright.tools.PlaywrightNativeTypeTool
import xyz.block.trailblaze.playwright.tools.PlaywrightNativeVerifyElementVisibleTool
import xyz.block.trailblaze.playwright.tools.PlaywrightNativeVerifyListVisibleTool
import xyz.block.trailblaze.playwright.tools.PlaywrightNativeVerifyTextVisibleTool
import xyz.block.trailblaze.playwright.tools.PlaywrightNativeVerifyValueTool
import xyz.block.trailblaze.playwright.tools.PlaywrightNativeWaitTool
import xyz.block.trailblaze.toolcalls.commands.TakeSnapshotTool
import xyz.block.trailblaze.toolcalls.DelegatingTrailblazeTool
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.isSuccess
import xyz.block.trailblaze.tracing.TrailblazeTracer
import xyz.block.trailblaze.toolcalls.commands.memory.MemoryTrailblazeTool
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.utils.ElementComparator

/**
 * Playwright-native implementation of [TrailblazeAgent].
 *
 * This agent executes tools directly against a Playwright browser without any
 * Maestro dependency. It uses ARIA snapshots for element identification and
 * web-native Playwright APIs for interaction.
 *
 * Tools that implement [PlaywrightExecutableTool] are executed via
 * [PlaywrightExecutableTool.executeWithPlaywright] with the current page.
 * Generic [ExecutableTrailblazeTool] instances (like [TakeSnapshotTool]) are
 * executed via the standard [ExecutableTrailblazeTool.execute] path.
 */
class PlaywrightTrailblazeAgent(
  val browserManager: PlaywrightPageManager,
  override val trailblazeLogger: TrailblazeLogger,
  override val trailblazeDeviceInfoProvider: () -> TrailblazeDeviceInfo,
  override val sessionProvider: TrailblazeSessionProvider,
) : TrailblazeAgent, TrailblazeAgentContext {

  override val memory = AgentMemory()

  /**
   * Working directory for resolving relative file paths in tools.
   * Set to the trail file's parent directory before running a trail.
   */
  var workingDirectory: java.io.File? = null

  val screenStateProvider: () -> ScreenState = { browserManager.getScreenState() }

  fun clearMemory() {
    memory.clear()
  }

  override fun runTrailblazeTools(
    tools: List<TrailblazeTool>,
    traceId: TraceId?,
    screenState: ScreenState?,
    elementComparator: ElementComparator,
    screenStateProvider: (() -> ScreenState)?,
  ): TrailblazeAgent.RunTrailblazeToolsResult {
    val resolvedTraceId = traceId ?: TraceId.generate(TraceOrigin.TOOL)
    val trailblazeDeviceInfo = trailblazeDeviceInfoProvider()
    val executionContext =
      TrailblazeToolExecutionContext(
        screenState = screenState,
        traceId = resolvedTraceId,
        trailblazeDeviceInfo = trailblazeDeviceInfo,
        sessionProvider = sessionProvider,
        screenStateProvider = screenStateProvider ?: this.screenStateProvider,
        trailblazeLogger = trailblazeLogger,
        memory = memory,
        maestroTrailblazeAgent = null,
        workingDirectory = workingDirectory,
      )

    val toolsExecuted = mutableListOf<TrailblazeTool>()
    var lastSuccessResult: TrailblazeToolResult = TrailblazeToolResult.Success()
    for (tool in tools) {
      when (tool) {
        is PlaywrightExecutableTool -> {
          toolsExecuted.add(tool)
          val result = executePlaywrightToolBlocking(tool, executionContext)
          if (!result.isSuccess()) {
            return TrailblazeAgent.RunTrailblazeToolsResult(
              inputTools = tools,
              executedTools = toolsExecuted,
              result = result,
            )
          }
          lastSuccessResult = result
        }

        is ExecutableTrailblazeTool -> {
          toolsExecuted.add(tool)
          val result = executeToolBlocking(tool, executionContext)
          if (!result.isSuccess()) {
            return TrailblazeAgent.RunTrailblazeToolsResult(
              inputTools = tools,
              executedTools = toolsExecuted,
              result = result,
            )
          }
          lastSuccessResult = result
        }

        is DelegatingTrailblazeTool -> {
          val mappedTools = tool.toExecutableTrailblazeTools(executionContext)
          logDelegatingTool(tool, resolvedTraceId, mappedTools)
          val originalTools = listOf(tool)
          for (mappedTool in mappedTools) {
            toolsExecuted.add(mappedTool)
            val result =
              if (mappedTool is PlaywrightExecutableTool) {
                executePlaywrightToolBlocking(mappedTool, executionContext)
              } else {
                executeToolBlocking(mappedTool, executionContext)
              }
            if (!result.isSuccess()) {
              return TrailblazeAgent.RunTrailblazeToolsResult(
                inputTools = originalTools,
                executedTools = toolsExecuted,
                result = result,
              )
            }
            lastSuccessResult = result
          }
        }

        is MemoryTrailblazeTool -> {
          toolsExecuted.add(tool)
          val result = tool.execute(
            memory = memory,
            elementComparator = elementComparator,
          )
          if (!result.isSuccess()) {
            return TrailblazeAgent.RunTrailblazeToolsResult(
              inputTools = tools,
              executedTools = toolsExecuted,
              result = result,
            )
          }
          lastSuccessResult = result
        }

        else ->
          throw TrailblazeException(
            message =
              buildString {
                appendLine("Unhandled Trailblaze tool ${tool::class.java.simpleName} - $tool.")
                appendLine("PlaywrightTrailblazeAgent supports:")
                appendLine("- ${PlaywrightExecutableTool::class.java.simpleName}")
                appendLine("- ${ExecutableTrailblazeTool::class.java.simpleName}")
                appendLine("- ${DelegatingTrailblazeTool::class.java.simpleName}")
                appendLine("- ${MemoryTrailblazeTool::class.java.simpleName}")
              },
          )
      }
    }
    return TrailblazeAgent.RunTrailblazeToolsResult(
      inputTools = tools,
      executedTools = toolsExecuted,
      result = lastSuccessResult,
    )
  }

  /**
   * When true, skips DOM stability in post-action settle and relies on element readiness
   * waits instead. This dramatically speeds up recording playback where DOM stability
   * never resolves on real-world SPAs (e.g., Square staging always times out at 2s per action).
   *
   * Set by the test runner when using `--use-recorded-steps`.
   */
  var skipPostActionDomStability: Boolean = false

  /**
   * Default timeout for waiting for a target element to be present before executing a tool.
   * 10 seconds handles slow page transitions (e.g., login → password form on Square staging).
   */
  private val elementReadinessTimeoutMs = 10_000.0

  companion object {
    /**
     * Reduced DOM stability timeout for recording playback post-action settle.
     *
     * DOM stability uses a MutationObserver — it's event-driven, not a blind sleep.
     * On stable pages, it resolves immediately. On busy SPAs with continuous DOM
     * mutations (analytics, animations), it acts as a ceiling that gives in-flight
     * requests (e.g., login POST ~472ms) time to complete before the next tool fires.
     */
    private const val RECORDING_DOM_STABILITY_TIMEOUT_MS = 500.0
  }

  private fun executePlaywrightToolBlocking(
    tool: PlaywrightExecutableTool,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult = runBlocking {
    val toolName = tool::class.simpleName ?: "unknown"
    TrailblazeTracer.traceSuspend("executePlaywrightTool", "tool", mapOf("tool" to toolName)) {
      // Wait for the target element to be present before executing.
      // This is critical for recording playback where there's no LLM latency between tools
      // and page transitions (e.g., email → password form) may not have completed.
      val effectiveContext = waitForElementReadiness(tool, context)

      // Capture screen state BEFORE execution for the screenshot overlay
      val preScreenState = try { browserManager.getScreenState() } catch (_: Exception) { null }

      val timeBeforeExecution = Clock.System.now()
      val result = TrailblazeTracer.traceSuspend("executeWithPlaywright:$toolName", "tool") {
        tool.executeWithPlaywright(browserManager.currentPage, effectiveContext)
      }
      val executionTimeMs =
        Clock.System.now().toEpochMilliseconds() - timeBeforeExecution.toEpochMilliseconds()

      // Enrich tool with TrailblazeNodeSelector before logging so recordings capture
      // rich selectors (ARIA role+name, CSS selector, data-testid, nth-index).
      // Uses the pre-execution screen state's trailblazeNodeTree since the tool's ref
      // was resolved against that snapshot.
      val enrichedTool = if (result.isSuccess()) {
        enrichToolWithNodeSelector(tool, effectiveContext)
      } else {
        tool
      }
      logToolExecution(enrichedTool, timeBeforeExecution, effectiveContext, result)

      // Log AgentDriverLog with pre-action screenshot for visualization
      logAgentDriverAction(tool, result, preScreenState, executionTimeMs, timeBeforeExecution, effectiveContext)

      if (result.isSuccess()) {
        TrailblazeTracer.trace("postActionSettle", "tool") {
          if (skipPostActionDomStability) {
            // Recording playback: use reduced DOM stability timeout. DOM stability is
            // event-driven (MutationObserver) — resolves early on stable pages, and the
            // 500ms ceiling gives form submissions (e.g., login POST ~472ms) time to
            // complete before the next tool fires. Without this, rapid tool execution
            // can navigate away before in-flight requests finish.
            browserManager.waitForPageReady(domStabilityTimeoutMs = RECORDING_DOM_STABILITY_TIMEOUT_MS)
          } else {
            browserManager.waitForPageReady()
          }
        }

        TrailblazeTracer.traceSuspend("logSnapshot", "tool") {
          try {
            val snapshotResult =
              TakeSnapshotTool(
                screenName = "web_action_${toolName.lowercase()}",
                description = "Auto-captured after Playwright tool execution.",
              ).execute(effectiveContext)
            if (!snapshotResult.isSuccess()) {
              Console.log("  [warn] Snapshot capture failed for tool: $toolName")
            }
          } catch (e: Exception) {
            Console.log("  [warn] Snapshot logging failed: ${e.message}")
          }
        }
      }

      if (tool is PlaywrightNativeRequestDetailsTool && result.isSuccess()) {
        browserManager.requestDetails(tool.include.toSet())
      }

      result
    }
  }

  /**
   * Enriches a [PlaywrightExecutableTool] with a [TrailblazeNodeSelector] by mapping
   * the tool's element ref to a node in the screen state's [trailblazeNodeTree].
   *
   * This is the bridge from Playwright's ref-based tools to the rich selector system:
   * 1. Resolve the tool's ref (e.g., "e5") to an [ElementRef] (ariaDescriptor + nthIndex)
   * 2. Find the matching node in the [TrailblazeNode] tree by descriptor + nthIndex
   * 3. Generate a [TrailblazeNodeSelector] via [TrailblazeNodeSelectorGenerator]
   * 4. Return a copy of the tool with the selector attached
   *
   * Returns the original tool unchanged if the ref can't be resolved or no tree is available.
   */
  private fun enrichToolWithNodeSelector(
    tool: PlaywrightExecutableTool,
    context: TrailblazeToolExecutionContext,
  ): PlaywrightExecutableTool {
    val ref = tool.targetRef ?: return tool
    val screenState = context.screenState as? PlaywrightScreenState ?: return tool
    val tree = screenState.trailblazeNodeTree ?: return tool

    return try {
      // Resolve the ref to an ElementRef (ariaDescriptor + nthIndex)
      val elementRef = screenState.resolveElementId(ref)

      // Find the matching node in the TrailblazeNode tree
      val targetNode = if (elementRef != null) {
        // Match by ariaDescriptor + nthIndex (from element ID mapping)
        tree.findFirst { node ->
          val detail = node.driverDetail as? DriverNodeDetail.Web ?: return@findFirst false
          detail.ariaDescriptor == elementRef.descriptor && detail.nthIndex == elementRef.nthIndex
        }
      } else {
        // Direct ARIA descriptor or CSS selector — match by descriptor
        tree.findFirst { node ->
          val detail = node.driverDetail as? DriverNodeDetail.Web ?: return@findFirst false
          detail.ariaDescriptor == ref ||
            detail.cssSelector == ref.removePrefix("css=") ||
            detail.dataTestId == ref.removePrefix("css=[data-testid=\"")?.removeSuffix("\"]")
        }
      }

      if (targetNode != null) {
        val selector = TrailblazeNodeSelectorGenerator.findBestSelector(tree, targetNode)
        tool.withNodeSelector(selector)
      } else {
        tool
      }
    } catch (e: Exception) {
      Console.log("WARNING: TrailblazeNodeSelector generation failed for ref='$ref': ${e.message}")
      tool
    }
  }

  /**
   * Waits for the tool's target element to be present on the page, then returns
   * an updated context with a fresh screen state (so element ID mappings are current).
   *
   * During recording playback, tools fire in rapid succession without LLM latency.
   * After a page transition (e.g., clicking "Continue" on a login form), the next
   * tool's target element may not exist yet, and stale element ID mappings (e.g., "e4")
   * would resolve to the wrong element. This method:
   *
   * 1. Uses the tool's [PlaywrightExecutableTool.elementDescriptor] ARIA descriptor
   *    (e.g., "textbox Password") to wait for the element to appear
   * 2. Captures a fresh screen state so positional element IDs map correctly
   *
   * For tools without an element descriptor (e.g., navigate, wait), or in AI mode
   * where the screen state is already fresh, this is a no-op that returns the
   * original context.
   */
  private fun waitForElementReadiness(
    tool: PlaywrightExecutableTool,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolExecutionContext {
    val descriptor = tool.elementDescriptor
    if (descriptor.isNullOrBlank()) return context

    val result = TrailblazeTracer.trace(
      "waitForElement",
      "tool",
      mapOf("element" to descriptor),
    ) {
      try {
        val locator = PlaywrightAriaSnapshot.resolveRef(browserManager.currentPage, descriptor)
        locator.waitFor(
          com.microsoft.playwright.Locator.WaitForOptions()
            .setState(com.microsoft.playwright.options.WaitForSelectorState.VISIBLE)
            .setTimeout(elementReadinessTimeoutMs),
        )
        Console.log("  [ready] Element '$descriptor' is present")
        "ok"
      } catch (_: TimeoutError) {
        Console.log("  [ready] Element '$descriptor' not found after ${elementReadinessTimeoutMs.toLong()}ms")
        "timeout"
      } catch (e: Exception) {
        Console.log("  [ready] Element '$descriptor' readiness check failed: ${e::class.simpleName}: ${e.message}")
        "error"
      }
    }

    // Refresh screen state so element ID mappings reflect the current DOM.
    // getScreenState() internally calls waitForPageReady() with 2000ms DOM stability.
    // On SPAs like Square's login, continuous DOM mutations (resource loads, script
    // execution, reCAPTCHA initialization, analytics) keep the MutationObserver active
    // for the full 2000ms, which is enough time for third-party scripts to initialize.
    // On simpler pages with no mutations, DOM stability resolves immediately.
    if (result == "ok") {
      val freshScreenState = browserManager.getScreenState()
      return TrailblazeToolExecutionContext(
        screenState = freshScreenState,
        traceId = context.traceId,
        trailblazeDeviceInfo = context.trailblazeDeviceInfo,
        sessionProvider = context.sessionProvider,
        screenStateProvider = context.screenStateProvider,
        trailblazeLogger = context.trailblazeLogger,
        memory = context.memory,
        maestroTrailblazeAgent = context.maestroTrailblazeAgent,
        workingDirectory = context.workingDirectory,
      )
    }

    return context
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
   * Resolves element bounding box coordinates via Playwright's locator API for click/hover tools.
   */
  private fun logAgentDriverAction(
    tool: PlaywrightExecutableTool,
    result: TrailblazeToolResult,
    preScreenState: ScreenState?,
    executionTimeMs: Long,
    startTime: kotlinx.datetime.Instant,
    context: TrailblazeToolExecutionContext,
  ) {
    if (preScreenState == null) return
    try {
      val session = sessionProvider.invoke()
      val screenshotFilename = if (preScreenState.screenshotBytes != null) {
        trailblazeLogger.logScreenState(session, preScreenState)
      } else {
        null
      }

      val action = mapToolToAgentDriverAction(tool, result, context)

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
        traceId = context.traceId,
      )
      trailblazeLogger.log(session, log)
    } catch (e: Exception) {
      Console.log("Warning: Failed to log AgentDriverAction: ${e.message}")
    }
  }

  /**
   * Maps a Playwright tool to an [AgentDriverAction] for unified logging visualization.
   * Resolves element coordinates via `boundingBox()` for click, hover, and verify tools.
   */
  private fun mapToolToAgentDriverAction(
    tool: PlaywrightExecutableTool,
    result: TrailblazeToolResult,
    context: TrailblazeToolExecutionContext,
  ): AgentDriverAction {
    val succeeded = result.isSuccess()
    return when (tool) {
      is PlaywrightNativeClickTool -> {
        val (x, y) = resolveElementCenter(tool.ref, context)
        AgentDriverAction.TapPoint(x = x, y = y)
      }
      is PlaywrightNativeHoverTool -> {
        val (x, y) = resolveElementCenter(tool.ref, context)
        AgentDriverAction.TapPoint(x = x, y = y)
      }
      is PlaywrightNativeTypeTool ->
        AgentDriverAction.EnterText(text = tool.text)
      is PlaywrightNativeScrollTool ->
        AgentDriverAction.Scroll(forward = tool.direction == PlaywrightNativeScrollTool.ScrollDirection.UP)
      is PlaywrightNativeNavigateTool -> when (tool.action) {
        PlaywrightNativeNavigateTool.NavigationAction.BACK -> AgentDriverAction.BackPress
        else -> AgentDriverAction.LaunchApp(appId = tool.url)
      }
      is PlaywrightNativePressKeyTool ->
        AgentDriverAction.OtherAction(AgentActionType.WEB_ACTION)
      is PlaywrightNativeSelectOptionTool -> {
        val (x, y) = resolveElementCenter(tool.ref, context)
        AgentDriverAction.TapPoint(x = x, y = y)
      }
      is PlaywrightNativeVerifyTextVisibleTool -> {
        val (x, y) = resolveTextCenter(tool.text)
        AgentDriverAction.AssertCondition(
          conditionDescription = "Verify text visible: ${tool.text}",
          x = x, y = y,
          isVisible = true,
          textToDisplay = tool.text,
          succeeded = succeeded,
        )
      }
      is PlaywrightNativeVerifyElementVisibleTool -> {
        val (x, y) = resolveElementCenter(tool.ref, context)
        AgentDriverAction.AssertCondition(
          conditionDescription = "Verify element visible: ${tool.element.ifBlank { tool.ref }}",
          x = x, y = y,
          isVisible = true,
          succeeded = succeeded,
        )
      }
      is PlaywrightNativeVerifyListVisibleTool -> {
        val (x, y) = resolveElementCenter(tool.ref, context)
        AgentDriverAction.AssertCondition(
          conditionDescription = "Verify list: ${tool.element.ifBlank { tool.ref }}",
          x = x, y = y,
          isVisible = true,
          succeeded = succeeded,
        )
      }
      is PlaywrightNativeVerifyValueTool -> {
        val (x, y) = resolveElementCenter(tool.ref, context)
        AgentDriverAction.AssertCondition(
          conditionDescription = "Verify ${tool.type.name.lowercase()}: ${tool.element.ifBlank { tool.ref }} = '${tool.expected}'",
          x = x, y = y,
          isVisible = true,
          succeeded = succeeded,
        )
      }
      is PlaywrightNativeWaitTool ->
        AgentDriverAction.WaitForSettle(timeoutMs = tool.seconds * 1000L)
      else -> AgentDriverAction.OtherAction(AgentActionType.WEB_ACTION)
    }
  }

  /**
   * Resolves an element ref to center coordinates via Playwright's `boundingBox()`.
   * Uses [PlaywrightExecutableTool.resolveRef] which handles element IDs (e.g., "e11"),
   * CSS selectors, and ARIA descriptors — matching the same resolution path as the tools.
   * Returns (0, 0) if the element can't be found or doesn't have a bounding box.
   */
  private fun resolveElementCenter(
    ref: String,
    context: TrailblazeToolExecutionContext,
  ): Pair<Int, Int> {
    return try {
      val locator = PlaywrightExecutableTool.resolveRef(browserManager.currentPage, ref, context)
      val box = locator.first().boundingBox()
      if (box != null) {
        val centerX = (box.x + box.width / 2).toInt()
        val centerY = (box.y + box.height / 2).toInt()
        centerX to centerY
      } else {
        0 to 0
      }
    } catch (_: Exception) {
      0 to 0
    }
  }

  /**
   * Resolves text content to center coordinates via Playwright's `getByText().boundingBox()`.
   * Returns (0, 0) if the text can't be found on the page.
   */
  private fun resolveTextCenter(text: String): Pair<Int, Int> {
    return try {
      val locator = browserManager.currentPage.getByText(text)
      val box = locator.first().boundingBox()
      if (box != null) {
        val centerX = (box.x + box.width / 2).toInt()
        val centerY = (box.y + box.height / 2).toInt()
        centerX to centerY
      } else {
        0 to 0
      }
    } catch (_: Exception) {
      0 to 0
    }
  }
}
