package xyz.block.trailblaze

import kotlinx.datetime.Clock
import maestro.orchestra.Command
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeAgent
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.toolcalls.DelegatingTrailblazeTool
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.getToolNameFromAnnotation

/**
 * Abstract class for Trailblaze agents that handle Maestro commands.
 * This class provides a framework for executing Maestro commands and handling [TrailblazeTool]s.
 *
 * This is abstract because there can be both on-device and host implementations of this agent.
 */
abstract class MaestroTrailblazeAgent : TrailblazeAgent {

  protected abstract fun executeMaestroCommands(
    commands: List<Command>,
    llmResponseId: String?,
  ): TrailblazeToolResult

  fun runMaestroCommands(
    maestroCommands: List<Command>,
    llmResponseId: String? = null,
  ): TrailblazeToolResult {
    maestroCommands.forEach { command ->
      val result = executeMaestroCommands(
        commands = listOf(command),
        llmResponseId = llmResponseId,
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
    llmResponseId: String?,
    screenState: ScreenState?,
  ): Pair<List<TrailblazeTool>, TrailblazeToolResult> {
    val trailblazeExecutionContext = TrailblazeToolExecutionContext(
      screenState = screenState,
      llmResponseId = llmResponseId,
      trailblazeAgent = this,
    )

    val executableTrailblazeTools: List<ExecutableTrailblazeTool> = tools.flatMap { trailblazeTool ->
      when (trailblazeTool) {
        is ExecutableTrailblazeTool -> listOf(trailblazeTool)
        is DelegatingTrailblazeTool -> {
          val executableTools = trailblazeTool.toExecutableTrailblazeTools(trailblazeExecutionContext)
          TrailblazeLogger.log(
            TrailblazeLog.DelegatingTrailblazeToolLog(
              command = trailblazeTool,
              executableTools = executableTools,
              session = TrailblazeLogger.getCurrentSessionId(),
              timestamp = Clock.System.now(),
            ),
          )
          executableTools
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
    val toolsExecuted = mutableListOf<TrailblazeTool>()
    executableTrailblazeTools.forEach { trailblazeTool ->
      toolsExecuted.add(trailblazeTool)
      val timeBeforeToolExecution = Clock.System.now()
      val trailblazeToolResult = trailblazeTool.execute(trailblazeExecutionContext)
      TrailblazeLogger.log(
        TrailblazeLog.TrailblazeToolLog(
          command = trailblazeTool,
          toolName = trailblazeTool.getToolNameFromAnnotation(),
          exceptionMessage = (trailblazeToolResult as? TrailblazeToolResult.Error)?.errorMessage,
          successful = trailblazeToolResult == TrailblazeToolResult.Success,
          durationMs = Clock.System.now().toEpochMilliseconds() - timeBeforeToolExecution.toEpochMilliseconds(),
          timestamp = timeBeforeToolExecution,
          llmResponseId = llmResponseId,
          session = TrailblazeLogger.getCurrentSessionId(),
        ),
      )

      if (trailblazeToolResult != TrailblazeToolResult.Success) {
        // Exit early if any tool execution fails
        return toolsExecuted to trailblazeToolResult
      }
    }
    return toolsExecuted to TrailblazeToolResult.Success
  }
}
