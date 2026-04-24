package xyz.block.trailblaze.host.axe

import maestro.orchestra.Command
import xyz.block.trailblaze.MaestroTrailblazeAgent
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.model.NodeSelectorMode
import xyz.block.trailblaze.toolcalls.DelegatingTrailblazeTool
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.isSuccess

/**
 * iOS-Simulator agent that drives the device natively through the AXe CLI instead of
 * Maestro/XCUITest.
 *
 * Parallel to [xyz.block.trailblaze.android.accessibility.AccessibilityTrailblazeAgent] on
 * Android: it extends [MaestroTrailblazeAgent] so the existing tool catalog and
 * `runMaestroCommands` plumbing continue to work, but overrides the five hot-path methods to
 * route through [AxeDeviceManager] / [SimctlCli] instead of Maestro's Orchestra.
 *
 * Maestro-shaped inputs are translated at the agent boundary by
 * [MaestroCommandToAxeActionConverter], so authored trails and recorded tool sequences stay
 * portable — the translation is just "more compatibility glue" as the user put it.
 */
class IosAxeTrailblazeAgent(
  private val deviceManager: AxeDeviceManager,
  trailblazeLogger: TrailblazeLogger,
  trailblazeDeviceInfoProvider: () -> TrailblazeDeviceInfo,
  sessionProvider: TrailblazeSessionProvider,
  nodeSelectorMode: NodeSelectorMode = NodeSelectorMode.DEFAULT,
) : MaestroTrailblazeAgent(
  trailblazeLogger = trailblazeLogger,
  trailblazeDeviceInfoProvider = trailblazeDeviceInfoProvider,
  sessionProvider = sessionProvider,
  nodeSelectorMode = nodeSelectorMode,
) {

  /** Flagged so tools can choose AXe-friendly command paths (mirrors the Android accessibility flag). */
  override val usesAccessibilityDriver: Boolean = true

  override suspend fun executeMaestroCommands(
    commands: List<Command>,
    traceId: TraceId?,
  ): TrailblazeToolResult {
    val actions = MaestroCommandToAxeActionConverter.convertAll(commands)
    if (actions.isEmpty() && commands.isNotEmpty()) {
      val skipped = commands.map { it::class.simpleName }.distinct().joinToString(", ")
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "All ${commands.size} Maestro command(s) are unsupported by the AXe driver. Skipped: $skipped",
      )
    }
    return AxeTrailRunner.runActions(
      actions = actions,
      traceId = traceId,
      deviceManager = deviceManager,
      trailblazeLogger = trailblazeLogger,
      sessionProvider = sessionProvider,
    )
  }

  /**
   * Resolves the rich [TrailblazeNodeSelector] against a fresh AXe tree and taps the match.
   * Short-circuits Maestro's Orchestra for the tap flow.
   */
  override suspend fun executeNodeSelectorTap(
    nodeSelector: TrailblazeNodeSelector,
    longPress: Boolean,
    traceId: TraceId?,
  ): TrailblazeToolResult = AxeTrailRunner.runActions(
    actions = listOf(AxeAction.TapOnElement(nodeSelector, longPress = longPress)),
    traceId = traceId,
    deviceManager = deviceManager,
    trailblazeLogger = trailblazeLogger,
    sessionProvider = sessionProvider,
  )

  override suspend fun executeNodeSelectorAssertVisible(
    nodeSelector: TrailblazeNodeSelector,
    timeoutMs: Long,
    traceId: TraceId?,
  ): TrailblazeToolResult = AxeTrailRunner.runActions(
    actions = listOf(AxeAction.AssertVisible(nodeSelector, timeoutMs)),
    traceId = traceId,
    deviceManager = deviceManager,
    trailblazeLogger = trailblazeLogger,
    sessionProvider = sessionProvider,
  )

  override suspend fun executeNodeSelectorAssertNotVisible(
    nodeSelector: TrailblazeNodeSelector,
    timeoutMs: Long,
    traceId: TraceId?,
  ): TrailblazeToolResult = AxeTrailRunner.runActions(
    actions = listOf(AxeAction.AssertNotVisible(nodeSelector, timeoutMs)),
    traceId = traceId,
    deviceManager = deviceManager,
    trailblazeLogger = trailblazeLogger,
    sessionProvider = sessionProvider,
  )

  /**
   * Public entry point for one-shot tool dispatch, used by the MCP bridge when a call
   * comes in for an IOS_AXE-configured device. Handles all three [TrailblazeTool] shapes
   * — [ExecutableTrailblazeTool], [DelegatingTrailblazeTool], [xyz.block.trailblaze.toolcalls.MapsToMaestroCommands]
   * (via its `ExecutableTrailblazeTool` parent) — so the bridge doesn't need to know about
   * tool-shape dispatch. A tool that internally calls `context.trailblazeAgent.runMaestroCommands`
   * lands back on [executeMaestroCommands] and routes through the AXe pipeline.
   *
   * Mirrors the protected `BaseTrailblazeAgent.executeTool` shape but stays `suspend` so we
   * don't wrap each expansion in `runBlocking` the way the synchronous override does.
   */
  suspend fun runTool(tool: TrailblazeTool, context: TrailblazeToolExecutionContext): TrailblazeToolResult {
    return when (tool) {
      is ExecutableTrailblazeTool -> tool.execute(context)
      is DelegatingTrailblazeTool -> {
        val expansions = tool.toExecutableTrailblazeTools(context)
        if (expansions.isEmpty()) return TrailblazeToolResult.Success()
        var last: TrailblazeToolResult = TrailblazeToolResult.Success()
        for (expansion in expansions) {
          last = expansion.execute(context)
          if (!last.isSuccess()) break
        }
        last
      }
      else -> throw TrailblazeException(
        message = "Tool ${tool::class.java.simpleName} is not a known TrailblazeTool shape " +
          "(ExecutableTrailblazeTool or DelegatingTrailblazeTool) — cannot execute on IOS_AXE.",
      )
    }
  }
}
