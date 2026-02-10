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
  /**
   * Optional target application identifier. This can be an alias if custom tools provided by your
   * organization use a short name for the app, or a package ID (e.g., "com.example.app") if
   * desired. Not required.
   */
  val targetApp: String? = null,
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