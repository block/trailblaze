package xyz.block.trailblaze.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull

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

  /** Infer Int, Double, Boolean, or String from a raw value. */
  internal fun inferPrimitive(raw: String): Any = when {
    raw.toIntOrNull() != null -> raw.toInt()
    raw.toDoubleOrNull() != null -> raw.toDouble()
    raw == "true" -> true
    raw == "false" -> false
    else -> raw
  }

  /** Convert a [JsonElement] tree to plain Kotlin types (Map/List/String/Number/Boolean). */
  internal fun jsonElementToAny(element: JsonElement): Any = when (element) {
    is JsonObject -> element.entries.associate { (k, v) -> k to jsonElementToAny(v) }
    is JsonArray -> element.map { jsonElementToAny(it) }
    is JsonPrimitive -> when {
      element.booleanOrNull != null -> element.boolean
      element.intOrNull != null -> element.int
      element.doubleOrNull != null -> element.double
      else -> element.content
    }
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
