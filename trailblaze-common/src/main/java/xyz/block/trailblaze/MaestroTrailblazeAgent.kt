package xyz.block.trailblaze

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import maestro.orchestra.Command
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeAgent
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeLoggerInstance
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.logs.model.TraceId.Companion.TraceOrigin
import xyz.block.trailblaze.toolcalls.DelegatingTrailblazeTool
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.commands.memory.MemoryTrailblazeTool
import xyz.block.trailblaze.toolcalls.getToolNameFromAnnotation
import xyz.block.trailblaze.utils.ElementComparator

/**
 * Abstract class for Trailblaze agents that handle Maestro commands.
 * This class provides a framework for executing Maestro commands and handling [TrailblazeTool]s.
 *
 * This is abstract because there can be both on-device and host implementations of this agent.
 */
abstract class MaestroTrailblazeAgent(
  val trailblazeLogger: TrailblazeLogger = TrailblazeLoggerInstance,
) : TrailblazeAgent {

  protected abstract suspend fun executeMaestroCommands(
    commands: List<Command>,
    traceId: TraceId?,
  ): TrailblazeToolResult

  val memory = AgentMemory()

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
      if (result != TrailblazeToolResult.Success) {
        // Exit early if any command fails
        return result
      }
    }
    return TrailblazeToolResult.Success
  }

  override fun runTrailblazeTools(
    tools: List<TrailblazeTool>,
    traceId: TraceId?,
    screenState: ScreenState?,
    elementComparator: ElementComparator,
  ): TrailblazeAgent.RunTrailblazeToolsResult {
    val traceId = traceId ?: TraceId.generate(TraceOrigin.TOOL)
    val trailblazeExecutionContext = TrailblazeToolExecutionContext(
      screenState = screenState,
      traceId = traceId,
      trailblazeAgent = this,
    )

    val toolsExecuted = mutableListOf<TrailblazeTool>()
    for (trailblazeTool in tools) {
      when (trailblazeTool) {
        is ExecutableTrailblazeTool -> {
          toolsExecuted.add(trailblazeTool)
          val result = handleExecutableToolBlocking(trailblazeTool, trailblazeExecutionContext)
          if (result != TrailblazeToolResult.Success) {
            // Exit early if any tool execution fails
            return TrailblazeAgent.RunTrailblazeToolsResult(inputTools = toolsExecuted, executedTools = toolsExecuted, result = result)
          }
        }

        is DelegatingTrailblazeTool -> {
          val mappedExecutableTools = trailblazeTool.toExecutableTrailblazeTools(trailblazeExecutionContext)
          logDelegatingTrailblazeTool(
            trailblazeTool = trailblazeTool,
            traceId = traceId,
            executableTools = mappedExecutableTools,
          )

          val originalTools = listOf(trailblazeTool)

          for (mappedTool in mappedExecutableTools) {
            toolsExecuted.add(mappedTool)
            val result = handleExecutableToolBlocking(mappedTool, trailblazeExecutionContext)
            if (result != TrailblazeToolResult.Success) {
              // Exit early if any tool execution fails
              return TrailblazeAgent.RunTrailblazeToolsResult(inputTools = originalTools, executedTools = toolsExecuted, result = result)
            }
          }
        }

        is MemoryTrailblazeTool -> {
          // Execute the memory tool with the execution context and the handleMemoryTool lambda
          // The lambda will call the TrailblazeElementComparator
          trailblazeTool.execute(
            memory = trailblazeExecutionContext.trailblazeAgent.memory,
            elementComparator = elementComparator,
          )
        }

        else -> throw TrailblazeException(
          message = buildString {
            appendLine("Unhandled Trailblaze tool ${trailblazeTool::class.java.simpleName}.")
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
      result = TrailblazeToolResult.Success,
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
    logTrailblazeTool(
      trailblazeTool = trailblazeTool,
      timeBeforeToolExecution = timeBeforeToolExecution,
      trailblazeExecutionContext = trailblazeExecutionContext,
      trailblazeToolResult = trailblazeToolResult,
    )
    return trailblazeToolResult
  }

  private fun logTrailblazeTool(
    trailblazeTool: ExecutableTrailblazeTool,
    timeBeforeToolExecution: Instant,
    trailblazeExecutionContext: TrailblazeToolExecutionContext,
    trailblazeToolResult: TrailblazeToolResult,
  ) {
    val toolLog = TrailblazeLog.TrailblazeToolLog(
      trailblazeTool = trailblazeTool,
      toolName = trailblazeTool.getToolNameFromAnnotation(),
      exceptionMessage = (trailblazeToolResult as? TrailblazeToolResult.Error)?.errorMessage,
      successful = trailblazeToolResult == TrailblazeToolResult.Success,
      durationMs = Clock.System.now().toEpochMilliseconds() - timeBeforeToolExecution.toEpochMilliseconds(),
      timestamp = timeBeforeToolExecution,
      traceId = trailblazeExecutionContext.traceId,
      session = trailblazeLogger.getCurrentSessionId(),
    )
    val toolLogJson = TrailblazeJsonInstance.encodeToString(toolLog)
    println("toolLogJson: $toolLogJson")
    trailblazeLogger.log(toolLog)
  }

  private fun logDelegatingTrailblazeTool(
    trailblazeTool: DelegatingTrailblazeTool,
    traceId: TraceId?,
    executableTools: List<ExecutableTrailblazeTool>,
  ) {
    trailblazeLogger.log(
      TrailblazeLog.DelegatingTrailblazeToolLog(
        trailblazeTool = trailblazeTool,
        toolName = trailblazeTool.getToolNameFromAnnotation(),
        executableTools = executableTools,
        session = trailblazeLogger.getCurrentSessionId(),
        traceId = traceId,
        timestamp = Clock.System.now(),
      ),
    )
  }
}
