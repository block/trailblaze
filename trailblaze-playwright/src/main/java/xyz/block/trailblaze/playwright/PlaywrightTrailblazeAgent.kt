package xyz.block.trailblaze.playwright

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import xyz.block.trailblaze.BaseTrailblazeAgent
import xyz.block.trailblaze.api.AgentActionType
import xyz.block.trailblaze.api.AgentDriverAction
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeNodeSelectorGenerator
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.logToolExecution
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.network.InflightRequestTracker
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

import xyz.block.trailblaze.toolcalls.DelegatingTrailblazeTool
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.commands.LaunchAppTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.memory.MemoryTrailblazeTool
import xyz.block.trailblaze.toolcalls.isSuccess
import xyz.block.trailblaze.tracing.TrailblazeTracer
import xyz.block.trailblaze.utils.NoOpElementComparator
import xyz.block.trailblaze.util.Console

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
  override val trailblazeToolRepo: TrailblazeToolRepo? = null,
  /**
   * Resolves the on-disk directory for a given session. Threaded through to
   * tools that need to write artifacts (e.g. network capture). Null in
   * non-host contexts (e.g. tests without a logs repo).
   */
  val sessionDirProvider: ((SessionId) -> java.io.File)? = null,
  /**
   * Engine-agnostic in-flight request tracker. The Playwright capture engine
   * updates this on every observed request start/end so future cross-platform
   * idling tools can wait on network quiet without engine-specific hooks.
   * Defaults to a fresh per-agent instance so tools can rely on a non-null
   * tracker even when the host doesn't supply one explicitly.
   */
  val inflightRequestTracker: InflightRequestTracker = InflightRequestTracker(),
) : BaseTrailblazeAgent() {

  /**
   * Working directory for resolving relative file paths in tools.
   * Set to the trail file's parent directory before running a trail.
   */
  var workingDirectory: java.io.File? = null

  val screenStateProvider: () -> ScreenState = { browserManager.getScreenState() }

  // buildKoogToolExecutionContext now lives on BaseTrailblazeAgent (shared by every driver agent),
  // delegating to this agent's buildExecutionContext override.

  override fun buildExecutionContext(
    traceId: TraceId,
    screenState: ScreenState?,
    screenStateProvider: (() -> ScreenState)?,
  ): TrailblazeToolExecutionContext {
    val trailblazeDeviceInfo = trailblazeDeviceInfoProvider()
    lateinit var context: TrailblazeToolExecutionContext
    context = TrailblazeToolExecutionContext(
      screenState = screenState,
      traceId = traceId,
      trailblazeDeviceInfo = trailblazeDeviceInfo,
      sessionProvider = sessionProvider,
      screenStateProvider = screenStateProvider ?: this.screenStateProvider,
      trailblazeLogger = trailblazeLogger,
      memory = memory,
      maestroTrailblazeAgent = null,
      nestedToolExecutor = { nestedTool ->
        runTrailblazeTools(
          tools = listOf(nestedTool),
          traceId = context.traceId,
          screenState = context.screenState,
          elementComparator = NoOpElementComparator,
          screenStateProvider = context.screenStateProvider,
        ).result
      },
      workingDirectory = workingDirectory,
      sessionDirProvider = sessionDirProvider,
      inflightRequestTracker = inflightRequestTracker,
      // Threads the agent's tool repo through so Kotlin tools composing framework
      // tools via `ctx.invokeFrameworkTool(...)` resolve them by name. See the same
      // wiring on `MaestroTrailblazeAgent.buildExecutionContext` — without it, the
      // bridge throws "toolRepo not wired" on every Kotlin-side call site in a
      // Playwright-driven session.
      toolRepo = trailblazeToolRepo,
    )
    return context
  }

  override fun executeTool(
    tool: TrailblazeTool,
    context: TrailblazeToolExecutionContext,
    toolsExecuted: MutableList<TrailblazeTool>,
  ): TrailblazeToolResult {
    return when (tool) {
      is PlaywrightExecutableTool -> {
        toolsExecuted.add(tool)
        executePlaywrightToolBlocking(tool, context)
      }
      is ExecutableTrailblazeTool -> {
        toolsExecuted.add(tool)
        executeToolBlocking(tool, context)
      }
      is DelegatingTrailblazeTool -> {
        executeDelegatingTool(tool, context, toolsExecuted) { mappedTool ->
          if (mappedTool is PlaywrightExecutableTool) {
            executePlaywrightToolBlocking(mappedTool, context)
          } else {
            executeToolBlocking(mappedTool, context)
          }
        }
      }
      // Map cross-platform Maestro tools to their Playwright equivalents
      is LaunchAppTrailblazeTool -> {
        // launchApp is a no-op on web — the browser is already open
        toolsExecuted.add(tool)
        TrailblazeToolResult.Success(message = "Browser already open (launchApp is a no-op on web)")
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

  private fun executePlaywrightToolBlocking(
    tool: PlaywrightExecutableTool,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult = runBlocking {
    val toolName = tool::class.simpleName ?: "unknown"
    TrailblazeTracer.traceSuspend("executePlaywrightTool", "tool", mapOf("tool" to toolName)) {
      // Pre-action screenshot for the agent-driver overlay. captureScreenStateForLogging
      // does NOT trigger settling — that's the job of the previous tool's dispatchAndAwaitSettle.
      val preScreenState = try { browserManager.captureScreenStateForLogging() } catch (_: Exception) { null }

      // Resolve element coordinates BEFORE execution for logging. Clicks may navigate
      // away, after which the element no longer exists on the page.
      val preResolvedCenter = resolveToolCenter(tool, context)

      // Log AgentDriverLog with pre-action screenshot BEFORE execution so the
      // timeline shows the tap coordinates on the pre-click screenshot.
      val timeBeforeExecution = Clock.System.now()
      logAgentDriverAction(tool, preScreenState, timeBeforeExecution, preResolvedCenter, context.traceId)

      // Run the tool inside the request-tracking settle. Playwright's actionability
      // checks (visible/stable/enabled/editable) inside locator.fill/click/hover/...
      // gate per-tool readiness; dispatchAndAwaitSettle handles the post-action settle
      // — see PlaywrightPageManager.dispatchAndAwaitSettle for the strategy.
      val result = browserManager.dispatchAndAwaitSettle {
        TrailblazeTracer.traceSuspend("executeWithPlaywright:$toolName", "tool") {
          tool.executeWithPlaywright(browserManager.currentPage, context)
        }
      }

      // Enrich tool with TrailblazeNodeSelector before logging so recordings capture
      // rich selectors (ARIA role+name, CSS selector, data-testid, nth-index).
      //
      // We try a fresh post-action screen state first. With element-attached auto-wait
      // inside validateAndResolveRef, an element may not be in the DOM yet at pre-action
      // capture but is present after the tool completes — e.g. dashboard tabs that render
      // 1-2s after a login navigation's `load` event. Fall back to the pre-action context
      // for clicks that navigate away (the clicked element is gone from the new page).
      //
      // Gate the post-action capture on the tool having something to enrich — `web_navigate`,
      // `web_wait`, `web_snapshot`, and other no-element tools shouldn't pay the ARIA-snapshot
      // cost twice per execution.
      val enrichedTool = if (result.isSuccess()) {
        val needsEnrichment = tool.targetRef != null || tool.targetNodeSelector != null
        if (needsEnrichment) {
          val postScreenState = try { browserManager.captureScreenStateForLogging() } catch (_: Exception) { null }
          val postEnriched = postScreenState?.let { enrichToolWithNodeSelector(tool, context, it) }
          if (postEnriched != null && postEnriched !== tool) postEnriched
          else enrichToolWithNodeSelector(tool, context, null)
        } else {
          tool
        }
      } else {
        tool
      }
      // Playwright tools drive a browser owned by the host JVM — flag the dispatch as
      // host-side so session viewers can distinguish it from RPC-routed-to-device dispatches.
      logToolExecution(enrichedTool, timeBeforeExecution, context, result, dispatchedHostSide = true)

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
    screenStateOverride: ScreenState? = null,
  ): PlaywrightExecutableTool {
    val ref = tool.targetRef ?: return tool
    val screenState = (screenStateOverride ?: context.screenState) as? PlaywrightScreenState ?: return tool
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

  private fun executeToolBlocking(
    tool: ExecutableTrailblazeTool,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult = runBlocking {
    val timeBeforeExecution = Clock.System.now()
    val result = tool.execute(context)
    // Same rationale as the executeWithPlaywright path — host JVM, in-process browser.
    logToolExecution(tool, timeBeforeExecution, context, result, dispatchedHostSide = true)
    result
  }

  /**
   * Logs an [AgentDriverLog] with the pre-action screenshot for unified visualization.
   * Resolves element bounding box coordinates via Playwright's locator API for click/hover tools.
   */
  private fun logAgentDriverAction(
    tool: PlaywrightExecutableTool,
    preScreenState: ScreenState?,
    timestamp: kotlinx.datetime.Instant,
    preResolvedCenter: Pair<Int, Int>,
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

      val action = mapToolToAgentDriverAction(tool, preResolvedCenter)

      val log = TrailblazeLog.AgentDriverLog(
        viewHierarchy = preScreenState.viewHierarchy,
        trailblazeNodeTree = preScreenState.trailblazeNodeTree,
        screenshotFile = screenshotFilename,
        action = action,
        durationMs = 0,
        timestamp = timestamp,
        session = session.sessionId,
        deviceWidth = preScreenState.deviceWidth,
        deviceHeight = preScreenState.deviceHeight,
        traceId = traceId,
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
    preResolvedCenter: Pair<Int, Int>,
  ): AgentDriverAction {
    val (x, y) = preResolvedCenter
    return when (tool) {
      is PlaywrightNativeClickTool ->
        AgentDriverAction.TapPoint(x = x, y = y)
      is PlaywrightNativeHoverTool ->
        AgentDriverAction.TapPoint(x = x, y = y)
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
      is PlaywrightNativeSelectOptionTool ->
        AgentDriverAction.TapPoint(x = x, y = y)
      is PlaywrightNativeVerifyTextVisibleTool ->
        AgentDriverAction.AssertCondition(
          conditionDescription = "Verify text visible: ${tool.text}",
          x = x, y = y,
          isVisible = true,
          textToDisplay = tool.text,
          succeeded = true,
        )
      is PlaywrightNativeVerifyElementVisibleTool ->
        AgentDriverAction.AssertCondition(
          conditionDescription = "Verify element visible: ${PlaywrightExecutableTool.describeTarget(tool.nodeSelector, tool.ref)}",
          x = x, y = y,
          isVisible = true,
          succeeded = true,
        )
      is PlaywrightNativeVerifyListVisibleTool ->
        AgentDriverAction.AssertCondition(
          conditionDescription = "Verify list: ${PlaywrightExecutableTool.describeTarget(tool.nodeSelector, tool.ref)}",
          x = x, y = y,
          isVisible = true,
          succeeded = true,
        )
      is PlaywrightNativeVerifyValueTool ->
        AgentDriverAction.AssertCondition(
          conditionDescription = "Verify ${tool.type.name.lowercase()}: ${PlaywrightExecutableTool.describeTarget(tool.nodeSelector, tool.ref)} = '${tool.expected}'",
          x = x, y = y,
          isVisible = true,
          succeeded = true,
        )
      is PlaywrightNativeWaitTool ->
        AgentDriverAction.WaitForSettle(timeoutMs = tool.seconds * 1000L)
      else -> AgentDriverAction.OtherAction(AgentActionType.WEB_ACTION)
    }
  }

  /**
   * Resolves center coordinates for a tool's target element BEFORE execution.
   * This must happen pre-execution because clicks can cause navigation, after which
   * the target element no longer exists on the page.
   * Returns (0, 0) if the tool has no target or the element can't be resolved.
   */
  private fun resolveToolCenter(
    tool: PlaywrightExecutableTool,
    context: TrailblazeToolExecutionContext,
  ): Pair<Int, Int> {
    return try {
      val (ref, nodeSelector) = when (tool) {
        is PlaywrightNativeClickTool -> tool.ref to tool.nodeSelector
        is PlaywrightNativeHoverTool -> tool.ref to tool.nodeSelector
        is PlaywrightNativeSelectOptionTool -> tool.ref to tool.nodeSelector
        is PlaywrightNativeVerifyElementVisibleTool -> tool.ref to tool.nodeSelector
        is PlaywrightNativeVerifyListVisibleTool -> tool.ref to tool.nodeSelector
        is PlaywrightNativeVerifyValueTool -> tool.ref to tool.nodeSelector
        is PlaywrightNativeVerifyTextVisibleTool -> null to null
        else -> null to null
      }
      val locator = when {
        ref != null || nodeSelector != null -> {
          val (resolved, _) = PlaywrightExecutableTool.validateAndResolveRef(
            browserManager.currentPage, ref, "", context, nodeSelector,
          )
          resolved ?: return 0 to 0
        }
        tool is PlaywrightNativeVerifyTextVisibleTool ->
          browserManager.currentPage.getByText(tool.text)
        else -> return 0 to 0
      }
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
