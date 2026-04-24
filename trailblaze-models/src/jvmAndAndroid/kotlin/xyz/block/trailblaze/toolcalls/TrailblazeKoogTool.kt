package xyz.block.trailblaze.toolcalls

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.serialization.JSONSerializer
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
  argsSerializer: KSerializer<T>,
  descriptor: ToolDescriptor,
  private val executeTool: suspend (args: T) -> String,
) : Tool<T, String>(
  argsSerializer = argsSerializer,
  resultSerializer = String.serializer(),
  descriptor = descriptor,
) {

  constructor(
    kClass: KClass<T>,
    executeTool: suspend (args: T) -> String,
  ) : this(
    argsSerializer = @Suppress("UNCHECKED_CAST") (serializer(kClass.starProjectedType) as KSerializer<T>),
    descriptor = kClass.toKoogToolDescriptor() ?: error("Failed to create tool descriptor for $kClass"),
    executeTool = executeTool,
  )

  override suspend fun execute(args: T): String = executeTool(args)
  override fun encodeResultToString(result: String, serializer: JSONSerializer): String = result

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

    /**
     * Projects a [TrailblazeToolDescriptor] onto Koog's typed [ToolDescriptor]. Shared by
     * every tool source that surfaces descriptors to the LLM (class-backed tools, YAML-
     * defined tools, subprocess MCP tools, future on-device bundle tools).
     *
     * [strict] controls unknown-type-string policy:
     *  - `true`  → throws an error with a clear message. Right for author-controlled schemas
     *    (e.g. YAML-defined tools, `ToolYamlConfig.parameters[*].type`) where an unrecognized
     *    type is a config bug worth surfacing loudly at registration.
     *  - `false` → silently falls back to [ToolParameterType.String]. Right for runtime-
     *    discovered schemas (e.g. subprocess MCP `tools/list` responses) where a tool with
     *    an unmappable type like `"array"` / `"object"` shouldn't abort the whole session.
     */
    fun TrailblazeToolDescriptor.toKoogToolDescriptor(strict: Boolean): ToolDescriptor = ToolDescriptor(
      name = name,
      description = description.orEmpty(),
      requiredParameters = requiredParameters.map { it.toKoogParameterDescriptor(strict) },
      optionalParameters = optionalParameters.map { it.toKoogParameterDescriptor(strict) },
    )

    fun TrailblazeToolParameterDescriptor.toKoogParameterDescriptor(strict: Boolean): ToolParameterDescriptor =
      ToolParameterDescriptor(
        name = name,
        description = description.orEmpty(),
        type = parseKoogParameterType(type, strict),
      )

    fun parseKoogParameterType(typeString: String, strict: Boolean): ToolParameterType =
      when (typeString.trim().lowercase()) {
        "string" -> ToolParameterType.String
        "integer", "int", "long" -> ToolParameterType.Integer
        "number", "float", "double" -> ToolParameterType.Float
        "boolean", "bool" -> ToolParameterType.Boolean
        else -> if (strict) {
          error("Unsupported tool parameter type '$typeString'. Supported: string, integer, number, boolean.")
        } else {
          ToolParameterType.String
        }
      }
  }
}
