package xyz.block.trailblaze.host

import maestro.Platform
import maestro.orchestra.Command
import maestro.orchestra.TapOnPointV2Command
import xyz.block.trailblaze.MaestroTrailblazeAgent
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.api.TrailblazeNodeSelectorResolver
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.host.devices.TrailblazeConnectedDevice
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.model.NodeSelectorMode
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
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
  nodeSelectorMode: NodeSelectorMode = NodeSelectorMode.FORCE_LEGACY,
) : MaestroTrailblazeAgent(
  trailblazeLogger = trailblazeLogger,
  trailblazeDeviceInfoProvider = trailblazeDeviceInfoProvider,
  sessionProvider = sessionProvider,
  nodeSelectorMode = nodeSelectorMode,
) {

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
    val tree = getCurrentTrailblazeNodeTree() ?: return null
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
    timeoutMs: Long,
    traceId: TraceId?,
  ): TrailblazeToolResult? {
    val tree = getCurrentTrailblazeNodeTree() ?: return null
    return when (TrailblazeNodeSelectorResolver.resolve(tree, nodeSelector)) {
      is TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch,
      is TrailblazeNodeSelectorResolver.ResolveResult.MultipleMatches -> TrailblazeToolResult.Success()
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
    timeoutMs: Long,
    traceId: TraceId?,
  ): TrailblazeToolResult? {
    val tree = getCurrentTrailblazeNodeTree() ?: return null
    return when (TrailblazeNodeSelectorResolver.resolve(tree, nodeSelector)) {
      is TrailblazeNodeSelectorResolver.ResolveResult.NoMatch -> TrailblazeToolResult.Success()
      else -> null // fall back to Maestro (has timeout to wait for disappearance)
    }
  }

  /**
   * Queries the Maestro driver directly for the current iOS view hierarchy and converts
   * to a [TrailblazeNode] tree. This is a lightweight operation (no screenshot, no
   * stabilization) — just a hierarchy query and conversion.
   *
   * Returns null for non-iOS devices or if the hierarchy is empty.
   */
  private fun getCurrentTrailblazeNodeTree(): TrailblazeNode? {
    val impl = maestroHostRunner as? MaestroHostRunnerImpl ?: return null
    val driver = impl.loggingDriver
    if (driver.deviceInfo().platform != Platform.IOS) return null
    val rawTree = driver.contentDescriptor(false)
    return rawTree.toTrailblazeNodeIosMaestro()
  }

  /**
   * Resolves a [TrailblazeNodeSelector] against the tree, returning the matched node
   * only if exactly one match is found.
   */
  private fun resolveSingleMatch(
    tree: TrailblazeNode,
    selector: TrailblazeNodeSelector,
  ): TrailblazeNode? = when (val result = TrailblazeNodeSelectorResolver.resolve(tree, selector)) {
    is TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch -> result.node
    else -> null
  }
}
