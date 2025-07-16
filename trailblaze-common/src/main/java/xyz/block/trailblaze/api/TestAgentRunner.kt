package xyz.block.trailblaze.api

import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.agent.model.TestObjective.TrailblazeObjective.TrailblazePrompt
import xyz.block.trailblaze.agent.model.TrailblazePromptStep
import xyz.block.trailblaze.yaml.TrailYamlItem.PromptsTrailItem.PromptStep

interface TestAgentRunner {
  @Deprecated("Move to the current run(PromptStep) version of this function")
  fun run(prompt: TrailblazePrompt): AgentTaskStatus

  @Deprecated("Move to the current run(PromptStep) version of this function")
  fun run(step: TrailblazePromptStep): AgentTaskStatus
  fun run(prompt: PromptStep): AgentTaskStatus
}
