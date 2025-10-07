package xyz.block.trailblaze.report.models

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.logs.model.TaskId

@Serializable
data class TaskIdAndPrompt(
  val taskId: TaskId,
  val prompt: String,
)
