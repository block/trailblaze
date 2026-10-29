package xyz.block.trailblaze.trailhead

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * The `trailRun.events.query` match vocabulary: the survey vocabulary minus predicates, since a
 * function can't cross from JavaScript.
 *
 *  - a string, number, boolean or null matches by equality (numbers compare numerically);
 *  - `{ "$regex": source, "$flags": flags }` is a `RegExp` sent from JS, tested against the value's
 *    string form (an object or array value is tested against its JSON). Flags `i` and `m` apply,
 *    `g` changes nothing for a yes/no test, and any other flag is rejected rather than ignored;
 *  - any other object is a subset match: every key it names must match, recursively.
 *
 * A match that names a key the record lacks fails, except a `null` match, which also accepts an
 * absent key.
 */
class EventMatch private constructor(private val match: JsonObject) {

  fun matches(record: JsonElement): Boolean = matchesObject(match, record)

  companion object {
    const val REGEX_KEY: String = "\$regex"
    const val FLAGS_KEY: String = "\$flags"
    private const val SUPPORTED_FLAGS = "gim"

    /**
     * Parse [match], compiling every regex up front so a bad pattern fails the query instead of
     * silently matching nothing.
     */
    fun parse(match: JsonObject): EventMatch {
      validate(match)
      return EventMatch(match)
    }

    private fun validate(element: JsonElement) {
      when (element) {
        is JsonObject -> if (isRegex(element)) toRegex(element) else element.values.forEach(::validate)
        is JsonArray -> throw IllegalArgumentException("A match can't hold an array; match one field at a time.")
        else -> Unit
      }
    }

    private fun isRegex(obj: JsonObject): Boolean = obj.keys.contains(REGEX_KEY)

    private fun toRegex(obj: JsonObject): Regex {
      val source = (obj[REGEX_KEY] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw IllegalArgumentException("$REGEX_KEY must be a string")
      val flags = (obj[FLAGS_KEY] as? JsonPrimitive)?.content.orEmpty()
      val unsupported = flags.filterNot { it in SUPPORTED_FLAGS }
      require(unsupported.isEmpty()) {
        "regex flags '$unsupported' are not supported; only g, i and m are"
      }
      val options = buildSet {
        if ('i' in flags) add(RegexOption.IGNORE_CASE)
        if ('m' in flags) add(RegexOption.MULTILINE)
      }
      return Regex(source, options)
    }

    private fun matchesObject(match: JsonObject, value: JsonElement): Boolean {
      val record = value as? JsonObject ?: return false
      return match.all { (key, expected) ->
        val actual = record[key]
        if (actual == null) expected is JsonNull else matchesValue(expected, actual)
      }
    }

    private fun matchesValue(expected: JsonElement, actual: JsonElement): Boolean = when (expected) {
      is JsonObject ->
        if (isRegex(expected)) {
          val text = (actual as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content ?: actual.toString()
          toRegex(expected).containsMatchIn(text)
        } else {
          matchesObject(expected, actual)
        }
      is JsonNull -> actual is JsonNull
      is JsonPrimitive -> matchesPrimitive(expected, actual)
      is JsonArray -> false
    }

    private fun matchesPrimitive(expected: JsonPrimitive, actual: JsonElement): Boolean {
      val other = actual as? JsonPrimitive ?: return false
      if (other is JsonNull) return false
      if (expected.isString) return other.isString && other.content == expected.content
      if (other.isString) return false
      expected.booleanOrNull?.let { return other.booleanOrNull == it }
      val e = expected.doubleOrNull ?: return false
      return other.doubleOrNull == e
    }
  }
}
