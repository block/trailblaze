package xyz.block.trailblaze.ui.goose

import kotlinx.serialization.Serializable

@Serializable
data class GooseExtension(
  val type: String,
  val name: String,
  val description: String,
  val uri: String,
  val envs: Map<String, String> = emptyMap(),
  val env_keys: List<String> = emptyList(),
  val enabled: Boolean = true,
  val bundled: Boolean = false,
  val timeout: Int = 3000,
)
