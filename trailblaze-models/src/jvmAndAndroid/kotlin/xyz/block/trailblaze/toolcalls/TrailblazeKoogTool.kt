package xyz.block.trailblaze.toolcalls

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import kotlin.reflect.KClass
import kotlin.reflect.full.starProjectedType

/**
 * Bridge between our [TrailblazeTool] definitions and Koog's tool system.
 * This allows us to define our tools using the [TrailblazeTool] interface.
 */
open class TrailblazeKoogTool<T : TrailblazeTool>(
  kClass: KClass<T>,
  private val executeTool: suspend (args: T) -> String,
) : SimpleTool<T>(
  argsSerializer = @Suppress("UNCHECKED_CAST") (serializer(kClass.starProjectedType) as KSerializer<T>),
  name = kClass.toKoogToolDescriptor()?.name ?: error("Failed to create tool descriptor for $kClass"),
  description = kClass.toKoogToolDescriptor()?.description ?: error("Failed to create tool descriptor for $kClass"),
) {

  override suspend fun execute(args: T): String = executeTool(args)

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
