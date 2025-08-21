package xyz.block.trailblaze.api

import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.yaml.PromptStep

interface TestAgentRunner {
  fun run(prompt: PromptStep): AgentTaskStatus
  fun appendToSystemPrompt(context: String)
}
