package xyz.block.trailblaze.report.models

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.logs.client.TrailblazeLog

@Serializable
data class PromptLogGroup(
  val prompt: String? = null,
  val logs: MutableSet<TrailblazeLog> = mutableSetOf(),
  val kotlin: String? = null,
  val json: String? = null,
  val yaml: String? = null,
) {
  fun sorted() = apply {
    logs.sortedBy { it.timestamp }
  }
}
