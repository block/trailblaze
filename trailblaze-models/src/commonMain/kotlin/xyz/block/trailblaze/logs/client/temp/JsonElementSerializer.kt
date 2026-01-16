@file:OptIn(ExperimentalSerializationApi::class)

package xyz.block.trailblaze.logs.client.temp

import com.charleskorn.kaml.YamlInput
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlScalar
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Custom serializer for Any? that handles the basic types we expect in JSON
 */
object JsonElementSerializer : KSerializer<Any?> {
  override val descriptor: SerialDescriptor = buildClassSerialDescriptor("JsonElement")

  override fun serialize(encoder: Encoder, value: Any?) {
    when (value) {
      is String -> encoder.encodeString(value)
      is Boolean -> encoder.encodeBoolean(value)
      is Long -> encoder.encodeLong(value)
      is Double -> encoder.encodeDouble(value)
      is Int -> encoder.encodeInt(value)
      is Float -> encoder.encodeFloat(value)
      null -> encoder.encodeNull()
      is Map<*, *> -> {
        @Suppress("UNCHECKED_CAST")
        encoder.encodeSerializableValue(
          MapSerializer(String.serializer(), JsonElementSerializer),
          value as Map<String, Any?>,
        )
      }

      is List<*> -> {
        encoder.encodeSerializableValue(
          ListSerializer(JsonElementSerializer),
          value,
        )
      }

      else -> encoder.encodeString(value.toString())
    }
  }

  override fun deserialize(decoder: Decoder): Any? {
    // Handle YAML input by accessing the node directly
    if (decoder is YamlInput) {
      return yamlNodeToSerializable(decoder.node)
    }
    // Fallback for other decoders
    return decoder.decodeString()
  }

  private fun yamlNodeToSerializable(node: com.charleskorn.kaml.YamlNode): Any? = when (node) {
    is YamlScalar -> {
      val content = node.content
      when {
        content.equals("true", ignoreCase = true) -> true
        content.equals("false", ignoreCase = true) -> false
        content.toLongOrNull() != null -> content.toLong()
        content.toDoubleOrNull() != null -> content.toDouble()
        else -> content
      }
    }
    is YamlNull -> null
    is YamlMap -> node.entries.map { (key, value) ->
      key.content to yamlNodeToSerializable(value)
    }.toMap()
    is YamlList -> node.items.map { yamlNodeToSerializable(it) }
    else -> node.toString()
  }
}

/**
 * Custom serializer for OtherTrailblazeTool that flattens the structure for YAML
 */
object OtherTrailblazeToolFlatSerializer : KSerializer<OtherTrailblazeTool> {
  override val descriptor: SerialDescriptor = buildClassSerialDescriptor("OtherTrailblazeTool")

  override fun serialize(encoder: Encoder, value: OtherTrailblazeTool) {
    if (encoder is JsonEncoder) {
      // For JSON, serialize as normal structure with toolName and raw
      val jsonObj = buildJsonObject {
        put("toolName", value.toolName)
        put("raw", value.raw)
      }
      encoder.encodeJsonElement(jsonObj)
    } else {
      // For YAML, flatten the structure - promote raw contents to top level
      val flattenedMap = mutableMapOf<String, Any?>()

      // Convert raw JsonObject to serializable map
      value.raw.forEach { (key, jsonElement) ->
        flattenedMap[key] = jsonElementToSerializable(jsonElement)
      }

      encoder.encodeSerializableValue(
        MapSerializer(String.serializer(), JsonElementSerializer),
        flattenedMap,
      )
    }
  }

  override fun deserialize(decoder: Decoder): OtherTrailblazeTool = when {
    decoder is JsonDecoder -> {
      // For JSON, expect the standard structure
      val jsonElement = decoder.decodeJsonElement().jsonObject
      val toolName = jsonElement["toolName"]?.jsonPrimitive?.content ?: ""
      val raw = jsonElement["raw"]?.jsonObject ?: JsonObject(emptyMap())
      OtherTrailblazeTool(toolName, raw)
    }
    decoder is YamlInput -> {
      // For YAML, directly access the node to preserve all nested data
      val raw = yamlNodeToJsonElement(decoder.node) as? JsonObject ?: JsonObject(emptyMap())
      // toolName is NOT available at this level - it comes from the YAML key (e.g., "launchSquareAppSignedIn:")
      // which is extracted by TrailblazeToolYamlWrapperSerializer and stored in TrailblazeToolYamlWrapper.name.
      // Using a sentinel value so it's obvious if this ever leaks out incorrectly.
      OtherTrailblazeTool(YAML_CONTEXT_TOOL_NAME_PLACEHOLDER, raw)
    }
    else -> {
      // Fallback: try to decode as a map - this shouldn't normally be reached
      val map = decoder.decodeSerializableValue(
        MapSerializer(String.serializer(), JsonElementSerializer),
      )
      val raw = JsonObject(
        map.mapValues { (_, value) ->
          serializableToJsonElement(value)
        },
      )
      OtherTrailblazeTool(YAML_CONTEXT_TOOL_NAME_PLACEHOLDER, raw)
    }
  }

  /**
   * Sentinel value used when deserializing from YAML context where the tool name
   * is not available at this level. The actual tool name is extracted from the
   * YAML key by [TrailblazeToolYamlWrapperSerializer] and stored in
   * [TrailblazeToolYamlWrapper.name].
   *
   * If you see this value in logs or output, it indicates the tool name wasn't
   * properly propagated from the wrapper.
   */
  private const val YAML_CONTEXT_TOOL_NAME_PLACEHOLDER = "__YAML_CONTEXT_TOOL_NAME_NOT_AVAILABLE__"

  private fun yamlNodeToJsonElement(node: com.charleskorn.kaml.YamlNode): JsonElement = when (node) {
    is YamlScalar -> {
      val content = node.content
      when {
        content.equals("true", ignoreCase = true) -> JsonPrimitive(true)
        content.equals("false", ignoreCase = true) -> JsonPrimitive(false)
        content.toLongOrNull() != null -> JsonPrimitive(content.toLong())
        content.toDoubleOrNull() != null -> JsonPrimitive(content.toDouble())
        else -> JsonPrimitive(content)
      }
    }
    is YamlNull -> JsonNull
    is YamlMap -> JsonObject(
      node.entries.map { (key, value) ->
        key.content to yamlNodeToJsonElement(value)
      }.toMap()
    )
    is YamlList -> JsonArray(node.items.map { yamlNodeToJsonElement(it) })
    else -> JsonPrimitive(node.toString())
  }

  private fun jsonElementToSerializable(jsonElement: JsonElement): Any? = when (jsonElement) {
    is JsonPrimitive -> {
      when {
        jsonElement.isString -> jsonElement.content
        jsonElement.content == "true" -> true
        jsonElement.content == "false" -> false
        jsonElement.content.toLongOrNull() != null -> jsonElement.content.toLong()
        jsonElement.content.toDoubleOrNull() != null -> jsonElement.content.toDouble()
        else -> jsonElement.content
      }
    }

    is JsonNull -> null
    is JsonObject -> jsonElement.mapValues { (_, elem) -> jsonElementToSerializable(elem) }
    is JsonArray -> jsonElement.map { elem -> jsonElementToSerializable(elem) }
  }

  private fun serializableToJsonElement(value: Any?): JsonElement = when (value) {
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
}
