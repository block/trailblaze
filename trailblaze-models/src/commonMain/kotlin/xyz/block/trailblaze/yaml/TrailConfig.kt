package xyz.block.trailblaze.yaml

import kotlinx.serialization.Serializable

@Serializable
data class TrailConfig(
  val context: String? = null,
  val id: String? = null,
  val title: String? = null,
  val description: String? = null,
  val priority: String? = null,
  val source: TrailSource? = null,
  val metadata: Map<String, String>? = null,
)

@Serializable
data class TrailSource(
  val type: TrailSourceType? = null,
  val reason: String? = null
)

@Serializable
enum class TrailSourceType {
  HANDWRITTEN,
}