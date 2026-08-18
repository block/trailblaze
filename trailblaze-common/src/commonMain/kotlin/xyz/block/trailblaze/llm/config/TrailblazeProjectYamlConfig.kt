package xyz.block.trailblaze.llm.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Top-level schema for `trailblaze.yaml` project config file.
 * Contains optional sections for different configuration concerns.
 */
@Serializable
data class TrailblazeProjectYamlConfig(
  @SerialName("target") val appTarget: String? = null,
  @SerialName("llm") val llm: LlmConfig? = null,
)
