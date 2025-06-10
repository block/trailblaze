package xyz.block.trailblaze.logs.model

import xyz.block.trailblaze.agent.model.AgentTaskStatus

interface HasAgentTaskStatus {
  val agentTaskStatus: AgentTaskStatus
}
