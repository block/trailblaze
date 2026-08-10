package xyz.block.trailblaze.host.ios

import java.io.File
import maestro.orchestra.Command
import xyz.block.trailblaze.MaestroTrailblazeAgent
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.model.NodeSelectorMode
import xyz.block.trailblaze.model.ResolvedTarget
import xyz.block.trailblaze.toolcalls.DelegatingTrailblazeTool
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.isSuccess

/**
 * iOS-Simulator agent that drives the device natively through a host-native
 * [IosDeviceManager] (the AXe CLI, or any other driver behind that seam) instead of
 * Maestro/XCUITest.
 *
 * Parallel to [xyz.block.trailblaze.android.accessibility.AccessibilityTrailblazeAgent] on
 * Android: it extends [MaestroTrailblazeAgent] so the existing tool catalog and
 * `runMaestroCommands` plumbing continue to work, but overrides the five hot-path methods to
 * route through the [IosDeviceManager] / [SimctlCli] instead of Maestro's Orchestra.
 *
 * Maestro-shaped inputs are translated at the agent boundary by
 * [MaestroCommandToIosDriverActionConverter], so authored trails and recorded tool sequences stay
 * portable — the translation is just "more compatibility glue" as the user put it.
 */
class IosDriverTrailblazeAgent(
  private val deviceManager: IosDeviceManager,
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

  /** Flagged so tools can choose AXe-friendly command paths (mirrors the Android accessibility flag). */
  override val usesAccessibilityDriver: Boolean = true

  /**
   * The base implementation dispatches one command at a time, which would let earlier commands
   * of a batch execute before a later unsupported one fails conversion. Passing the whole batch
   * to [executeMaestroCommands] converts every command up front, so an unconvertible batch
   * fails before any action runs.
   */
  override suspend fun runMaestroCommands(
    maestroCommands: List<Command>,
    traceId: TraceId?,
  ): TrailblazeToolResult = executeMaestroCommands(maestroCommands, traceId)

  override suspend fun executeMaestroCommands(
    commands: List<Command>,
    traceId: TraceId?,
  ): TrailblazeToolResult {
    val actions = try {
      MaestroCommandToIosDriverActionConverter.convertAll(commands)
    } catch (e: TrailblazeException) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = e.message ?: "Unsupported Maestro command on the AXe driver",
        // Distinguishes a converter bug from a genuinely-unsupported command in session logs,
        // matching every other ExceptionThrown producer on this driver (e.g. IosDriverTrailRunner).
        stackTrace = e.stackTraceToString(),
      )
    }
    return IosDriverTrailRunner.runActions(
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
  ): TrailblazeToolResult = IosDriverTrailRunner.runActions(
    actions = listOf(IosDriverAction.TapOnElement(nodeSelector, longPress = longPress)),
    traceId = traceId,
    deviceManager = deviceManager,
    trailblazeLogger = trailblazeLogger,
    sessionProvider = sessionProvider,
  )

  override suspend fun executeNodeSelectorAssertVisible(
    nodeSelector: TrailblazeNodeSelector,
    timeoutMs: Long?,
    traceId: TraceId?,
  ): TrailblazeToolResult = IosDriverTrailRunner.runActions(
    actions = listOf(IosDriverAction.AssertVisible(nodeSelector, timeoutMs ?: DEFAULT_AXE_TIMEOUT_MS)),
    traceId = traceId,
    deviceManager = deviceManager,
    trailblazeLogger = trailblazeLogger,
    sessionProvider = sessionProvider,
  )

  override suspend fun executeNodeSelectorAssertNotVisible(
    nodeSelector: TrailblazeNodeSelector,
    timeoutMs: Long?,
    traceId: TraceId?,
  ): TrailblazeToolResult = IosDriverTrailRunner.runActions(
    actions = listOf(IosDriverAction.AssertNotVisible(nodeSelector, timeoutMs ?: DEFAULT_AXE_TIMEOUT_MS)),
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
   *
   * Like `MaestroTrailblazeAgent.executeTool`, an incoming [OtherTrailblazeTool] (the
   * unknown-at-decode-time placeholder callers like `BridgeUiActionExecutor` forward for the
   * device agent to resolve) is resolved through the session's tool repo before the shape
   * dispatch below. Without this, every repo-resolvable tool that reached this entry as a
   * placeholder failed with a misleading "not a known TrailblazeTool shape" error.
   */
  suspend fun runTool(tool: TrailblazeTool, context: TrailblazeToolExecutionContext): TrailblazeToolResult {
    return when (val resolved = resolveDynamicTool(tool)) {
      is ExecutableTrailblazeTool -> resolved.execute(context)
      is DelegatingTrailblazeTool -> {
        val expansions = resolved.toExecutableTrailblazeTools(context)
        if (expansions.isEmpty()) return TrailblazeToolResult.Success()
        var last: TrailblazeToolResult = TrailblazeToolResult.Success()
        for (expansion in expansions) {
          last = expansion.execute(context)
          if (!last.isSuccess()) break
        }
        last
      }
      is OtherTrailblazeTool -> throw TrailblazeException(
        message = if (trailblazeToolRepo == null) {
          "Unknown tool '${resolved.toolName}' cannot be resolved because no tool repo is " +
            "wired on this agent, and cannot be executed on IOS_AXE."
        } else {
          "Unknown tool '${resolved.toolName}' is not registered in this session's " +
            "tool repo as a class-backed, YAML-defined, or dynamic scripted tool, and cannot " +
            "be executed on IOS_AXE."
        },
      )
      else -> throw TrailblazeException(
        message = "Tool ${resolved::class.java.simpleName} is not a known TrailblazeTool shape " +
          "(ExecutableTrailblazeTool or DelegatingTrailblazeTool) — cannot execute on IOS_AXE.",
      )
    }
  }

  companion object {
    /** Driver-default wait when the caller passes `timeoutMs = null`. Matches the prior
     *  hardcoded default so existing callers see no behavior change. */
    private const val DEFAULT_AXE_TIMEOUT_MS = 5_000L
  }
}
