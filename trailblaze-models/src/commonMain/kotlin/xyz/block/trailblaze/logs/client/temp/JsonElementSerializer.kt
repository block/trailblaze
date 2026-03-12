@file:OptIn(ExperimentalSerializationApi::class)

package xyz.block.trailblaze.logs.client.temp

import com.charleskorn.kaml.YamlInput
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
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
    if (decoder is YamlInput) {
      return YamlJsonBridge.yamlNodeToSerializable(decoder.node)
    }
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
      val jsonObj = buildJsonObject {
        put("toolName", value.toolName)
        put("raw", value.raw)
      }
      encoder.encodeJsonElement(jsonObj)
    } else {
      val flattenedMap = mutableMapOf<String, Any?>()
      value.raw.forEach { (key, jsonElement) ->
        flattenedMap[key] = YamlJsonBridge.jsonElementToSerializable(jsonElement)
      }
      encoder.encodeSerializableValue(
        MapSerializer(String.serializer(), JsonElementSerializer),
        flattenedMap,
      )
    }
  }

  override fun deserialize(decoder: Decoder): OtherTrailblazeTool = when {
    decoder is JsonDecoder -> {
      val jsonElement = decoder.decodeJsonElement().jsonObject
      val toolName = jsonElement["toolName"]?.jsonPrimitive?.content ?: ""
      val raw = jsonElement["raw"]?.jsonObject ?: JsonObject(emptyMap())
      OtherTrailblazeTool(toolName, raw)
    }
    decoder is YamlInput -> {
      val raw = YamlJsonBridge.yamlNodeToJsonElement(decoder.node) as? JsonObject
        ?: JsonObject(emptyMap())
      // toolName is NOT available at this level - it comes from the YAML key (e.g., "myApp_launchSignedIn:")
      // which is extracted by TrailblazeToolYamlWrapperSerializer and stored in TrailblazeToolYamlWrapper.name.
      OtherTrailblazeTool(YAML_CONTEXT_TOOL_NAME_PLACEHOLDER, raw)
    }
    else -> {
      val map = decoder.decodeSerializableValue(
        MapSerializer(String.serializer(), JsonElementSerializer),
      )
      val raw = JsonObject(
        map.mapValues { (_, value) ->
          YamlJsonBridge.serializableToJsonElement(value)
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
}
