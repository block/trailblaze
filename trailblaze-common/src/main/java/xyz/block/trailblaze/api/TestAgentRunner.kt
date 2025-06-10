package xyz.block.trailblaze.api

import xyz.block.trailblaze.agent.model.AgentTaskStatus

interface TestAgentRunner {
  fun run(instructions: String): AgentTaskStatus
}
