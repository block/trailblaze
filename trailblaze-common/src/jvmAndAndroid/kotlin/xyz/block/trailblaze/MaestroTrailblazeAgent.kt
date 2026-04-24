package xyz.block.trailblaze

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import maestro.orchestra.Command
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.device.AndroidDeviceCommandExecutor
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.model.NodeSelectorMode
import xyz.block.trailblaze.toolcalls.DelegatingTrailblazeTool
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.isSuccess

/**
 * Abstract class for Trailblaze agents that handle Maestro commands.
 * This class provides a framework for executing Maestro commands and handling [TrailblazeTool]s.
 *
 * This is abstract because there can be both on-device and host implementations of this agent.
 * Uses stateless logger with explicit session management via sessionProvider.
 */
abstract class MaestroTrailblazeAgent(
  override val trailblazeLogger: TrailblazeLogger,
  override val trailblazeDeviceInfoProvider: () -> TrailblazeDeviceInfo,
  override val sessionProvider: TrailblazeSessionProvider,
  /** Controls nodeSelector vs legacy Maestro path for playback and recording. */
  val nodeSelectorMode: NodeSelectorMode = NodeSelectorMode.DEFAULT,
  /**
   * Session tool repo — threaded to the base so `OtherTrailblazeTool` instances (e.g.
   * subprocess MCP tool names in a trail YAML) can resolve through
   * [xyz.block.trailblaze.toolcalls.TrailblazeToolRepo] before driver dispatch.
   */
  trailblazeToolRepo: TrailblazeToolRepo? = null,
) : BaseTrailblazeAgent() {

  override val trailblazeToolRepo: TrailblazeToolRepo? = trailblazeToolRepo

  /**
   * Whether this agent uses the accessibility driver instead of Maestro/UiAutomator.
   * Tools can check this to choose accessibility-friendly command paths.
   */
  open val usesAccessibilityDriver: Boolean = false

  protected abstract suspend fun executeMaestroCommands(
    commands: List<Command>,
    traceId: TraceId?,
  ): TrailblazeToolResult

  /**
   * Executes a tap using the rich [TrailblazeNodeSelector], bypassing the Maestro command layer.
   *
   * Override in driver-specific agents (e.g., AccessibilityTrailblazeAgent) to provide
   * native resolution using [TrailblazeNode] trees.
   *
   * @return A [TrailblazeToolResult] if the driver handled the action, or null if the driver
   *   does not support [TrailblazeNodeSelector] (in which case the caller should fall back
   *   to the Maestro command path).
   */
  open suspend fun executeNodeSelectorTap(
    nodeSelector: TrailblazeNodeSelector,
    longPress: Boolean,
    traceId: TraceId?,
  ): TrailblazeToolResult? = null

  /**
   * Asserts that an element matching the [nodeSelector] is visible, bypassing Maestro commands.
   *
   * Override in driver-specific agents (e.g., AccessibilityTrailblazeAgent) to provide
   * native resolution using [TrailblazeNodeSelector] trees.
   *
   * @return A [TrailblazeToolResult] if the driver handled the assertion, or null to fall back
   *   to the Maestro command path.
   */
  open suspend fun executeNodeSelectorAssertVisible(
    nodeSelector: TrailblazeNodeSelector,
    timeoutMs: Long = 5_000L,
    traceId: TraceId?,
  ): TrailblazeToolResult? = null

  /**
   * Asserts that no element matching the [nodeSelector] is visible, bypassing Maestro commands.
   *
   * Override in driver-specific agents (e.g., AccessibilityTrailblazeAgent) to provide
   * native resolution using [TrailblazeNodeSelector] trees.
   *
   * @return A [TrailblazeToolResult] if the driver handled the assertion, or null to fall back
   *   to the Maestro command path.
   */
  open suspend fun executeNodeSelectorAssertNotVisible(
    nodeSelector: TrailblazeNodeSelector,
    timeoutMs: Long = 5_000L,
    traceId: TraceId?,
  ): TrailblazeToolResult? = null

  @Deprecated(
    message = "Use the suspend function runMaestroCommands() instead.",
    replaceWith = ReplaceWith("runMaestroCommands(maestroCommands, traceId)"),
  )
  fun runMaestroCommandsBlocking(
    maestroCommands: List<Command>,
    traceId: TraceId?,
  ): TrailblazeToolResult = runBlocking {
    runMaestroCommands(
      maestroCommands = maestroCommands,
      traceId = traceId,
    )
  }

  suspend fun runMaestroCommands(
    maestroCommands: List<Command>,
    traceId: TraceId?,
  ): TrailblazeToolResult {
    maestroCommands.forEach { command ->
      val result = executeMaestroCommands(
        commands = listOf(command),
        traceId = traceId,
      )
      if (!result.isSuccess()) {
        return result
      }
    }
    return TrailblazeToolResult.Success()
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
      screenStateProvider = screenStateProvider,
      androidDeviceCommandExecutor = AndroidDeviceCommandExecutor(trailblazeDeviceInfo.trailblazeDeviceId),
      trailblazeLogger = trailblazeLogger,
      memory = memory,
      maestroTrailblazeAgent = this,
      nodeSelectorMode = nodeSelectorMode,
    )
  }

  override fun executeTool(
    tool: TrailblazeTool,
    context: TrailblazeToolExecutionContext,
    toolsExecuted: MutableList<TrailblazeTool>,
  ): TrailblazeToolResult {
    return when (tool) {
      is ExecutableTrailblazeTool -> {
        toolsExecuted.add(tool)
        handleExecutableToolBlocking(tool, context)
      }
      is DelegatingTrailblazeTool -> {
        executeDelegatingTool(tool, context, toolsExecuted) { mappedTool ->
          handleExecutableToolBlocking(mappedTool, context)
        }
      }
      is OtherTrailblazeTool -> throw TrailblazeException(
        message = buildString {
          appendLine("Unknown tool '${tool.toolName}' is not registered and cannot be executed.")
          appendLine("This usually means the tool's class is not in the custom tool classes for this app target.")
          appendLine("Ensure the app target is selected (e.g., in the UI or via CLI) and that it registers this tool via getCustomToolsForDriver().")
          appendLine("Raw parameters: ${tool.raw}")
        },
      )
      else -> throw TrailblazeException(
        message = buildString {
          appendLine("Unhandled Trailblaze tool ${tool::class.java.simpleName} - ${tool}.")
          appendLine("Supported Trailblaze Tools must implement one of the following:")
          appendLine("- ${ExecutableTrailblazeTool::class.java.simpleName}")
          appendLine("- ${DelegatingTrailblazeTool::class.java.simpleName}")
        },
      )
    }
  }

  @Deprecated(
    message = "Use the suspend function handleExecutableTool() instead.",
    replaceWith = ReplaceWith("handleExecutableTool(trailblazeTool, trailblazeExecutionContext)"),
  )
  private fun handleExecutableToolBlocking(
    trailblazeTool: ExecutableTrailblazeTool,
    trailblazeExecutionContext: TrailblazeToolExecutionContext,
  ) = runBlocking {
    handleExecutableTool(trailblazeTool, trailblazeExecutionContext)
  }

  private suspend fun handleExecutableTool(
    trailblazeTool: ExecutableTrailblazeTool,
    trailblazeExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    val timeBeforeToolExecution = Clock.System.now()
    val trailblazeToolResult = trailblazeTool.execute(
      toolExecutionContext = trailblazeExecutionContext,
    )
    logToolExecution(
      tool = trailblazeTool,
      timeBeforeExecution = timeBeforeToolExecution,
      context = trailblazeExecutionContext,
      result = trailblazeToolResult,
    )
    return trailblazeToolResult
  }
}
