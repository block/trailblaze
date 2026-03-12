package xyz.block.trailblaze

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import maestro.orchestra.Command
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeAgent
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.device.AndroidDeviceCommandExecutor
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.logs.model.TraceId.Companion.TraceOrigin
import xyz.block.trailblaze.toolcalls.DelegatingTrailblazeTool
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.isSuccess
import xyz.block.trailblaze.toolcalls.commands.RequestViewHierarchyDetailsTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.memory.MemoryTrailblazeTool
import xyz.block.trailblaze.utils.ElementComparator
import xyz.block.trailblaze.viewhierarchy.NativeViewHierarchyDetail

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
) : TrailblazeAgent, TrailblazeAgentContext {

  /**
   * Whether this agent uses the accessibility driver instead of Maestro/UiAutomator.
   * Tools can check this to choose accessibility-friendly command paths.
   */
  open val usesAccessibilityDriver: Boolean = false

  protected abstract suspend fun executeMaestroCommands(
    commands: List<Command>,
    traceId: TraceId?,
  ): TrailblazeToolResult

  override val memory = AgentMemory()

  /**
   * Pending view hierarchy detail requests from [RequestViewHierarchyDetailsTrailblazeTool].
   * Consumed (cleared) by the screen state provider on the next turn.
   */
  @Volatile
  var pendingViewHierarchyDetails: Set<NativeViewHierarchyDetail> = emptySet()

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

  // Clear any variables that exist in the agent's memory between test runs
  fun clearMemory() {
    memory.clear()
  }

  @Deprecated("Prefer the suspend version of this function.")
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
        // Exit early if any command fails
        return result
      }
    }
    return TrailblazeToolResult.Success()
  }

  override fun runTrailblazeTools(
    tools: List<TrailblazeTool>,
    traceId: TraceId?,
    screenState: ScreenState?,
    elementComparator: ElementComparator,
    screenStateProvider: (() -> ScreenState)?,
  ): TrailblazeAgent.RunTrailblazeToolsResult {
    val traceId = traceId ?: TraceId.generate(TraceOrigin.TOOL)
    val trailblazeDeviceInfo = trailblazeDeviceInfoProvider()
    val trailblazeExecutionContext = TrailblazeToolExecutionContext(
      screenState = screenState,
      traceId = traceId,
      trailblazeDeviceInfo = trailblazeDeviceInfo,
      sessionProvider = sessionProvider,
      screenStateProvider = screenStateProvider,
      androidDeviceCommandExecutor = AndroidDeviceCommandExecutor(trailblazeDeviceInfo.trailblazeDeviceId),
      trailblazeLogger = trailblazeLogger,
      memory = memory,
      maestroTrailblazeAgent = this,
    )

    val toolsExecuted = mutableListOf<TrailblazeTool>()
    var lastSuccessResult: TrailblazeToolResult = TrailblazeToolResult.Success()
    for (trailblazeTool in tools) {
      when (trailblazeTool) {
        is ExecutableTrailblazeTool -> {
          toolsExecuted.add(trailblazeTool)
          val result = handleExecutableToolBlocking(trailblazeTool, trailblazeExecutionContext)
          if (!result.isSuccess()) {
            // Exit early if any tool execution fails
            return TrailblazeAgent.RunTrailblazeToolsResult(inputTools = toolsExecuted, executedTools = toolsExecuted, result = result)
          }
          if (trailblazeTool is RequestViewHierarchyDetailsTrailblazeTool) {
            pendingViewHierarchyDetails = trailblazeTool.include.toSet()
          }
          lastSuccessResult = result
        }

        is DelegatingTrailblazeTool -> {
          val mappedExecutableTools = trailblazeTool.toExecutableTrailblazeTools(trailblazeExecutionContext)
          logDelegatingTool(
            tool = trailblazeTool,
            traceId = traceId,
            executableTools = mappedExecutableTools,
          )

          val originalTools = listOf(trailblazeTool)

          for (mappedTool in mappedExecutableTools) {
            toolsExecuted.add(mappedTool)
            val result = handleExecutableToolBlocking(mappedTool, trailblazeExecutionContext)
            if (!result.isSuccess()) {
              // Exit early if any tool execution fails
              return TrailblazeAgent.RunTrailblazeToolsResult(inputTools = originalTools, executedTools = toolsExecuted, result = result)
            }
            lastSuccessResult = result
          }
        }

        is MemoryTrailblazeTool -> {
          // Execute the memory tool with the execution context and the handleMemoryTool lambda
          // The lambda will call the TrailblazeElementComparator
          trailblazeTool.execute(
            memory = trailblazeExecutionContext.memory,
            elementComparator = elementComparator,
          )
        }

        is OtherTrailblazeTool -> throw TrailblazeException(
          message = buildString {
            appendLine("Unknown tool '${trailblazeTool.toolName}' is not registered and cannot be executed.")
            appendLine("This usually means the tool's class is not in the custom tool classes for this app target.")
            appendLine("Ensure the app target is selected (e.g., in the UI or via CLI) and that it registers this tool via getCustomToolsForDriver().")
            appendLine("Raw parameters: ${trailblazeTool.raw}")
          },
        )

        else -> throw TrailblazeException(
          message = buildString {
            appendLine("Unhandled Trailblaze tool ${trailblazeTool::class.java.simpleName} - ${trailblazeTool}.")
            appendLine("Supported Trailblaze Tools must implement one of the following:")
            appendLine("- ${ExecutableTrailblazeTool::class.java.simpleName}")
            appendLine("- ${DelegatingTrailblazeTool::class.java.simpleName}")
          },
        )
      }
    }
    return TrailblazeAgent.RunTrailblazeToolsResult(
      inputTools = toolsExecuted,
      executedTools = toolsExecuted,
      result = lastSuccessResult,
    )
  }

  @Deprecated("Starting in Maestro 2.0.0 their api uses a suspend function. Prefer that implementation.")
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
