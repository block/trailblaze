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
 *  - declared `number`/`integer`, value is a numeric string naming that exact value → a JSON number
 *  - declared `boolean`, value is `"true"`/`"false"` (any case) → a JSON boolean
 *
 * Everything else is left untouched: unknown keys, object/array values, nulls, and any value
 * already matching its declared type. A numeric string is only turned into a number when the number
 * holds that exact value, so no significant digit is ever lost: a zero-padded `"0130"` and a phone
 * number stay strings, while a differently-spelled but equal `"1.50"` or `"+5"` becomes a number
 * (see [numericOrNull]).
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

/**
 * A JSON number primitive only when [content] names a value a JSON number holds exactly.
 *
 * Any plain decimal spelling of that value is accepted, not just the shortest one: a leading `+`,
 * trailing zeros in the fraction (`1.50`), and a value whose printed form uses exponent notation
 * while the text does not (`0.0001`, `10000000.50`). That matters because the CLI hands the daemon
 * the text the user typed — `amount=1.50` arrives as the string `"1.50"` rather than a Double that
 * already dropped the cents — so without this a parameter the tool declares a number would receive
 * a string.
 *
 * Leading zeros are deliberately NOT stripped: a zero-padded `"0130"` is far more likely a code
 * whose schema mistyped it than the number 130, and keeping it a string is the recoverable choice.
 * An exponent form (`1e3`) stays a string for the same reason. Anything that would lose digits — a
 * value past Long range, more precision than a Double holds, an integer wider than a scripted tool's
 * own number type — also stays a string.
 */
private fun numericOrNull(content: String): JsonPrimitive? {
  exactNumericOrNull(content)?.let { return it }
  if (!PLAIN_DECIMAL_LITERAL.matches(content)) return null
  val unsigned = content.removePrefix("+").removePrefix("-")
  val negative = content.startsWith("-")
  val fraction = unsigned.substringAfter('.', "").trimEnd('0')

  if (fraction.isEmpty()) {
    // An integer, however it was spelled (`+5`, `2.00`). A scripted tool receives its arguments
    // through `JSON.parse`, and every JavaScript number is a double, so an integer past 2^53
    // arrives as a *different* number (9007199254740993 -> ...992). Keep the text: the tool's own
    // schema then rejects it by name, which the caller can act on, instead of the tool running on
    // a value nobody typed. A magnitude past Long range is that same case, further out.
    val magnitude = unsigned.substringBefore('.').toLongOrNull() ?: return null
    if (magnitude > MAX_EXACT_JS_INTEGER) return null
    // `-0` / `-0.00` is negative zero. A Long has no sign to give it, so hand it over as the double
    // that does — a tool computing `1 / scale` then gets -Infinity, not Infinity.
    if (magnitude == 0L && negative) return JsonPrimitive(-0.0)
    return JsonPrimitive(if (negative) -magnitude else magnitude)
  }

  val value = content.toDoubleOrNull() ?: return null
  if (!value.isFinite()) return null
  // Accept only when the double denotes the very value that was written, so nothing is silently
  // rounded (`1.00000000000000000001` stays text). Compare the two as VALUES, not as text:
  // `Double.toString` switches to exponent notation below 1e-3 and at/above 1e7, so a textual
  // comparison would reject plain decimals such as `0.0001` and `10000000.50`.
  if (decimalValueKeyOrNull(content) != decimalValueKeyOrNull(value.toString())) return null
  return JsonPrimitive(value)
}

/**
 * [text] reduced to a canonical key for the decimal value it denotes — sign, significant digits,
 * and a power of ten — so two spellings of one value compare equal whatever notation each uses.
 * Accepts a plain decimal and the exponent form `Double.toString` produces; null when [text] is
 * neither.
 */
private fun decimalValueKeyOrNull(text: String): String? {
  val negative = text.startsWith("-")
  val unsigned = text.removePrefix("+").removePrefix("-")
  val exponentAt = unsigned.indexOfFirst { it == 'e' || it == 'E' }
  val mantissa = if (exponentAt < 0) unsigned else unsigned.substring(0, exponentAt)
  val exponent = if (exponentAt < 0) 0 else unsigned.substring(exponentAt + 1).toIntOrNull() ?: return null
  val digits = mantissa.substringBefore('.') + mantissa.substringAfter('.', "")
  if (digits.isEmpty() || digits.any { !it.isDigit() }) return null
  // The value is `0.<digits> * 10^pointExponent`; dropping a leading zero shifts that exponent,
  // dropping a trailing one does not.
  val significant = digits.trimStart('0')
  val pointExponent = mantissa.substringBefore('.').length + exponent - (digits.length - significant.length)
  val trimmed = significant.trimEnd('0')
  if (trimmed.isEmpty()) return "0"
  return "${if (negative) "-" else ""}${trimmed}e$pointExponent"
}

/** 2^53 - 1 — the widest integer magnitude an IEEE-754 double, and so a JavaScript number, holds exactly. */
private const val MAX_EXACT_JS_INTEGER = 9007199254740991L

/** A number primitive only when [content] is already exactly the text that number prints back. */
private fun exactNumericOrNull(content: String): JsonPrimitive? {
  content.toLongOrNull()?.let { if (it.toString() == content) return JsonPrimitive(it) }
  // `isFinite` rejects "NaN"/"Infinity"/"-Infinity" — those satisfy the round-trip check but are
  // not valid JSON numbers, and the default kotlinx `Json` throws when re-encoding them. Leave such
  // a value as its original string.
  content.toDoubleOrNull()?.let { if (it.isFinite() && it.toString() == content) return JsonPrimitive(it) }
  return null
}

/**
 * A decimal with no exponent and no leading zero — `5`, `+5`, `-0.0001`, `2.00`. The unpadded
 * integer part is what keeps a zero-padded code (`0130`, `01.5`) out of the numeric path.
 */
private val PLAIN_DECIMAL_LITERAL = Regex("""[+-]?(0|[1-9]\d*)(\.\d+)?""")
