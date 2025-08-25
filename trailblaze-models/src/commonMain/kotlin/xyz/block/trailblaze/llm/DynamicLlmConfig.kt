package xyz.block.trailblaze.llm

import kotlinx.serialization.Serializable

/**
 * Holds dynamic LLM Configuration
 */
@Serializable
data class DynamicLlmConfig(
  val modelId: String,
  val providerId: String,
  val capabilities: List<String>,
)
