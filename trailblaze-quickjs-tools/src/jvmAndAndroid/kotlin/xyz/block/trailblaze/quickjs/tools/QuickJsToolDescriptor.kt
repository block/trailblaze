package xyz.block.trailblaze.quickjs.tools

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeToolParameterDescriptor

/**
 * Projects a [RegisteredToolSpec]'s `spec` JSON onto a [TrailblazeToolDescriptor] the
 * registry can surface to the LLM.
 *
 * The `@trailblaze/tools` SDK's `inputSchema` is the flat author-friendly form —
 * `{ paramName: { type, description } }` — not the nested JSON Schema 2020-12 shape the
 * MCP-flavored runtime reads. This parser handles both:
 *
 *  - **Flat author shape** (most QuickJS bundles): each top-level key is a parameter; all
 *    parameters are required (no `required:` list in the simple form).
 *  - **JSON Schema shape** (`{ type: "object", properties: {...}, required: [...] }`):
 *    detected by attempting to deserialize as [JsonSchemaInputSchema] and verifying the
 *    type discriminator. Same co-condition as before (BOTH `type == "object"` AND
 *    `properties != null`) — without that, a flat author who declares a parameter
 *    literally named `properties` would be misclassified.
 *
 * The two shapes used to be detected via direct `inputSchema["type"]` / `inputSchema["properties"]`
 * lookups with hard-coded string keys scattered through the function. Sam called those
 * out as "magic strings ... fragile" during PR review; this version uses
 * `kotlinx.serialization` data classes for the JSON Schema shape so the field names live
 * on the data class instead. Same shape detection semantics, less stringly-typed code.
 *
 * A property whose schema isn't an object (e.g. a stray primitive) is dropped rather than
 * crashing registration — bad authoring shouldn't take a session down.
 */
internal fun RegisteredToolSpec.toTrailblazeToolDescriptor(): TrailblazeToolDescriptor {
  val description = (spec[SPEC_KEY_DESCRIPTION] as? JsonPrimitive)?.contentOrNull
  val inputSchema = spec[SPEC_KEY_INPUT_SCHEMA] as? JsonObject
    ?: return TrailblazeToolDescriptor(name = name, description = description)

  // Try the JSON Schema shape first; on a deserialization mismatch (e.g. `properties` is
  // not an object map, `required` is not a list of strings) fall through to flat-shape
  // parsing. The data class itself defines the field names — no string keys here.
  val jsonSchema = inputSchema.tryDecodeAsJsonSchema()
  return if (jsonSchema != null && jsonSchema.isObjectShape()) {
    val all = jsonSchema.properties.orEmpty().toParameterDescriptors()
    val requiredNames = jsonSchema.required.toSet()
    TrailblazeToolDescriptor(
      name = name,
      description = description,
      requiredParameters = all.filter { it.name in requiredNames },
      optionalParameters = all.filter { it.name !in requiredNames },
    )
  } else {
    // Flat author shape: every top-level key is a parameter; treat all as required since
    // there's no `required:` to partition by.
    val all = inputSchema.toParameterDescriptors()
    TrailblazeToolDescriptor(
      name = name,
      description = description,
      requiredParameters = all,
      optionalParameters = emptyList(),
    )
  }
}

/**
 * Typed view of the JSON Schema-flavored `inputSchema` shape:
 * `{ type: "object", properties: {...}, required: [...] }`. The type discriminator is
 * deliberately not enforced as a constant by the serializer (we want to attempt decode
 * even when `type` is absent or a non-string), so a separate [isObjectShape] check
 * verifies the discriminator before treating this as a JSON Schema input.
 */
@Serializable
private data class JsonSchemaInputSchema(
  @SerialName(SCHEMA_KEY_TYPE) val type: String? = null,
  @SerialName(SCHEMA_KEY_PROPERTIES) val properties: Map<String, JsonObject>? = null,
  @SerialName(SCHEMA_KEY_REQUIRED) val required: List<String> = emptyList(),
) {
  fun isObjectShape(): Boolean = type == JSON_SCHEMA_TYPE_OBJECT && properties != null
}

/**
 * Per-property JSON Schema entry: `{ type, description }`. A bundle-side primitive or
 * other shape that doesn't fit fails decode and is dropped at the call site.
 */
@Serializable
private data class JsonSchemaProperty(
  @SerialName(SCHEMA_KEY_TYPE) val type: String? = null,
  @SerialName(SCHEMA_KEY_DESCRIPTION) val description: String? = null,
)

private fun JsonObject.tryDecodeAsJsonSchema(): JsonSchemaInputSchema? = try {
  QuickJsToolEnvelopeJson.decodeFromJsonElement(JsonSchemaInputSchema.serializer(), this)
} catch (e: SerializationException) {
  // The JSON didn't fit the data-class shape — a `properties` value that's a primitive,
  // a `required` that isn't an array of strings, etc. Treat as not-JSON-Schema and let
  // the caller fall back to flat-shape parsing.
  null
}

private fun Map<String, JsonObject>.toParameterDescriptors(): List<TrailblazeToolParameterDescriptor> =
  mapNotNull { (propName, rawSchema) ->
    val prop = rawSchema.tryDecodeAsProperty() ?: return@mapNotNull null
    TrailblazeToolParameterDescriptor(
      name = propName,
      type = prop.type ?: DEFAULT_PARAMETER_TYPE,
      description = prop.description,
    )
  }

private fun JsonObject.toParameterDescriptors(): List<TrailblazeToolParameterDescriptor> =
  mapNotNull { (propName, rawSchema) ->
    val propSchema = rawSchema as? JsonObject ?: return@mapNotNull null
    val prop = propSchema.tryDecodeAsProperty() ?: return@mapNotNull null
    TrailblazeToolParameterDescriptor(
      name = propName,
      type = prop.type ?: DEFAULT_PARAMETER_TYPE,
      description = prop.description,
    )
  }

private fun JsonObject.tryDecodeAsProperty(): JsonSchemaProperty? = try {
  QuickJsToolEnvelopeJson.decodeFromJsonElement(JsonSchemaProperty.serializer(), this)
} catch (e: SerializationException) {
  null
}

// Top-level `spec` keys defined by `@trailblaze/tools`'s `TrailblazeToolSpec` type.
// Mirroring them as named constants here so a renamed-key drift surfaces as a compile-
// referenced symbol rather than a silent grep miss.
private const val SPEC_KEY_DESCRIPTION = "description"
private const val SPEC_KEY_INPUT_SCHEMA = "inputSchema"

// JSON Schema 2020-12 keys we read from the nested `inputSchema` shape.
private const val SCHEMA_KEY_TYPE = "type"
private const val SCHEMA_KEY_PROPERTIES = "properties"
private const val SCHEMA_KEY_REQUIRED = "required"
private const val SCHEMA_KEY_DESCRIPTION = "description"

// JSON Schema's `type: "object"` discriminator value — anything else (or absent) is not
// the nested shape and falls back to flat-author parsing.
private const val JSON_SCHEMA_TYPE_OBJECT = "object"

// Type fallback when an author omits `type` on a property — JSON Schema's default is
// also string-shaped for the simple cases we handle, so this matches the spec while
// keeping unknown types from crashing registration.
private const val DEFAULT_PARAMETER_TYPE = "string"
