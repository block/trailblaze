package xyz.block.trailblaze.toolcalls

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Re-aligns a scripted tool's decoded argument object so each value's JSON type matches the type
 * its [descriptor] declares.
 *
 * A recorded `.trail.yaml` step's YAML→JSON decode guesses a scalar's type from its content
 * because kaml discards the source quote style — a recorded quoted string like a passcode
 * `'12345678'` or a flag value `'true'` surfaces as a JSON number/boolean. On replay the scripted
 * tool then receives the wrong JS type (a number where it declared a string) and crashes on the
 * first string op. This is the single, tool-agnostic version of the per-tool `String(x)` casts
 * that previously papered over it — applied once at dispatch from the tool's own declared types.
 *
 * Only same-value scalar reinterpretations are performed, and only when [descriptor] declares the
 * field:
 *  - declared `string`, value is a number/boolean → the value's textual form as a string
 *  - declared `number`/`integer`, value is a *canonical* numeric string → a JSON number
 *  - declared `boolean`, value is `"true"`/`"false"` (any case) → a JSON boolean
 *
 * Everything else is left untouched: unknown keys, object/array values, nulls, and any value
 * already matching its declared type. A numeric string that is not canonical (a zero-padded
 * `"0130"`, a phone number) is never turned into a number, so no significant digits are lost —
 * mirroring the round-trip guard in `YamlJsonBridge.scalarToJsonPrimitive`.
 *
 * When [descriptor] carries a full [TrailblazeToolDescriptor.inputSchema], coercion is
 * schema-driven and RECURSES into nested objects and arrays-of-objects (see
 * [coerceArgsToSchemaTypes]) so a scalar buried inside e.g. `overrides[].value` is re-aligned the
 * same way a top-level scalar is. Absent a schema, only the flat top-level parameter view is used.
 */
fun coerceArgsToDescriptorTypes(args: JsonObject, descriptor: TrailblazeToolDescriptor): JsonObject {
  if (args.isEmpty()) return args
  // A known JSON Schema is the richer oracle — it retains array `items` and nested `properties`
  // the flat parameter split can't. Top-level scalar coercion is identical either way (both read
  // the property's declared `type`); the schema path just additionally reaches nested values.
  descriptor.inputSchema?.let { return coerceArgsToSchemaTypes(args, it) }

  val typeByName = HashMap<String, String>()
  for (p in descriptor.requiredParameters) typeByName[p.name] = p.type
  for (p in descriptor.optionalParameters) typeByName[p.name] = p.type
  if (typeByName.isEmpty()) return args

  var changed = false
  val out = LinkedHashMap<String, JsonElement>(args.size)
  for ((key, value) in args) {
    val declared = typeByName[key]
    // JsonNull is a JsonPrimitive but represents an explicit null — never rewrite it (that would
    // turn `arg: null` into the literal string "null" for a string param). Leave it for the tool.
    val coerced =
      if (declared != null && value is JsonPrimitive && value !is JsonNull) {
        coerceScalar(value, declared)
      } else {
        value
      }
    if (coerced !== value) changed = true
    out[key] = coerced
  }
  return if (changed) JsonObject(out) else args
}

/**
 * Schema-driven variant of [coerceArgsToDescriptorTypes] that walks a JSON Schema
 * (`{ type: object, properties: {...} }`) and re-aligns scalar values to their declared type at
 * every depth — top-level, inside nested objects (`type: object` with `properties`), and inside
 * arrays (`type: array` with an object/scalar `items` schema). Same same-value-only scalar guards
 * as the flat path (never rewrites explicit `null`; never turns a non-canonical numeric string
 * into a number).
 *
 * Local `$ref`s are resolved against the root schema's `definitions` / `$defs` blocks (the shape
 * `ts-json-schema-generator` emits for a named nested type — `overrides.items` becomes
 * `{ "$ref": "#/definitions/AndroidFeatureFlagOverride" }` rather than an inline object), so a
 * scalar buried inside a `$ref`-typed nested object / array-of-objects is still reached. Only local
 * `#/definitions/<name>` and `#/$defs/<name>` refs are followed; a non-local ref, an unresolvable
 * name, or a `$ref` cycle is left untouched.
 *
 * Conservative by construction: a value whose (ref-resolved) property/items schema declares no
 * `type` is left untouched (so a bare `anyOf` prop never misfires), and any structure the schema
 * doesn't model (unknown key, array with no `items`, primitive where the schema says object) passes
 * through unchanged. Returns [args] itself when nothing changed.
 *
 * `internal` — an implementation detail of [coerceArgsToDescriptorTypes] (the public entry point),
 * exposed to this module's tests but deliberately kept off the published API surface.
 */
internal fun coerceArgsToSchemaTypes(args: JsonObject, inputSchema: JsonObject): JsonObject {
  if (args.isEmpty()) return args
  val definitions = collectDefinitions(inputSchema)
  return coerceObjectAgainstSchema(args, resolveRef(inputSchema, definitions), definitions)
}

/** Coerce a decoded object's properties against a (ref-resolved) object schema. */
private fun coerceObjectAgainstSchema(
  args: JsonObject,
  schema: JsonObject,
  definitions: Map<String, JsonObject>,
): JsonObject {
  val properties = schema["properties"] as? JsonObject ?: return args
  var changed = false
  val out = LinkedHashMap<String, JsonElement>(args.size)
  for ((key, value) in args) {
    val propSchema = properties[key] as? JsonObject
    val coerced = if (propSchema != null) coerceValueToSchema(value, propSchema, definitions) else value
    if (coerced !== value) changed = true
    out[key] = coerced
  }
  return if (changed) JsonObject(out) else args
}

/** Coerce a single value against its property/items schema, recursing into objects and arrays. */
private fun coerceValueToSchema(
  value: JsonElement,
  rawSchema: JsonObject,
  definitions: Map<String, JsonObject>,
): JsonElement {
  val propSchema = resolveRef(rawSchema, definitions)
  return when (value) {
    // JsonNull is a JsonPrimitive; check it first so an explicit null is never rewritten.
    is JsonNull -> value
    is JsonPrimitive -> {
      val declared = declaredScalarType(propSchema)
      if (declared != null) coerceScalar(value, declared) else value
    }
    is JsonObject ->
      // Recurse only when the schema models this as an object with properties; otherwise leave it.
      if (propSchema["properties"] is JsonObject) {
        coerceObjectAgainstSchema(value, propSchema, definitions)
      } else {
        value
      }
    is JsonArray -> {
      val itemsSchema = propSchema["items"] as? JsonObject
      if (itemsSchema == null) {
        value
      } else {
        var changed = false
        val outList = ArrayList<JsonElement>(value.size)
        for (element in value) {
          val coerced = coerceValueToSchema(element, itemsSchema, definitions)
          if (coerced !== element) changed = true
          outList.add(coerced)
        }
        if (changed) JsonArray(outList) else value
      }
    }
  }
}

/**
 * The scalar type a property schema declares, or null if it declares none. Handles a union
 * `type` array — `{ "type": ["string", "null"] }`, the shape `ts-json-schema-generator` emits for a
 * nullable scalar — by taking the first non-`"null"` member, so a nullable-scalar property is
 * coerced the same as a plain one. This mirrors the flat path, which defaults such a property to
 * `"string"`; without it a non-null schema would silently skip coercion the flat path performed.
 */
private fun declaredScalarType(propSchema: JsonObject): String? =
  when (val type = propSchema["type"]) {
    is JsonPrimitive -> if (type.isString) type.content else null
    is JsonArray ->
      type.mapNotNull { (it as? JsonPrimitive)?.let { p -> if (p.isString) p.content else null } }
        .firstOrNull { it != "null" }
    else -> null
  }

/** The root schema's `definitions` + `$defs` object entries, keyed by name (both blocks merged). */
private fun collectDefinitions(root: JsonObject): Map<String, JsonObject> {
  val out = LinkedHashMap<String, JsonObject>()
  (root["definitions"] as? JsonObject)?.forEach { (name, v) -> (v as? JsonObject)?.let { out[name] = it } }
  (root["\$defs"] as? JsonObject)?.forEach { (name, v) -> (v as? JsonObject)?.let { out[name] = it } }
  return out
}

/**
 * Follow a chain of local `$ref`s (`#/definitions/<name>` or `#/$defs/<name>`) to the schema they
 * point at. A schema with no `$ref`, a non-local ref, an unresolvable name, or a ref cycle resolves
 * to itself (conservative — coercion then finds no `type`/`properties` and leaves the value alone).
 */
private fun resolveRef(schema: JsonObject, definitions: Map<String, JsonObject>): JsonObject {
  // Fast path — the overwhelmingly common inline schema has no `$ref`; skip the HashSet alloc.
  if (schema["\$ref"] == null) return schema
  var current = schema
  val seen = HashSet<String>()
  while (true) {
    val ref = (current["\$ref"] as? JsonPrimitive)?.let { if (it.isString) it.content else null } ?: return current
    if (!seen.add(ref)) return current
    val name = when {
      ref.startsWith("#/definitions/") -> ref.removePrefix("#/definitions/")
      ref.startsWith("#/\$defs/") -> ref.removePrefix("#/\$defs/")
      else -> return current
    }
    current = definitions[name] ?: return current
  }
}

private fun coerceScalar(value: JsonPrimitive, declaredType: String): JsonPrimitive {
  val content = value.content
  return when (declaredType.lowercase()) {
    "string" ->
      // A number/boolean literal that should have been a string (the passcode / flag-value bug).
      if (value.isString) value else JsonPrimitive(content)

    "number", "integer", "int", "long", "float", "double" ->
      if (value.isString) numericOrNull(content) ?: value else value

    "boolean", "bool" ->
      if (value.isString) {
        when (content.lowercase()) {
          "true" -> JsonPrimitive(true)
          "false" -> JsonPrimitive(false)
          else -> value
        }
      } else {
        value
      }

    else -> value
  }
}

/** A JSON number primitive only when [content] round-trips exactly (no leading-zero / overflow loss). */
private fun numericOrNull(content: String): JsonPrimitive? {
  content.toLongOrNull()?.let { if (it.toString() == content) return JsonPrimitive(it) }
  // `isFinite` rejects "NaN"/"Infinity"/"-Infinity" — those satisfy the round-trip check but are
  // not valid JSON numbers, and the default kotlinx `Json` throws when re-encoding them. Leave such
  // a value as its original string.
  content.toDoubleOrNull()?.let { if (it.isFinite() && it.toString() == content) return JsonPrimitive(it) }
  return null
}
