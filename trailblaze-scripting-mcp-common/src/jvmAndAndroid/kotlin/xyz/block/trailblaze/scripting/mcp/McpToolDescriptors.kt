package xyz.block.trailblaze.scripting.mcp

import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeToolParameterDescriptor

/**
 * Projects an MCP [ToolSchema] (JSON Schema 2020-12 object, `type: "object"`) onto a
 * Trailblaze [TrailblazeToolDescriptor] the registry can surface to the LLM.
 *
 * Extracts per-property `type` + `description` + `enum` and partitions by the schema's
 * `required` list. A property with a JSON-Schema `enum` array threads its allowed values into
 * [TrailblazeToolParameterDescriptor.validValues] so the LLM-facing function-call schema can
 * constrain the argument rather than seeing a bare `type: string`. Nested shapes, `oneOf`, and
 * defaults still aren't reflected — a property with `type: "object"` registers as `"object"`
 * and the LLM sees a flat schema hint. The MCP handler receives the full raw JSON regardless,
 * so correctness isn't affected — only LLM-facing UX is.
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
      validValues = propSchema.enumValues(),
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

/**
 * JSON-Schema `enum` → allowed values for the parameter descriptor. A `"type": "string"` with a
 * sibling `enum` (a TS string-literal union lowered by the analyzer, or any MCP server declaring
 * an enum) carries its legal values here so the constraint reaches the LLM — not just the prose
 * description.
 *
 * **Only STRING enums are promoted.** Koog's `ToolParameterType.Enum` is string-only and
 * `KoogToMcpExt.fillJsonSchema` always renders it as `{"type":"string","enum":[...]}`, so
 * surfacing a non-string enum (e.g. `{"type":"integer","enum":[1,2]}`) would emit a *lying*
 * schema — the LLM would send `"1"` instead of `1` and the tool would receive the wrong JSON
 * type. A non-string enum keeps its primitive type and drops the (koog-inexpressible) constraint.
 *
 * Null when the property declares no usable string `enum` (absent, non-string-typed, or no string
 * entries); an empty `enum` folds to null. Mirrors
 * `LazyYamlScriptedToolRegistration.jsonSchemaEnumValues`.
 */
private fun JsonObject.enumValues(): List<String>? {
  val type = (this["type"] as? JsonPrimitive)?.contentOrNull
  if (type != null && !type.equals("string", ignoreCase = true)) return null
  return (this["enum"] as? JsonArray)
    ?.mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }
    ?.takeIf { it.isNotEmpty() }
}
