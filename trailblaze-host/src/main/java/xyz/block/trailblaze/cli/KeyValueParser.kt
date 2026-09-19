package xyz.block.trailblaze.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Parses key=value argument pairs into nested maps.
 *
 * Supports three value forms:
 * 1. **Flat**: `ref="Sign In"` -> `{ref: "Sign In"}`
 * 2. **Dot-notation**: `selector.textRegex=Contacts` -> `{selector: {textRegex: "Contacts"}}`
 * 3. **JSON values**: `selector='{"textRegex":"OK"}'` or `traits='["A","B"]'` — parsed and merged
 *
 * Indexed list notation (`key[0].field=val`) groups fields into list-of-object entries.
 */
internal object KeyValueParser {

  fun parse(pairs: List<String>): Map<String, Any> {
    val root = mutableMapOf<String, Any>()
    for (pair in pairs) {
      val eqIndex = pair.indexOf('=')
      if (eqIndex <= 0) {
        throw IllegalArgumentException("Invalid argument '$pair'. Expected key=value format.")
      }
      val rawKey = pair.substring(0, eqIndex)
      val rawValue = pair.substring(eqIndex + 1).removeSurrounding("\"").removeSurrounding("'")

      val segments = parseDotSegments(rawKey)
      val value = parseValue(rawValue)
      setNestedValue(root, segments, value)
    }
    return root
  }

  internal fun parseDotSegments(key: String): List<PathSegment> {
    val indexedPattern = Regex("""^(.+)\[(\d+)]$""")
    return key.split(".").map { part ->
      val match = indexedPattern.matchEntire(part)
      if (match != null) {
        PathSegment.IndexedKey(match.groupValues[1], match.groupValues[2].toInt())
      } else {
        PathSegment.Key(part)
      }
    }
  }

  /** Parse a raw string value: detect JSON objects/arrays, then fall back to primitive inference. */
  internal fun parseValue(raw: String): Any {
    if ((raw.startsWith("{") && raw.endsWith("}")) ||
      (raw.startsWith("[") && raw.endsWith("]"))
    ) {
      return try {
        jsonElementToAny(Json.parseToJsonElement(raw))
      } catch (_: Exception) {
        inferPrimitive(raw)
      }
    }
    return inferPrimitive(raw)
  }

  /**
   * Infer Int, Double, Boolean, or String from a raw value.
   *
   * A value only becomes a number when the number prints back as exactly the text the user typed —
   * which is the only thing that matters, since a number that reproduces the text loses nothing.
   * `1.00`, `007`, `+5`, `1e3`, and a value past Int range that a Double cannot print back exactly
   * stay strings, because nothing can restore the cents a Double dropped: `amount=1.00` used to
   * reach a string-typed tool argument as `"1.0"`. (A value past Int range that a Double does print
   * back, such as `2.147483648E9`, still becomes a number — the text survives either way.)
   * A parameter the tool declares numeric still gets a number: the daemon re-aligns the
   * string from the tool's own declared type, accepting any spelling of a value a JSON number holds
   * exactly (`1.50` -> 1.5, `+5` -> 5) and leaving the rest as text.
   */
  internal fun inferPrimitive(raw: String): Any {
    raw.toIntOrNull()?.let { if (it.toString() == raw) return it }
    raw.toDoubleOrNull()?.let { if (it.isFinite() && it.toString() == raw) return it }
    return when (raw) {
      "true" -> true
      "false" -> false
      else -> raw
    }
  }

  /**
   * Convert a [JsonElement] tree to plain Kotlin types (Map/List/String/Number/Boolean).
   *
   * A scalar inside a JSON value follows the same rule as a flat one ([inferPrimitive]): it only
   * becomes a number or boolean when that prints back as exactly the text written. So
   * `amount='{"v":1.00}'` and `amounts=[1.00]` carry their cents through, and a quoted `"007"`
   * stays a string — kotlinx's `intOrNull`/`booleanOrNull` read a primitive's content without
   * asking whether it was quoted, so the quoted forms used to be renumbered too.
   */
  internal fun jsonElementToAny(element: JsonElement): Any = when (element) {
    is JsonObject -> element.entries.associate { (k, v) -> k to jsonElementToAny(v) }
    is JsonArray -> element.map { jsonElementToAny(it) }
    is JsonPrimitive -> if (element.isString) element.content else inferPrimitive(element.content)
  }

  /**
   * Walk the [segments] path into [root], creating intermediate maps/lists as needed,
   * and set the leaf to [value].
   */
  @Suppress("UNCHECKED_CAST")
  internal fun setNestedValue(
    root: MutableMap<String, Any>,
    segments: List<PathSegment>,
    value: Any,
  ) {
    var current: Any = root
    for ((i, segment) in segments.withIndex()) {
      val isLast = i == segments.lastIndex
      when (segment) {
        is PathSegment.Key -> {
          val map = current as MutableMap<String, Any>
          if (isLast) {
            map[segment.name] = value
          } else {
            current = map.getOrPut(segment.name) { mutableMapOf<String, Any>() }
          }
        }
        is PathSegment.IndexedKey -> {
          val parentMap = current as MutableMap<String, Any>
          val list =
            parentMap.getOrPut(segment.name) { mutableListOf<Any>() } as MutableList<Any>
          while (list.size <= segment.index) list.add(mutableMapOf<String, Any>())
          if (isLast) {
            list[segment.index] = value
          } else {
            current = list[segment.index]
          }
        }
      }
    }
  }
}
