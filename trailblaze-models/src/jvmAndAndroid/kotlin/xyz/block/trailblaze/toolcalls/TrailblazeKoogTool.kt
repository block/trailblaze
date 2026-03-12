package xyz.block.trailblaze.toolcalls

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.serializer
import kotlin.reflect.KClass
import kotlin.reflect.full.starProjectedType

/**
 * Bridge between our [TrailblazeTool] definitions and Koog's tool system.
 * This allows us to define our tools using the [TrailblazeTool] interface.
 *
 * Extends [Tool] directly (rather than [ai.koog.agents.core.tools.SimpleTool]) so we can
 * provide a pre-built [ToolDescriptor] via [toKoogToolDescriptor]. This avoids Koog's default
 * serializer-based descriptor generation which recursively introspects the serial descriptor
 * and causes [StackOverflowError] on self-referencing types like [TrailblazeNodeSelector][xyz.block.trailblaze.api.TrailblazeNodeSelector].
 */
open class TrailblazeKoogTool<T : TrailblazeTool>(
  kClass: KClass<T>,
  private val executeTool: suspend (args: T) -> String,
) : Tool<T, String>(
  argsSerializer = @Suppress("UNCHECKED_CAST") (serializer(kClass.starProjectedType) as KSerializer<T>),
  resultSerializer = String.serializer(),
  descriptor = kClass.toKoogToolDescriptor() ?: error("Failed to create tool descriptor for $kClass"),
) {

  override suspend fun execute(args: T): String = executeTool(args)
  override fun encodeResultToString(result: String): String = result

  companion object {
    fun ToolParameterDescriptor.toTrailblazeToolParameterDescriptor(): TrailblazeToolParameterDescriptor =
      TrailblazeToolParameterDescriptor(
        name = this.name,
        description = this.description.takeIf { it.isNotBlank() },
        type = this.type.name
      )

    fun ToolDescriptor.toTrailblazeToolDescriptor(): TrailblazeToolDescriptor = TrailblazeToolDescriptor(
      name = this.name,
      description = this.description.takeIf { it.isNotBlank() },
      optionalParameters = this.optionalParameters.map { it.toTrailblazeToolParameterDescriptor() },
      requiredParameters = this.requiredParameters.map { it.toTrailblazeToolParameterDescriptor() }
    )
  }
}
