package xyz.block.trailblaze.logs.client

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.agent.model.AgentTaskStatusData
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TaskId
import xyz.block.trailblaze.yaml.PromptStep

/**
 * Shared helper for creating objective lifecycle log events.
 *
 * Used by DeterministicTrailExecutor, TrailblazeRunnerUtil, and TrailExecutorImpl to avoid
 * duplicating the log-building logic.
 */
object ObjectiveLogHelper {

  fun createStartLog(
    step: PromptStep,
    sessionId: SessionId,
  ): TrailblazeLog.ObjectiveStartLog =
    TrailblazeLog.ObjectiveStartLog(
      promptStep = step,
      session = sessionId,
      timestamp = Clock.System.now(),
    )

  fun createCompleteLog(
    step: PromptStep,
    taskId: TaskId,
    stepStartTime: Instant,
    sessionId: SessionId,
    success: Boolean,
    failureReason: String?,
    explanation: String =
      if (success) "Completed via recording"
      else (failureReason ?: "Recording execution failed"),
  ): TrailblazeLog.ObjectiveCompleteLog {
    val now = Clock.System.now()
    val durationMs = (now - stepStartTime).inWholeMilliseconds
    val toolCount = step.recording?.tools?.size ?: 0
    val statusData =
      AgentTaskStatusData(
        taskId = taskId,
        prompt = step.prompt,
        callCount = toolCount,
        taskStartTime = stepStartTime,
        totalDurationMs = durationMs,
      )
    val objectiveResult: AgentTaskStatus =
      if (success) {
        AgentTaskStatus.Success.ObjectiveComplete(
          statusData = statusData,
          llmExplanation = explanation,
        )
      } else {
        AgentTaskStatus.Failure.ObjectiveFailed(
          statusData = statusData,
          llmExplanation = explanation,
        )
      }
    return TrailblazeLog.ObjectiveCompleteLog(
      promptStep = step,
      objectiveResult = objectiveResult,
      session = sessionId,
      timestamp = now,
    )
  }
}
