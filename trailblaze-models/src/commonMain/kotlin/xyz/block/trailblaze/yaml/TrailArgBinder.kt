package xyz.block.trailblaze.yaml

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import xyz.block.trailblaze.config.DefaultBehavior

/**
 * Binds a parameterized trail's declared [TrailArgConfig]s against the values a caller supplied,
 * producing the typed `Map<String, JsonElement>` seeded into the `args.` resolution namespace.
 *
 * Pure and declaration-driven. Coercion follows the DECLARED type, never the value's shape — a
 * quoted `'123'` bound to an `integer` arg is `123`; a bare `123` bound to a `string` arg is `"123"`.
 * There is no value-guessing and **no null in the args domain** (optional ≠ nullable): an omitted
 * optional arg takes its declared `default:`; a YAML-null provided value is a loud error.
 *
 * Binding is validation: a missing required arg, an unknown arg, a null value, or a value that
 * can't coerce to its declared type all fail before any device or LLM work.
 */
object TrailArgBinder {

  sealed interface BindResult {
    data class Success(val args: Map<String, JsonElement>) : BindResult

    /** [message] is a single human-readable, actionable line safe to surface as a CLI error. */
    data class Failure(val message: String) : BindResult
  }

  private val WIRE_JSON = Json { isLenient = false }

  /**
   * The wire form of provided arg values used to carry them from the CLI to the runner:
   * a `Map<String, String>` where each value is a JSON-encoded [JsonElement]. `--arg KEY=VAL`
   * encodes `JsonPrimitive(VAL)` (always a string); an `--args-file` entry encodes its parsed
   * YAML→JSON element (so a YAML null encodes as JSON `null` and is caught as the null error, and a
   * quoted vs. bare scalar keeps its string-vs-number identity for declaration-driven coercion).
   */
  fun encodeProvided(values: Map<String, JsonElement>): Map<String, String> =
    values.mapValues { WIRE_JSON.encodeToString(JsonElement.serializer(), it.value) }

  /** Inverse of [encodeProvided]. */
  fun decodeProvided(wire: Map<String, String>): Map<String, JsonElement> =
    wire.mapValues { WIRE_JSON.parseToJsonElement(it.value) }

  /**
   * @param declared the trail's `config.args:` (null/empty ⇒ the trail declares no args).
   * @param provided merged provided values (`--args-file` < `--arg` precedence already applied),
   *   each a [JsonElement] (see [decodeProvided]).
   */
  fun bind(
    declared: Map<String, TrailArgConfig>?,
    provided: Map<String, JsonElement>,
  ): BindResult {
    val declaredArgs = declared ?: emptyMap()

    val unknown = provided.keys - declaredArgs.keys
    if (unknown.isNotEmpty()) {
      val declaredList = if (declaredArgs.isEmpty()) "none (the trail declares no `config.args:`)"
      else declaredArgs.keys.sorted().toString()
      return BindResult.Failure(
        "Unknown arg(s) ${unknown.sorted()} — not declared under `config.args:`. Declared: $declaredList.",
      )
    }

    val bound = LinkedHashMap<String, JsonElement>()
    val missing = mutableListOf<String>()
    for ((name, config) in declaredArgs) {
      val providedValue = provided[name]
      when {
        providedValue != null -> {
          if (providedValue is JsonNull) {
            return BindResult.Failure(
              "Arg '$name' was given a null value — args have no null (optional is not nullable). " +
                "Use '' for an empty string, or omit the arg to take its default.",
            )
          }
          when (val coerced = coerce(name, config.type, providedValue)) {
            is CoerceResult.Ok -> bound[name] = coerced.value
            is CoerceResult.Err -> return BindResult.Failure(coerced.message)
          }
        }
        config.default is DefaultBehavior.Use -> {
          // A declared default is coerced by the same rule, so `default: '007'` on a string arg
          // keeps "007" and `default: 3` on an integer arg stays 3. Token-valued string defaults
          // (`'{{memory.email}}'`) pass through as their token text and resolve at seed time.
          when (val coerced = coerce(name, config.type, config.default.value)) {
            is CoerceResult.Ok -> bound[name] = coerced.value
            is CoerceResult.Err -> return BindResult.Failure(
              "Default for arg '$name' is invalid: ${coerced.message}",
            )
          }
        }
        else -> missing += name
      }
    }

    if (missing.isNotEmpty()) {
      return BindResult.Failure(
        "Missing required arg(s): ${missing.sorted()}. Pass each as `--arg <name>=<value>` " +
          "(or in an `--args-file`), or declare a `default:` to make it optional.",
      )
    }

    // A string value may itself carry `args.` tokens (a sibling reference, or a token-valued
    // default) that resolve at seed time — where a malformed token (an expression) hard-errors.
    // Binding is validation, so catch it here and fail as a clean input error instead.
    for ((name, value) in bound) {
      if (value !is JsonPrimitive || !value.isString) continue
      val malformed = TrailArgTokens.scanArgsTokens(value.content)
        .firstOrNull { !TrailArgTokens.isValidDottedPath(it.body) }
        ?: continue
      return BindResult.Failure(
        "Arg '$name' has a malformed args token in its value: " +
          TrailArgTokens.malformedTokenMessage(malformed.body),
      )
    }
    return BindResult.Success(bound)
  }

  private sealed interface CoerceResult {
    data class Ok(val value: JsonElement) : CoerceResult
    data class Err(val message: String) : CoerceResult
  }

  private fun coerce(name: String, type: String, element: JsonElement): CoerceResult = when (type) {
    TrailArgConfig.STRING -> {
      val s = element.scalarContentOrNull()
        ?: return CoerceResult.Err("Arg '$name' expects a string, got a ${element.shapeName()}.")
      CoerceResult.Ok(JsonPrimitive(s))
    }
    TrailArgConfig.INTEGER -> {
      val long = element.asLongOrNull()
        ?: return CoerceResult.Err(
          "Arg '$name' expects an integer, got ${element.describeValue()}.",
        )
      CoerceResult.Ok(JsonPrimitive(long))
    }
    TrailArgConfig.BOOLEAN -> {
      val bool = element.asBooleanOrNull()
        ?: return CoerceResult.Err(
          "Arg '$name' expects a boolean (true/false), got ${element.describeValue()}.",
        )
      CoerceResult.Ok(JsonPrimitive(bool))
    }
    // Accepted-but-deferred: the value must actually BE an array/object (binding is validation,
    // even for a type whose substitution isn't executed yet) — declaring and supplying them is
    // legal today so a trail's contract can be authored ahead of execution. `--arg` can only ever
    // wire-encode a plain string, so a real array/object value can only arrive via `--args-file`.
    TrailArgConfig.ARRAY -> {
      if (element !is JsonArray) {
        return CoerceResult.Err("Arg '$name' expects an array, got a ${element.shapeName()}.")
      }
      CoerceResult.Ok(element)
    }
    TrailArgConfig.OBJECT -> {
      if (element !is JsonObject) {
        return CoerceResult.Err("Arg '$name' expects an object, got a ${element.shapeName()}.")
      }
      CoerceResult.Ok(element)
    }
    else -> CoerceResult.Err("Arg '$name' has unsupported type '$type'.")
  }

  private fun JsonElement.scalarContentOrNull(): String? =
    (this as? JsonPrimitive)?.contentOrNull

  private fun JsonElement.asLongOrNull(): Long? {
    val p = this as? JsonPrimitive ?: return null
    // A real JSON number (from an args-file) or a numeric string (from `--arg`); anything else —
    // booleans, non-numeric strings — has no long form and falls through to null.
    return p.longOrNull ?: p.content.toLongOrNull()
  }

  private fun JsonElement.asBooleanOrNull(): Boolean? {
    val p = this as? JsonPrimitive ?: return null
    return when (p.content.lowercase()) {
      "true" -> true
      "false" -> false
      else -> null
    }
  }

  private fun JsonElement.shapeName(): String = when (this) {
    is JsonArray -> "array"
    is JsonObject -> "object"
    is JsonNull -> "null"
    is JsonPrimitive -> if (isString) "string" else "scalar"
  }

  private fun JsonElement.describeValue(): String = when (this) {
    is JsonPrimitive -> "'${content}'"
    else -> "a ${shapeName()}"
  }
}
