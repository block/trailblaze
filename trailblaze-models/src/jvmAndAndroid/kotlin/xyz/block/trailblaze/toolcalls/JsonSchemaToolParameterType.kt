package xyz.block.trailblaze.toolcalls

import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.net.URLDecoder

/**
 * Lowers a tool's JSON Schema (`{ type: object, properties: {...} }`) onto Koog [ToolParameterType]s,
 * so the schema the LLM receives keeps array `items` and nested object `properties` instead of
 * flattening them to strings.
 *
 * A scripted (`.ts`) tool declares e.g. `items?: { name: string; priceCents: number }[]`. The flat
 * `type`-string view on [TrailblazeToolParameterDescriptor] can only say `"array"`, which the
 * Koog projection used to map to `String`, so the model sent `"Espresso, Hot Chocolate"` or a
 * stringified JSON array and the recorded args then failed `trailblaze check` against the tool's own
 * TypeScript types. Reading the full schema keeps the model's output the same shape the tool declares.
 *
 * Local `$ref`s (`#/definitions/<name>`, `#/$defs/<name>`) are followed. Every shape this cannot
 * model faithfully degrades to what the flat view produced before (a string), or to a free-form
 * object for a union of objects, so a tool with an exotic schema never fails to register.
 */
internal class JsonSchemaToolParameterTypes(private val root: JsonObject) {
  private val definitions = collectDefinitions(root)

  /** The Koog type for the property [name] of the root object schema, or null if it has none. */
  fun typeOfRootProperty(name: String): ToolParameterType? {
    val property = (root["properties"] as? JsonObject)?.get(name) as? JsonObject ?: return null
    return convert(property, depth = 0, expanding = emptySet())
  }

  private fun convert(schema: JsonObject, depth: Int, expanding: Set<String>): ToolParameterType {
    // A selector-sized schema (or a pathological one) must not bloat the LLM's tool menu or recurse
    // without bound: below the cap an object is "some JSON object" and anything else is text.
    if (depth > MAX_DEPTH) return fallbackFor(schema)

    var current = schema
    var expandingNow = expanding
    while (true) {
      val ref = current.refName() ?: break
      // A type that contains itself: stop at the second visit instead of expanding forever.
      if (ref in expandingNow) return FREE_FORM_OBJECT
      val target = definitions[ref] ?: return fallbackFor(current)
      expandingNow = expandingNow + ref
      current = target
    }

    current.stringEnumValues()?.let { return ToolParameterType.Enum(it.toTypedArray()) }

    // `anyOf` / `oneOf`: a nullable (`T | null`) wrapper is just `T`; a real union is only
    // expressible when every member is an object (free-form) or a string literal (an enum).
    for (key in UNION_KEYS) {
      val members = (current[key] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: continue
      return convertUnion(members, depth, expandingNow)
    }
    // `allOf` with a single member is a wrapper (ts-json-schema-generator emits it to attach a
    // description to a `$ref`); several members are an intersection this cannot flatten.
    (current["allOf"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.let { members ->
      return if (members.size == 1) convert(members.single(), depth, expandingNow) else fallbackFor(current)
    }

    return when (current.nonNullTypes().singleOrNull() ?: inferType(current)) {
      "string" -> ToolParameterType.String
      "integer" -> ToolParameterType.Integer
      "number" -> ToolParameterType.Float
      "boolean" -> ToolParameterType.Boolean
      "array" -> ToolParameterType.List(itemsType(current, depth, expandingNow))
      "object" -> objectType(current, depth, expandingNow)
      else -> ToolParameterType.String
    }
  }

  private fun itemsType(array: JsonObject, depth: Int, expanding: Set<String>): ToolParameterType {
    // Tuple-form `items: [...]` and an absent/untyped `items` (a `unknown[]`) carry no single
    // element type; text is what the flat view always said for those.
    val items = array["items"] as? JsonObject ?: return ToolParameterType.String
    return convert(items, depth + 1, expanding)
  }

  private fun objectType(schema: JsonObject, depth: Int, expanding: Set<String>): ToolParameterType {
    val properties = (schema["properties"] as? JsonObject)
      ?.mapNotNull { (name, value) ->
        val property = value as? JsonObject ?: return@mapNotNull null
        ToolParameterDescriptor(
          name = name,
          description = describe(property) ?: name,
          type = convert(property, depth + 1, expanding),
        )
      }
      .orEmpty()
    val additional = schema["additionalProperties"]
    val additionalType = (additional as? JsonObject)
      ?.takeIf { it.isNotEmpty() }
      ?.let { convert(it, depth + 1, expanding) }
    val required = (schema["required"] as? JsonArray)
      ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
      ?.filter { required -> properties.any { it.name == required } }
      .orEmpty()
    return ToolParameterType.Object(
      properties = properties,
      requiredProperties = required,
      // No `properties` means a record / free-form payload: anything goes unless the schema says no.
      additionalProperties = when {
        additional is JsonPrimitive -> additional.booleanOrNull
        additional is JsonObject -> true
        properties.isEmpty() -> true
        else -> null
      },
      additionalPropertiesType = additionalType,
    )
  }

  private fun convertUnion(members: List<JsonObject>, depth: Int, expanding: Set<String>): ToolParameterType {
    val nonNull = members.filterNot { it.isNullType() }
    if (nonNull.size == 1) return convert(nonNull.single(), depth, expanding)
    val converted = nonNull.map { convert(it, depth + 1, expanding) }
    if (converted.isNotEmpty() && converted.all { it is ToolParameterType.Object }) return FREE_FORM_OBJECT
    if (converted.isNotEmpty() && converted.all { it is ToolParameterType.Enum }) {
      return ToolParameterType.Enum(
        converted.flatMap { (it as ToolParameterType.Enum).entries.toList() }.distinct().toTypedArray(),
      )
    }
    return ToolParameterType.String
  }

  /** What an unexpandable schema degrades to: a free-form object if it says object, else text. */
  private fun fallbackFor(schema: JsonObject): ToolParameterType =
    if (inferType(schema) == "object") FREE_FORM_OBJECT else ToolParameterType.String

  private fun describe(schema: JsonObject): String? {
    (schema["description"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
    // A `$ref` site usually carries no description of its own; the referenced type's one still helps.
    val target = schema.refName()?.let { definitions[it] } ?: return null
    return (target["description"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
  }

  private companion object {
    const val MAX_DEPTH = 8
    val UNION_KEYS = listOf("anyOf", "oneOf")
    val FREE_FORM_OBJECT = ToolParameterType.Object(emptyList(), additionalProperties = true)

    /**
     * The definition name a local `$ref` points at, decoded the way the definitions are keyed:
     * `ts-json-schema-generator` percent-encodes names (`Record%3Cstring%2Cnumber%3E` for
     * `Record<string,number>`), and a JSON Pointer escapes `~` and `/` as `~0` and `~1`. A deeper
     * pointer (`.../properties/X`) isn't a definition, so it resolves to nothing.
     */
    fun JsonObject.refName(): String? {
      val ref = (this["\$ref"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
      val encoded = when {
        ref.startsWith("#/definitions/") -> ref.removePrefix("#/definitions/")
        ref.startsWith("#/\$defs/") -> ref.removePrefix("#/\$defs/")
        else -> return null
      }
      if (encoded.isEmpty() || '/' in encoded) return null
      val decoded = try {
        URLDecoder.decode(encoded, "UTF-8")
      } catch (_: IllegalArgumentException) {
        encoded
      }
      return decoded.replace("~1", "/").replace("~0", "~")
    }

    /** Declared `type` names other than `"null"` (the schema's `type` may be a string or a union array). */
    fun JsonObject.nonNullTypes(): List<String> =
      when (val type = this["type"]) {
        is JsonPrimitive -> listOfNotNull(type.contentOrNull?.takeIf { type.isString })
        is JsonArray -> type.mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }
        else -> emptyList()
      }.filter { it != "null" }

    fun JsonObject.isNullType(): Boolean = (this["type"] as? JsonPrimitive)?.contentOrNull == "null"

    /** The type a schema implies when it omits `type` but has `properties` / `items`. */
    fun inferType(schema: JsonObject): String? =
      schema.nonNullTypes().singleOrNull()
        ?: when {
          schema["properties"] is JsonObject -> "object"
          schema["items"] != null -> "array"
          else -> null
        }

    /** String entries of an `enum` / `const`, when the schema is a string enum (or untyped). */
    fun JsonObject.stringEnumValues(): List<String>? {
      val types = nonNullTypes()
      if (types.isNotEmpty() && types != listOf("string")) return null
      val values = (this["enum"] as? JsonArray)?.map { it.asEnumString() }
        ?: this["const"]?.let { listOf(it.asEnumString()) }
        ?: return null
      // A non-string entry would make the enum a lie (Koog enums are strings); leave the type alone.
      return values.takeIf { v -> v.isNotEmpty() && v.all { it != null } }?.filterNotNull()
    }

    fun JsonElement.asEnumString(): String? = (this as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
  }
}
