package xyz.block.trailblaze.logs.model

import kotlinx.serialization.Serializable

@Serializable
data class TrailblazeLlmMessage(
  val role: String,
  val message: String?,
  /** Tool name if this is a tool call (role = "tool_call") */
  val toolName: String? = null,
)
