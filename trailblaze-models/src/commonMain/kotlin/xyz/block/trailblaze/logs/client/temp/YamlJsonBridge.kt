package xyz.block.trailblaze.logs.client.temp

import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlScalar
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object YamlJsonBridge {
  /**
   * @param coerceNumbers Whether a scalar that round-trips losslessly through `Long`/`Double`
   * (see [scalarToJsonPrimitive]) should become a JSON number. Defaults to `true`, preserving
   * behavior for every existing caller. Pass `false` when the scalar's content is known to
   * ALWAYS be free-form text at the schema level (e.g. Maestro's `InputTextCommand.text`) — there,
   * a "canonical number" string like a verification code (`"123123"`) or a numeric button label
   * is never actually a number, and the target reader (e.g. Maestro's own YAML parser) already
   * coerces a quoted numeric string into whatever numeric type it needs, so staying a string is
   * always safe and avoids the guess entirely. See [MaestroTrailblazeToolSerializer]'s use.
   */
  fun yamlNodeToJsonElement(node: YamlNode, coerceNumbers: Boolean = true): JsonElement = when (node) {
    is YamlScalar -> scalarToJsonPrimitive(node, coerceNumbers)
    is YamlNull -> JsonNull
    is YamlMap -> JsonObject(
      node.entries.map { (key, value) ->
        key.content to yamlNodeToJsonElement(value, coerceNumbers)
      }.toMap(),
    )
    is YamlList -> JsonArray(node.items.map { yamlNodeToJsonElement(it, coerceNumbers) })
    else -> JsonPrimitive(node.toString())
  }

  fun jsonElementToSerializable(jsonElement: JsonElement): Any? = when (jsonElement) {
    is JsonPrimitive -> when {
      jsonElement.isString -> jsonElement.content
      jsonElement.content == "true" -> true
      jsonElement.content == "false" -> false
      jsonElement.content.toLongOrNull() != null -> jsonElement.content.toLong()
      jsonElement.content.toDoubleOrNull() != null -> jsonElement.content.toDouble()
      else -> jsonElement.content
    }
    is JsonNull -> null
    is JsonObject -> jsonElement.mapValues { (_, elem) -> jsonElementToSerializable(elem) }
    is JsonArray -> jsonElement.map { elem -> jsonElementToSerializable(elem) }
  }

  fun serializableToJsonElement(value: Any?): JsonElement = when (value) {
    is String -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    null -> JsonNull
    is Map<*, *> -> {
      @Suppress("UNCHECKED_CAST")
      val stringMap = value as Map<String, Any?>
      JsonObject(stringMap.mapValues { (_, v) -> serializableToJsonElement(v) })
    }
    is List<*> -> JsonArray(value.map { elem -> serializableToJsonElement(elem) })
    else -> JsonPrimitive(value.toString())
  }

  fun yamlNodeToSerializable(node: YamlNode): Any? =
    jsonElementToSerializable(yamlNodeToJsonElement(node))

  private fun scalarToJsonPrimitive(node: YamlScalar, coerceNumbers: Boolean): JsonPrimitive {
    val content = node.content
    return when {
      content.equals("true", ignoreCase = true) -> JsonPrimitive(true)
      content.equals("false", ignoreCase = true) -> JsonPrimitive(false)
      !coerceNumbers -> JsonPrimitive(content)
      // Treat a scalar as a number ONLY when it round-trips exactly. kaml's [YamlScalar] discards the
      // quote style — `"0000"` and `0000` both surface as `content == "0000"` — so a naive
      // `toLongOrNull()` lossily coerces a zero-padded string (a PIN `"0000"`, a zip code `"07928"`, a
      // phone number) to a number, dropping the significant leading zeros. That silently broke scripted
      // (TypeScript) tools whose recorded string args flow through this bridge: a text-input tool would
      // receive the number `0` and type `"0"` instead of `"0000"`, so a PIN never submitted. Requiring
      // `toString()` to reproduce the original keeps those as strings (matching how the former Kotlin
      // `@Serializable` String-typed tool fields decoded them), while genuine canonical numbers
      // (`"5"`, `"123123"`, `"-4"`, `"3.5"`) still decode as numbers — UNLESS the caller already knows
      // (`coerceNumbers = false`) that this content is always free-form text, in which case even a
      // "clean" numeric-looking string like a verification code must stay a string.
      content.toLongOrNull()?.toString() == content -> JsonPrimitive(content.toLong())
      content.toDoubleOrNull()?.toString() == content -> JsonPrimitive(content.toDouble())
      else -> JsonPrimitive(content)
    }
  }
}
