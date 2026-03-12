package xyz.block.trailblaze.agent

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.agent.model.AgentTaskStatusData
import xyz.block.trailblaze.agent.model.PromptRecordingResult
import xyz.block.trailblaze.agent.model.PromptStepStatus
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TestAgentRunner
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TaskId
import xyz.block.trailblaze.yaml.PromptStep

/**
 * Adapter that wraps [MultiAgentV3Runner] behind the [TestAgentRunner] interface.
 *
 * This allows existing test infrastructure (which expects [TestAgentRunner]) to use the
 * V3 multi-agent architecture without code changes. Each [run] call delegates to
 * [MultiAgentV3Runner.trail] with a single-step list.
 *
 * @param v3Runner The underlying V3 runner
 * @param screenStateProvider Provider for current screen state
 * @param sessionIdProvider Provider for the current session ID
 */
class MultiAgentV3TestAgentRunner(
  private val v3Runner: MultiAgentV3Runner,
  override val screenStateProvider: () -> ScreenState,
  private val sessionIdProvider: () -> SessionId,
) : TestAgentRunner {

  override fun run(
    prompt: PromptStep,
    stepStatus: PromptStepStatus,
  ): AgentTaskStatus = runBlocking {
    runSuspend(prompt, stepStatus)
  }

  override suspend fun runSuspend(
    prompt: PromptStep,
    stepStatus: PromptStepStatus,
  ): AgentTaskStatus {
    val startTime = Clock.System.now()
    val result = v3Runner.trail(
      steps = listOf(prompt),
      config = TrailConfig.AI_ONLY,
      sessionId = sessionIdProvider(),
    )
    return result.toAgentTaskStatus(prompt, startTime)
  }

  override fun recover(
    promptStep: PromptStep,
    recordingResult: PromptRecordingResult.Failure,
  ): AgentTaskStatus = runBlocking {
    val startTime = Clock.System.now()
    val result = v3Runner.trail(
      steps = listOf(promptStep),
      config = TrailConfig.AI_ONLY,
      sessionId = sessionIdProvider(),
    )
    result.toAgentTaskStatus(promptStep, startTime)
  }

  override fun appendToSystemPrompt(context: String) {
    // No-op: V3 doesn't use mutable system prompts
  }

  /**
   * Runs a prompt step and throws on failure, matching [TrailblazeRunner.runAndHandleStatus].
   */
  fun runAndHandleStatus(
    prompt: PromptStep,
    recordingResult: PromptRecordingResult.Failure? = null,
  ) {
    val status = if (recordingResult != null) {
      recover(prompt, recordingResult)
    } else {
      run(prompt)
    }

    when (status) {
      is AgentTaskStatus.Success.ObjectiveComplete -> return
      else -> throw TrailblazeException("Failed to successfully run prompt with AI $prompt")
    }
  }

  private fun TrailResult.toAgentTaskStatus(
    prompt: PromptStep,
    startTime: kotlinx.datetime.Instant,
  ): AgentTaskStatus {
    val statusData = AgentTaskStatusData(
      taskId = TaskId.generate(),
      prompt = prompt.prompt,
      callCount = state.completedSteps.size,
      taskStartTime = startTime,
      totalDurationMs = durationMs,
    )
    return if (success) {
      AgentTaskStatus.Success.ObjectiveComplete(
        statusData = statusData,
        llmExplanation = "V3 trail completed successfully",
      )
    } else {
      AgentTaskStatus.Failure.ObjectiveFailed(
        statusData = statusData,
        llmExplanation = errorMessage ?: "V3 trail failed",
      )
    }
  }
}
