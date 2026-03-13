package xyz.block.trailblaze.rules

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.agent.model.AgentTaskStatus.Success.ObjectiveComplete
import xyz.block.trailblaze.logs.client.ObjectiveLogHelper
import xyz.block.trailblaze.api.TestAgentRunner
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.model.TaskId
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.yaml.PromptStep

class TrailblazeRunnerUtil(
  val runTrailblazeTool: (tools: List<TrailblazeTool>) -> TrailblazeToolResult,
  private val trailblazeRunner: TestAgentRunner,
  private val trailblazeLogger: TrailblazeLogger? = null,
  private val sessionProvider: (() -> TrailblazeSession)? = null,
) {
  fun runPrompt(
    prompts: List<PromptStep>,
    useRecordedSteps: Boolean,
    onStepProgress: ((stepIndex: Int, totalSteps: Int, stepText: String) -> Unit)? = null,
  ): TrailblazeToolResult = runBlocking {
    runPromptSuspend(prompts, useRecordedSteps, onStepProgress)
  }

  /**
   * Suspend version of runPrompt that properly handles coroutine cancellation.
   * Checks for cancellation before each prompt and during execution.
   */
  suspend fun runPromptSuspend(
    prompts: List<PromptStep>,
    useRecordedSteps: Boolean,
    onStepProgress: ((stepIndex: Int, totalSteps: Int, stepText: String) -> Unit)? = null,
  ): TrailblazeToolResult {
    for ((index, prompt) in prompts.withIndex()) {
      onStepProgress?.invoke(index + 1, prompts.size, prompt.prompt)
      val promptResult: TrailblazeToolResult =
        if (useRecordedSteps && prompt.canPromptStepUseRecording()) {
          val stepStartTime = Clock.System.now()
          val stepTaskId = TaskId.generate()
          emitObjectiveStart(prompt)
          val result = runTrailblazeTool(prompt.recording!!.tools.map { it.trailblazeTool })
          val success = result !is TrailblazeToolResult.Error
          val failureReason = (result as? TrailblazeToolResult.Error)?.errorMessage
          emitObjectiveComplete(prompt, stepTaskId, stepStartTime, success, failureReason)
          result
        } else {
          when (val status = trailblazeRunner.runSuspend(prompt)) {
            is ObjectiveComplete -> TrailblazeToolResult.Success()
            is AgentTaskStatus.Failure -> {
              throw TrailblazeException(
                buildString {
                  appendLine("Failed to successfully run prompt with AI ${TrailblazeJsonInstance.encodeToString(prompt)}")
                  appendLine("Status Type: ${status::class.java.name}")
                  appendLine("Status: ${TrailblazeJsonInstance.encodeToString(status)}")
                },
              )
            }

            is AgentTaskStatus.InProgress -> {
              // Still in progress
              TrailblazeToolResult.Success()
            }

            is AgentTaskStatus.McpScreenAnalysis -> {
              // MCP screen analysis - treat as success (analysis completed)
              TrailblazeToolResult.Success()
            }
          }
        }
      if (promptResult is TrailblazeToolResult.Error) {
        throw TrailblazeException("Failed to successfully run prompt $prompt with error ${promptResult.errorMessage}")
      }
    }
    return TrailblazeToolResult.Success()
  }

  private fun emitObjectiveStart(step: PromptStep) {
    val logger = trailblazeLogger ?: return
    val session = try { sessionProvider?.invoke() } catch (_: Exception) { null } ?: return
    logger.log(session, ObjectiveLogHelper.createStartLog(step, session.sessionId))
  }

  private fun emitObjectiveComplete(
    step: PromptStep,
    taskId: TaskId,
    stepStartTime: kotlinx.datetime.Instant,
    success: Boolean,
    failureReason: String?,
  ) {
    val logger = trailblazeLogger ?: return
    val session = try { sessionProvider?.invoke() } catch (_: Exception) { null } ?: return
    logger.log(
      session,
      ObjectiveLogHelper.createCompleteLog(
        step = step,
        taskId = taskId,
        stepStartTime = stepStartTime,
        sessionId = session.sessionId,
        success = success,
        failureReason = failureReason,
      ),
    )
  }

  private fun PromptStep.canPromptStepUseRecording() = recordable && recording != null
}
