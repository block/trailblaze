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
  fun yamlNodeToJsonElement(node: YamlNode): JsonElement = when (node) {
    is YamlScalar -> scalarToJsonPrimitive(node)
    is YamlNull -> JsonNull
    is YamlMap -> JsonObject(
      node.entries.map { (key, value) ->
        key.content to yamlNodeToJsonElement(value)
      }.toMap(),
    )
    is YamlList -> JsonArray(node.items.map { yamlNodeToJsonElement(it) })
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

  private fun scalarToJsonPrimitive(node: YamlScalar): JsonPrimitive {
    val content = node.content
    return when {
      content.equals("true", ignoreCase = true) -> JsonPrimitive(true)
      content.equals("false", ignoreCase = true) -> JsonPrimitive(false)
      content.toLongOrNull() != null -> JsonPrimitive(content.toLong())
      content.toDoubleOrNull() != null -> JsonPrimitive(content.toDouble())
      else -> JsonPrimitive(content)
    }
  }
}
