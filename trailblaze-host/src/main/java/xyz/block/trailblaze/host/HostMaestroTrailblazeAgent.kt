package xyz.block.trailblaze.host

import java.io.File
import kotlinx.datetime.Clock
import maestro.device.Platform
import maestro.orchestra.Command
import maestro.orchestra.TapOnPointV2Command
import xyz.block.trailblaze.MaestroTrailblazeAgent
import xyz.block.trailblaze.api.AgentDriverAction
import xyz.block.trailblaze.api.MigrationScreenState
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TargetTemplateContext
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.api.TrailblazeNodeSelectorResolver
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.host.devices.TrailblazeConnectedDevice
import xyz.block.trailblaze.host.recording.AxeTreeOverlay
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.model.NodeSelectorMode
import xyz.block.trailblaze.model.ResolvedTarget
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.viewmatcher.matching.toTrailblazeNodeIosMaestro

/**
 * Host-mode Maestro agent for executing commands on connected devices.
 * Uses stateless logger with explicit session management.
 */
class HostMaestroTrailblazeAgent(
  private val maestroHostRunner: MaestroHostRunner,
  trailblazeLogger: TrailblazeLogger,
  trailblazeDeviceInfoProvider: () -> TrailblazeDeviceInfo,
  sessionProvider: TrailblazeSessionProvider,
  nodeSelectorMode: NodeSelectorMode = NodeSelectorMode.DEFAULT,
  trailblazeToolRepo: TrailblazeToolRepo? = null,
  resolvedTarget: ResolvedTarget? = null,
  appId: String? = null,
  sessionDirProvider: ((SessionId) -> File)? = null,
) : MaestroTrailblazeAgent(
  trailblazeLogger = trailblazeLogger,
  trailblazeDeviceInfoProvider = trailblazeDeviceInfoProvider,
  sessionProvider = sessionProvider,
  nodeSelectorMode = nodeSelectorMode,
  trailblazeToolRepo = trailblazeToolRepo,
  resolvedTarget = resolvedTarget,
  appId = appId,
  sessionDirProvider = sessionDirProvider,
) {

  /**
   * Template context surfaced to every internal [TrailblazeNodeSelectorResolver.resolve]
   * call so selectors carrying `{{target.appId}}` placeholders expand correctly. Null when
   * the agent wasn't constructed with a target (target-agnostic tests / ad-hoc runs).
   */
  private val templateContext: TargetTemplateContext? = resolvedTarget?.let {
    TargetTemplateContext(appId = appId, appIds = it.appIds)
  }

  val connectedDevice: TrailblazeConnectedDevice by lazy {
    (maestroHostRunner as MaestroHostRunnerImpl).connectedDevice
  }

  override suspend fun executeMaestroCommands(commands: List<Command>, traceId: TraceId?): TrailblazeToolResult =
    maestroHostRunner.runMaestroCommands(
      commands = commands,
      traceId = traceId,
    )

  /**
   * Resolves the [nodeSelector] against the current iOS TrailblazeNode tree and taps
   * at the matched node's center point. Falls back to Maestro Orchestra if:
   * - The device is not iOS (tree conversion is iOS-specific)
   * - The tree cannot be obtained
   * - The selector doesn't resolve to a single match
   */
  override suspend fun executeNodeSelectorTap(
    nodeSelector: TrailblazeNodeSelector,
    longPress: Boolean,
    traceId: TraceId?,
  ): TrailblazeToolResult? {
    val tree = getCurrentTrailblazeNodeTree(nodeSelector) ?: return null
    val node = resolveSingleMatch(tree, nodeSelector) ?: return null
    val center = node.centerPoint() ?: return null
    return executeMaestroCommands(
      listOf(TapOnPointV2Command(point = "${center.first},${center.second}", longPress = longPress)),
      traceId,
    )
  }

  /**
   * Resolves the [nodeSelector] against the current iOS TrailblazeNode tree.
   * Any match (single or multiple) means the element is visible.
   * Falls back to Maestro Orchestra on no match or if the tree is unavailable.
   */
  override suspend fun executeNodeSelectorAssertVisible(
    nodeSelector: TrailblazeNodeSelector,
    timeoutMs: Long?,
    traceId: TraceId?,
  ): TrailblazeToolResult? {
    val tree = getCurrentTrailblazeNodeTree(nodeSelector) ?: return null
    return when (val result = TrailblazeNodeSelectorResolver.resolve(tree, nodeSelector, templateContext)) {
      is TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch -> {
        logAssertScreenState(nodeSelector = nodeSelector, matchedNode = result.node, isVisible = true)
        TrailblazeToolResult.Success()
      }
      // For multiple matches, mark the first resolved node — consistent with how
      // verifyTextEquality / resolveToCenter pick a representative from a match list — rather
      // than defaulting the marker to screen center.
      is TrailblazeNodeSelectorResolver.ResolveResult.MultipleMatches -> {
        logAssertScreenState(nodeSelector = nodeSelector, matchedNode = result.nodes.firstOrNull(), isVisible = true)
        TrailblazeToolResult.Success()
      }
      is TrailblazeNodeSelectorResolver.ResolveResult.NoMatch -> null // fall back to Maestro (has retry/timeout)
    }
  }

  /**
   * Resolves the [nodeSelector] against the current iOS TrailblazeNode tree.
   * No matches means the element is not visible (success).
   * Falls back to Maestro Orchestra if matches exist (Maestro has timeout to wait for disappearance).
   */
  override suspend fun executeNodeSelectorAssertNotVisible(
    nodeSelector: TrailblazeNodeSelector,
    timeoutMs: Long?,
    traceId: TraceId?,
  ): TrailblazeToolResult? {
    val tree = getCurrentTrailblazeNodeTree(nodeSelector) ?: return null
    return when (TrailblazeNodeSelectorResolver.resolve(tree, nodeSelector, templateContext)) {
      is TrailblazeNodeSelectorResolver.ResolveResult.NoMatch -> {
        logAssertScreenState(nodeSelector = nodeSelector, matchedNode = null, isVisible = false)
        TrailblazeToolResult.Success()
      }
      else -> null // fall back to Maestro (has timeout to wait for disappearance)
    }
  }

  /**
   * Emits an [TrailblazeLog.AgentDriverLog] carrying a screenshot for a passing node-selector
   * assertion, so the assert renders a frame in the Storyboard/Timeline just like tap/swipe do.
   *
   * The host node-selector assert paths above resolve entirely from the lightweight tree
   * ([getCurrentTrailblazeNodeTree], no screenshot) and return [TrailblazeToolResult.Success]
   * without logging anything. Taps/swipes go through [xyz.block.trailblaze.android.maestro.LoggingDriver],
   * which always logs an `AgentDriverLog` with a screenshot; the Maestro `Driver` interface has no
   * assert method, so a passing assert produced no screenshot and showed no frame on iOS (while
   * Android's accessibility runner logs one for every action, asserts included). This closes that
   * gap by capturing a full [ScreenState] on the success path and logging the same
   * [AgentDriverAction.AssertCondition] the Android/Maestro-fallback paths emit.
   */
  private fun logAssertScreenState(
    nodeSelector: TrailblazeNodeSelector,
    matchedNode: TrailblazeNode?,
    isVisible: Boolean,
  ) {
    try {
      val impl = maestroHostRunner as? MaestroHostRunnerImpl ?: return
      val session = sessionProvider.invoke()
      val screenState: ScreenState = impl.screenStateProvider.invoke()
      val center = matchedNode?.centerPoint()
      val screenshotFilename = if (screenState.screenshotBytes != null) {
        trailblazeLogger.logScreenState(session, screenState)
      } else {
        null
      }
      val log = TrailblazeLog.AgentDriverLog(
        viewHierarchy = screenState.viewHierarchy,
        trailblazeNodeTree = screenState.trailblazeNodeTree,
        // Migration-mode side tree (see MigrationScreenState). Carried on every log type with a
        // `trailblazeNodeTree` so migrate-trail's cursor-scan fallback produces accessibility-shape
        // selectors regardless of which log it lands on.
        driverMigrationTreeNode = (screenState as? MigrationScreenState)?.driverMigrationTreeNode,
        screenshotFile = screenshotFilename,
        action = AgentDriverAction.AssertCondition(
          conditionDescription = nodeSelector.description(),
          x = center?.first ?: (screenState.deviceWidth / 2),
          y = center?.second ?: (screenState.deviceHeight / 2),
          isVisible = isVisible,
          textToDisplay = if (isVisible) null else nodeSelector.description(),
          succeeded = true,
        ),
        durationMs = 0,
        timestamp = Clock.System.now(),
        session = session.sessionId,
        deviceWidth = screenState.deviceWidth,
        deviceHeight = screenState.deviceHeight,
      )
      trailblazeLogger.log(session, log)
    } catch (t: Throwable) {
      Console.log("[HostMaestroTrailblazeAgent] Failed to log assert screenshot: ${t.message}")
    }
  }

  /**
   * Queries the Maestro driver directly for the current iOS view hierarchy and converts to a
   * [TrailblazeNode] tree. When [nodeSelector] was recorded against overlay-provided (AXe-dialect)
   * content, the live capture is re-enriched with AXe the same way the record path was — otherwise
   * a selector targeting bottom-sheet content XCUITest drops would resolve against nothing. A
   * Maestro-dialect selector skips AXe, so a trail that never touched dropped content pays no cost.
   *
   * Returns null for non-iOS devices or if the hierarchy is empty.
   */
  private suspend fun getCurrentTrailblazeNodeTree(nodeSelector: TrailblazeNodeSelector): TrailblazeNode? {
    val impl = maestroHostRunner as? MaestroHostRunnerImpl ?: return null
    val driver = impl.loggingDriver
    val deviceInfo = driver.deviceInfo()
    if (deviceInfo.platform != Platform.IOS) return null
    val maestroTree = driver.contentDescriptor(false).toTrailblazeNodeIosMaestro()
    val udid = impl.iosUdid
    return if (udid != null && AxeTreeOverlay.selectorReferencesIosAxe(nodeSelector)) {
      AxeTreeOverlay.enrichIosTree(
        maestroTree = maestroTree,
        udid = udid,
        screenWidth = deviceInfo.widthGrid,
        screenHeight = deviceInfo.heightGrid,
      )
    } else {
      maestroTree
    }
  }

  /**
   * Resolves a [TrailblazeNodeSelector] against the tree, returning the matched node
   * only if exactly one match is found.
   */
  private fun resolveSingleMatch(
    tree: TrailblazeNode,
    selector: TrailblazeNodeSelector,
  ): TrailblazeNode? = when (val result = TrailblazeNodeSelectorResolver.resolve(tree, selector, templateContext)) {
    is TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch -> result.node
    else -> null
  }
}
