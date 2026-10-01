@file:OptIn(kotlin.time.ExperimentalTime::class)

package xyz.block.trailblaze.agent.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.Clock
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.logs.model.TaskId
import xyz.block.trailblaze.yaml.PromptStep

/**
 * The prompt step an LLM request belongs to, plus the screen it was made against — what
 * `TrailblazeLogger.logLlmRequest` needs to write a request log. Call [prepareNextStep] to capture
 * the screen before logging.
 */
data class PromptStepStatus(
  val promptStep: PromptStep,
  private val screenStateProvider: () -> ScreenState,
) {
  val taskId = TaskId.generate()

  lateinit var currentScreenState: ScreenState
    private set

  val currentStatus = MutableStateFlow<AgentTaskStatus>(
    AgentTaskStatus.InProgress(
      statusData = AgentTaskStatusData(
        prompt = promptStep.prompt,
        callCount = 0,
        taskStartTime = Clock.System.now(),
        totalDurationMs = 0,
        taskId = taskId,
      ),
    ),
  )

  fun prepareNextStep() {
    currentScreenState = screenStateProvider()
  }
}
