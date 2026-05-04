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
 *
 * Capability flags that live on [TrailblazeToolClass] (`isForLlm`, `isRecordable`,
 * `requiresHost`, `isVerification`) intentionally do **not** ride on this descriptor.
 * Descriptors are produced by several converters from many sources (Koog `ToolDescriptor`,
 * MCP `ToolSchema`, YAML configs) and threading every capability through every converter
 * is more error-prone than reading the source-of-truth annotation when needed. Consumers
 * that need a flag should look it up via the corresponding `KClass<out TrailblazeTool>`
 * extension (e.g. `KClass.isVerification()`, `KClass.requiresHost()`) or the instance-
 * level helpers in `TrailblazeTools.kt` (`isVerificationToolInstance`,
 * `getIsRecordableFromAnnotation`, `requiresHostInstance`).
 */
@Serializable
data class TrailblazeToolDescriptor(
  val name: String,
  val description: String? = null,
  val requiredParameters: List<TrailblazeToolParameterDescriptor> = emptyList(),
  val optionalParameters: List<TrailblazeToolParameterDescriptor> = emptyList(),
)
