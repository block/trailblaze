package xyz.block.trailblaze.toolcalls

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
) : SimpleTool<T>() {

  @Suppress("UNCHECKED_CAST")
  override val argsSerializer: KSerializer<T> = serializer(kClass.starProjectedType) as KSerializer<T>

  override val descriptor: ToolDescriptor =
    kClass.toKoogToolDescriptor() ?: error("Failed to create tool descriptor for $kClass")

  override val name: String = descriptor.name

  override val description: String = descriptor.description

  override suspend fun doExecute(args: T): String = executeTool(args)

  companion object {

    private fun ToolParameterDescriptor.toJson(): JsonObject = JsonObject(
      mapOf(
        "name" to JsonPrimitive(this.name),
        "description" to JsonPrimitive(this.description),
        "type" to JsonPrimitive(this.type.toString()),
      ),
    )

    fun ToolDescriptor.toJson(): JsonObject = JsonObject(
      mapOf(
        "name" to JsonPrimitive(this.name),
        "description" to JsonPrimitive(this.description),
        "requiredParameters" to JsonArray(this.requiredParameters.map { it.toJson() }),
        "optionalParameters" to JsonArray(this.optionalParameters.map { it.toJson() }),
      ),
    )
  }
}
