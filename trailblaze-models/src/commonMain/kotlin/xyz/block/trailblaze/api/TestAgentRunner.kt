package xyz.block.trailblaze.api

import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.agent.model.PromptRecordingResult
import xyz.block.trailblaze.agent.model.PromptStepStatus
import xyz.block.trailblaze.yaml.PromptStep

interface TestAgentRunner {
  val screenStateProvider: () -> ScreenState

  fun run(
    prompt: PromptStep,
    stepStatus: PromptStepStatus = PromptStepStatus(prompt, screenStateProvider = screenStateProvider),
  ): AgentTaskStatus

  /**
   * Suspend version of run() that supports coroutine cancellation.
   * Default implementation calls the blocking version for backwards compatibility.
   */
  suspend fun runSuspend(
    prompt: PromptStep,
    stepStatus: PromptStepStatus = PromptStepStatus(prompt, screenStateProvider = screenStateProvider),
  ): AgentTaskStatus = run(prompt, stepStatus)

  fun recover(promptStep: PromptStep, recordingResult: PromptRecordingResult.Failure): AgentTaskStatus

  /**
   * Suspend version of recover(), so self-heal runs in the trail's coroutine and is cancelled with it.
   * Default implementation calls the blocking version.
   */
  suspend fun recoverSuspend(
    promptStep: PromptStep,
    recordingResult: PromptRecordingResult.Failure,
  ): AgentTaskStatus = recover(promptStep, recordingResult)

  fun appendToSystemPrompt(context: String)

  /**
   * Drops everything [appendToSystemPrompt] added, so the next trail run on this runner starts from
   * the base prompt. A runner that keeps appended context must override this.
   */
  fun resetSystemPrompt() = Unit
}
