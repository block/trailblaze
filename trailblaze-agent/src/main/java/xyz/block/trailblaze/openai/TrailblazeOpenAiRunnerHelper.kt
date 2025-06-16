package xyz.block.trailblaze.openai

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.agent.model.TrailblazePromptStep
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeAgent
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.commands.ObjectiveStatusTrailblazeTool
import xyz.block.trailblaze.toolcalls.getToolNameFromAnnotation

class TrailblazeOpenAiRunnerHelper(
  private val agent: TrailblazeAgent,
) {

  // This field will be used to determine whether or not our next request to the LLM should require
  // a tool call. This occasionally happens when the LLM returns the tool call in the message
  // instead of actually triggering the tool. In this case we already have the model's reasoning
  // so we can just force it to call a tool next.
  private var shouldForceToolCall = false

  fun setShouldForceToolCall(force: Boolean) {
    shouldForceToolCall = force
  }

  fun getShouldForceToolCall(): Boolean = shouldForceToolCall

  private var forceStepStatusUpdate = false

  fun setForceStepStatusUpdate(force: Boolean) {
    forceStepStatusUpdate = force
  }

  fun getForceStepStatusUpdate(): Boolean = forceStepStatusUpdate

  fun handleTrailblazeToolForPrompt(
    trailblazeTool: TrailblazeTool,
    llmResponseId: String?,
    step: TrailblazePromptStep,
    screenStateForLlmRequest: ScreenState,
  ): TrailblazeToolResult = when (trailblazeTool) {
    is ObjectiveStatusTrailblazeTool -> {
      setForceStepStatusUpdate(false)
      when (trailblazeTool.status) {
        "in_progress" -> TrailblazeToolResult.Success
        "failed" -> {
          step.markAsFailed()
          // Using objective to determine when we're done, not the tool result
          TrailblazeToolResult.Success
        }

        "completed" -> {
          step.markAsComplete()
          TrailblazeToolResult.Success
        }

        else -> TrailblazeToolResult.Error.UnknownTrailblazeTool(trailblazeTool)
      }
    }

    else -> {
      val startTime = System.currentTimeMillis() - 1
      val (updatedTools, trailblazeToolResult) = agent.runTrailblazeTools(
        tools = listOf(trailblazeTool),
        llmResponseId = llmResponseId,
        screenState = screenStateForLlmRequest,
      )
      for (command in updatedTools) {
        TrailblazeLogger.log(
          TrailblazeLog.TrailblazeToolLog(
            agentTaskStatus = step.currentStatus.value,
            command = command,
            toolName = command.getToolNameFromAnnotation(),
            exceptionMessage = (trailblazeToolResult as? TrailblazeToolResult.Error)?.errorMessage,
            successful = trailblazeToolResult == TrailblazeToolResult.Success,
            duration = System.currentTimeMillis() - startTime,
            timestamp = startTime,
            instructions = step.fullPrompt,
            llmResponseId = llmResponseId,
            session = TrailblazeLogger.getCurrentSessionId(),
          ),
        )
      }
      setForceStepStatusUpdate(true)
      println("\u001B[33m\n[ACTION_TAKEN] Tool executed: ${trailblazeTool.javaClass.simpleName}\u001B[0m")
      trailblazeToolResult
    }
  }

  fun ToolRegistry.getTrailblazeToolFromToolRegistry(toolName: String, toolArgs: JsonObject): TrailblazeTool {
    val toolRegistry = this

    @Suppress("UNCHECKED_CAST")
    val koogTool: Tool<TrailblazeTool, ToolResult> =
      toolRegistry.getTool(toolName) as Tool<TrailblazeTool, ToolResult>
    val trailblazeTool: TrailblazeTool = TrailblazeJsonInstance.decodeFromJsonElement(
      deserializer = koogTool.argsSerializer,
      element = toolArgs,
    )
    return trailblazeTool
  }

  fun handleLlmResponse(
    toolRegistry: ToolRegistry,
    llmMessage: String?,
    toolName: String,
    toolArgs: JsonObject,
    llmResponseId: String?,
    step: TrailblazePromptStep,
    screenStateForLlmRequest: ScreenState,
  ) {
    val trailblazeToolResult = try {
      val trailblazeTool = toolRegistry.getTrailblazeToolFromToolRegistry(
        toolName = toolName,
        toolArgs = toolArgs,
      )
      handleTrailblazeToolForPrompt(
        trailblazeTool = trailblazeTool,
        llmResponseId = llmResponseId,
        step = step,
        screenStateForLlmRequest = screenStateForLlmRequest,
      )
    } catch (e: Exception) {
      TrailblazeToolResult.Error.ExceptionThrown.fromThrowable(
        throwable = e,
        trailblazeTool = null,
      )
    }

    step.addCompletedToolCallToChatHistory(
      llmResponseContent = llmMessage,
      toolName = toolName,
      toolArgs = toolArgs,
      commandResult = trailblazeToolResult,
    )
  }
}
