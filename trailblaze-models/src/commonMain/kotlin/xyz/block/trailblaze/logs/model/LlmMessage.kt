package xyz.block.trailblaze.logs.model

import kotlinx.serialization.Serializable

@Serializable
data class LlmMessage(
  val role: String,
  val message: String?,
)
