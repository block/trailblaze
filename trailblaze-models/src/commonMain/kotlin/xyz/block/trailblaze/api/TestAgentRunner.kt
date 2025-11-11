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
  fun recover(promptStep: PromptStep, recordingResult: PromptRecordingResult.Failure): AgentTaskStatus
  fun appendToSystemPrompt(context: String)
}
