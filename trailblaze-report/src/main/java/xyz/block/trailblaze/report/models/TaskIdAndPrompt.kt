package xyz.block.trailblaze.report.models

import kotlinx.serialization.Serializable

@Serializable
data class TaskIdAndPrompt(
  val taskId: String,
  val prompt: String,
)
