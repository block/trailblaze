package xyz.block.trailblaze.toolcalls

import ai.koog.agents.core.tools.SimpleTool
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

}
