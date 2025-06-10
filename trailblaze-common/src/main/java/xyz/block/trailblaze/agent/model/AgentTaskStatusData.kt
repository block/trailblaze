package xyz.block.trailblaze.agent.model

import kotlinx.serialization.Serializable

@Serializable
data class AgentTaskStatusData(
  val taskId: String,
  val prompt: String,
  val callCount: Int,
  val taskStartTime: Long,
  val totalDurationMs: Long,
)
