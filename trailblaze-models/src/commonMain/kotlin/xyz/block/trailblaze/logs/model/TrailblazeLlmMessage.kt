package xyz.block.trailblaze.logs.model

import kotlinx.serialization.Serializable

@Serializable
data class TrailblazeLlmMessage(
  val role: String,
  val message: String?,
)
