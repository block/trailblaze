package xyz.block.trailblaze.logs.client.temp

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

  override fun deserialize(decoder: Decoder): Any {
    // This is a simplified deserialization - in practice, YAML parsing will handle types
    return decoder.decodeString()
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

  override fun deserialize(decoder: Decoder): OtherTrailblazeTool = if (decoder is JsonDecoder) {
    // For JSON, expect the standard structure
    val jsonElement = decoder.decodeJsonElement().jsonObject
    val toolName = jsonElement["toolName"]?.jsonPrimitive?.content ?: ""
    val raw = jsonElement["raw"]?.jsonObject ?: JsonObject(emptyMap())
    OtherTrailblazeTool(toolName, raw)
  } else {
    // For YAML, the structure is flattened - we can't determine toolName from content
    val map = decoder.decodeSerializableValue(
      MapSerializer(String.serializer(), JsonElementSerializer),
    )

    val raw = JsonObject(
      map.mapValues { (_, value) ->
        serializableToJsonElement(value)
      },
    )

    // For YAML deserialization, toolName would need to come from higher-level context
    OtherTrailblazeTool("unknown", raw)
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
