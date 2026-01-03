package xyz.block.trailblaze.toolcalls

import kotlinx.serialization.Serializable

/**
 * [kotlin.io.Serializable] Mirror of [ai.koog.agents.core.tools.ToolParameterDescriptor]
 */
@Serializable
data class TrailblazeToolParameterDescriptor(
  val name: String,
  val type: String,
  val description: String? = null,
)

/**
 * [kotlin.io.Serializable] Mirror of [ai.koog.agents.core.tools.ToolDescriptor]
 */
@Serializable
data class TrailblazeToolDescriptor(
  val name: String,
  val description: String? = null,
  val requiredParameters: List<TrailblazeToolParameterDescriptor> = emptyList(),
  val optionalParameters: List<TrailblazeToolParameterDescriptor> = emptyList(),
)
