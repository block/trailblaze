package xyz.block.trailblaze.api

import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.agent.model.TestObjective.TrailblazeObjective.TrailblazePrompt
import xyz.block.trailblaze.agent.model.TrailblazePromptStep

interface TestAgentRunner {
  fun run(prompt: TrailblazePrompt): AgentTaskStatus
  fun run(step: TrailblazePromptStep): AgentTaskStatus
}
