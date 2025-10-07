package xyz.block.trailblaze.agent.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.logs.model.TaskId

@Serializable
data class AgentTaskStatusData(
  val taskId: TaskId,
  val prompt: String,
  val callCount: Int,
  val taskStartTime: Instant,
  val totalDurationMs: Long,
)
