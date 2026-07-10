package xyz.block.trailblaze.toolcalls

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
 */
fun coerceArgsToDescriptorTypes(args: JsonObject, descriptor: TrailblazeToolDescriptor): JsonObject {
  if (args.isEmpty()) return args
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
  content.toDoubleOrNull()?.let { if (it.toString() == content) return JsonPrimitive(it) }
  return null
}
