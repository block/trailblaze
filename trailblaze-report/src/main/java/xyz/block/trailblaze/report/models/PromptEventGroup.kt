package xyz.block.trailblaze.report.models

import kotlinx.serialization.Serializable

@Serializable
data class PromptEventGroup(
  val prompt: String? = null,
  val kotlin: String? = null,
  val json: String? = null,
  val yaml: String? = null,
  val events: List<SessionEvent>,
)
