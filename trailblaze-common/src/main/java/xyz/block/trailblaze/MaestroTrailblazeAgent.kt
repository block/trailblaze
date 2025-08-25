package xyz.block.trailblaze

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import maestro.orchestra.Command
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeAgent
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.toolcalls.DelegatingTrailblazeTool
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.commands.memory.MemoryTrailblazeTool
import xyz.block.trailblaze.toolcalls.getToolNameFromAnnotation
import xyz.block.trailblaze.utils.ElementComparator
import kotlin.random.Random

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

  val memory = AgentMemory()

  // Clear any variables that exist in the agent's memory between test runs
  fun clearMemory() {
    memory.clear()
  }

  fun runMaestroCommands(
    maestroCommands: List<Command>,
    llmResponseId: String?,
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
    elementComparator: ElementComparator,
  ): Pair<List<TrailblazeTool>, TrailblazeToolResult> {
    val toolChainId = generateIdIfNull(
      prefix = "tools",
      llmResponseId = llmResponseId,
    )
    val trailblazeExecutionContext = TrailblazeToolExecutionContext(
      screenState = screenState,
      llmResponseId = toolChainId,
      trailblazeAgent = this,
    )

    val toolsExecuted = mutableListOf<TrailblazeTool>()
    for (trailblazeTool in tools) {
      when (trailblazeTool) {
        is ExecutableTrailblazeTool -> {
          toolsExecuted.add(trailblazeTool)
          val result = handleExecutableTool(trailblazeTool, trailblazeExecutionContext)
          if (result != TrailblazeToolResult.Success) {
            // Exit early if any tool execution fails
            return toolsExecuted to result
          }
        }

        is DelegatingTrailblazeTool -> {
          val executableTools = trailblazeTool.toExecutableTrailblazeTools(trailblazeExecutionContext)
          logDelegatingTrailblazeTool(
            trailblazeTool = trailblazeTool,
            llmResponseId = toolChainId,
            executableTools = executableTools,
          )
          for (mappedTool in executableTools) {
            toolsExecuted.add(mappedTool)
            val result = handleExecutableTool(mappedTool, trailblazeExecutionContext)
            if (result != TrailblazeToolResult.Success) {
              // Exit early if any tool execution fails
              return toolsExecuted to result
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
    return toolsExecuted to TrailblazeToolResult.Success
  }

  private fun handleExecutableTool(
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
      command = trailblazeTool,
      toolName = trailblazeTool.getToolNameFromAnnotation(),
      exceptionMessage = (trailblazeToolResult as? TrailblazeToolResult.Error)?.errorMessage,
      successful = trailblazeToolResult == TrailblazeToolResult.Success,
      durationMs = Clock.System.now().toEpochMilliseconds() - timeBeforeToolExecution.toEpochMilliseconds(),
      timestamp = timeBeforeToolExecution,
      llmResponseId = trailblazeExecutionContext.llmResponseId,
      session = TrailblazeLogger.getCurrentSessionId(),
    )
    val toolLogJson = TrailblazeJsonInstance.encodeToString(toolLog)
    println("toolLogJson: $toolLogJson")
    TrailblazeLogger.log(toolLog)
  }

  private fun logDelegatingTrailblazeTool(
    trailblazeTool: DelegatingTrailblazeTool,
    llmResponseId: String?,
    executableTools: List<ExecutableTrailblazeTool>,
  ) {
    TrailblazeLogger.log(
      TrailblazeLog.DelegatingTrailblazeToolLog(
        command = trailblazeTool,
        toolName = trailblazeTool.getToolNameFromAnnotation(),
        executableTools = executableTools,
        session = TrailblazeLogger.getCurrentSessionId(),
        llmResponseId = llmResponseId,
        timestamp = Clock.System.now(),
      ),
    )
  }

  companion object {
    /**
     * Generates a unique ID if no ID is provided.
     */
    fun generateIdIfNull(prefix: String?, llmResponseId: String?): String = llmResponseId ?: buildString {
      prefix?.let {
        appendLine("$prefix-")
      }
      appendLine(Random.nextLong().toString())
    }
  }
}
