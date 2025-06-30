package xyz.block.trailblaze.toolcalls

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.reflect.asToolType
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.primaryConstructor
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

  override val descriptor: ToolDescriptor = kClass.toKoogToolDescriptor()

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

    /**
     * Extracts [ToolDescriptor] info from a [TrailblazeTool] class.
     */
    fun KClass<out TrailblazeTool>.toKoogToolDescriptor(): ToolDescriptor {
      val kClass = this
      fun KParameter.toKoogToolParameterDescriptors(): ToolParameterDescriptor = ToolParameterDescriptor(
        name = this.name?.trim() ?: error("Parameter name cannot be null"),
        description = this.findAnnotation<LLMDescription>()?.description?.trim()?.trimIndent() ?: "",
        type = this.type.asToolType(),
      )

      val primaryConstructorParams = kClass.primaryConstructor?.parameters
      val optionalParams = primaryConstructorParams
        ?.filter { it.isOptional }
        ?.map { it.toKoogToolParameterDescriptors() }
        ?: listOf()
      val requiredParams =
        primaryConstructorParams
          ?.filter { !it.isOptional }
          ?.map { it.toKoogToolParameterDescriptors() }
          ?: listOf()

      return ToolDescriptor(
        name = kClass.findAnnotation<TrailblazeToolClass>()?.name?.trim()
          ?: error("Please add @TrailblazeToolClass to $kClass"),
        description = kClass.findAnnotation<LLMDescription>()?.description?.trim()?.trimIndent()
          ?: error("Please add @LLMDescription to $kClass"),
        requiredParameters = requiredParams,
        optionalParameters = optionalParams,
      )
    }
  }
}
