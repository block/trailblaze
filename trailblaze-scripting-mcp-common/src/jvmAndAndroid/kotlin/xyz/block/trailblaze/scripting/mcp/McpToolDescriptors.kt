package xyz.block.trailblaze.scripting.mcp

import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeToolParameterDescriptor

/**
 * Projects an MCP [ToolSchema] (JSON Schema 2020-12 object, `type: "object"`) onto a
 * Trailblaze [TrailblazeToolDescriptor] the registry can surface to the LLM.
 *
 * MVP extracts per-property `type` + `description` and partitions by the schema's `required`
 * list. Nested shapes, enums, `oneOf`, and defaults aren't reflected — a property with
 * `type: "object"` registers as `"object"` and the LLM sees a flat schema hint. Authors
 * who need richer parameter hints can add a second pass later; the MCP handler receives the
 * full raw JSON regardless, so correctness isn't affected — only LLM-facing UX is.
 */
fun ToolSchema.toTrailblazeToolDescriptor(
  name: String,
  description: String?,
): TrailblazeToolDescriptor {
  val requiredNames = required?.toSet().orEmpty()
  val properties = properties ?: JsonObject(emptyMap())
  val all = properties.mapNotNull { (propName, rawSchema) ->
    val propSchema = rawSchema as? JsonObject ?: return@mapNotNull null
    TrailblazeToolParameterDescriptor(
      name = propName,
      type = propSchema.propertyType(),
      description = propSchema["description"]?.let { (it as? JsonPrimitive)?.contentOrNull },
    )
  }
  return TrailblazeToolDescriptor(
    name = name,
    description = description,
    requiredParameters = all.filter { it.name in requiredNames },
    optionalParameters = all.filter { it.name !in requiredNames },
  )
}

private fun JsonObject.propertyType(): String =
  (this["type"] as? JsonPrimitive)?.contentOrNull ?: "string"
