package xyz.block.trailblaze.api

import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.agent.model.PromptStep
import xyz.block.trailblaze.agent.model.TestObjective.TrailblazeObjective.TrailblazePrompt

interface TestAgentRunner {
  fun run(prompt: TrailblazePrompt): AgentTaskStatus
  fun run(step: PromptStep): AgentTaskStatus
}
