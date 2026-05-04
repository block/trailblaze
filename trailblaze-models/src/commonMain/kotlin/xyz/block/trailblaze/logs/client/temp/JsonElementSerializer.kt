@file:OptIn(ExperimentalSerializationApi::class)

package xyz.block.trailblaze.logs.client.temp

import com.charleskorn.kaml.YamlInput
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import xyz.block.trailblaze.logs.client.TrailblazeJson
import xyz.block.trailblaze.toolcalls.OTHER_TRAILBLAZE_TOOL_NAME_FIELD
import xyz.block.trailblaze.toolcalls.OTHER_TRAILBLAZE_TOOL_RAW_FIELD

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
        put(OTHER_TRAILBLAZE_TOOL_NAME_FIELD, value.toolName)
        put(OTHER_TRAILBLAZE_TOOL_RAW_FIELD, value.raw)
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
      decodeFromJson(jsonElement)
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
   * JSON-side decode that accepts both wire shapes:
   *
   * 1. **Current** `{"toolName": "...", "raw": {...}}` — the canonical encoding emitted by
   *    every path (this serializer + [xyz.block.trailblaze.toolcalls.TrailblazeToolJsonSerializer]).
   *    Strictness mirrors that contextual serializer: missing or blank `toolName` cannot be
   *    routed by `TrailblazeToolRepo.toolCallToTrailblazeTool` and is rejected at the decode
   *    boundary instead of producing a payload that decodes successfully but breaks dispatch.
   * 2. **Legacy** `{"class": "FQCN.SomeTool", ...flatToolFields}` — the shape emitted before
   *    the polymorphic dispatcher started routing TrailblazeTool fields through
   *    [OtherTrailblazeTool]. Production log files in this shape exist on disk; the framework
   *    must continue to read them. `toolName` is best-effort derived from the class
   *    `simpleName` (the @TrailblazeToolClass(name) annotation isn't reachable from
   *    commonMain without reflection); `raw` is everything except the `class` discriminator.
   *    The viewer surfaces `toolName` as a string, so the legacy decode is sufficient for
   *    display even when the derived name doesn't match the live repo's routing key.
   */
  private fun decodeFromJson(jsonElement: JsonObject): OtherTrailblazeTool {
    // Type-check rather than `?.jsonPrimitive`-chaining so a non-primitive value at
    // `toolName` (e.g. a malformed payload writing an array or nested object there) yields
    // a clean SerializationException instead of an `IllegalArgumentException` from
    // kotlinx-serialization's primitive-only accessor.
    val explicitNameElement = jsonElement[OTHER_TRAILBLAZE_TOOL_NAME_FIELD]
    val explicitToolName = (explicitNameElement as? JsonPrimitive)?.contentOrNull
    if (explicitToolName != null) {
      if (explicitToolName.isBlank()) {
        throw SerializationException(
          "OtherTrailblazeTool payload '$OTHER_TRAILBLAZE_TOOL_NAME_FIELD' must not be blank",
        )
      }
      val raw = jsonElement[OTHER_TRAILBLAZE_TOOL_RAW_FIELD]?.jsonObject
        ?: JsonObject(emptyMap())
      return OtherTrailblazeTool(explicitToolName, raw)
    }
    if (explicitNameElement != null) {
      // Field present but not a primitive — neither new shape nor legacy fallback applies.
      throw SerializationException(
        "OtherTrailblazeTool payload '$OTHER_TRAILBLAZE_TOOL_NAME_FIELD' must be a JSON " +
          "string, got ${explicitNameElement::class.simpleName}",
      )
    }
    val legacyClassName = (jsonElement[TrailblazeJson.POLYMORPHIC_CLASS_DISCRIMINATOR] as? JsonPrimitive)?.contentOrNull
    if (legacyClassName != null) {
      // Empty class discriminator can't yield a usable tool name. Fail loud (matches the
      // strictness of the new shape) instead of silently producing an empty-named tool.
      if (legacyClassName.isBlank()) {
        throw SerializationException(
          "OtherTrailblazeTool legacy '${TrailblazeJson.POLYMORPHIC_CLASS_DISCRIMINATOR}' field must not be blank",
        )
      }
      // `substringAfterLast('.')` returns the input unchanged when there's no dot, which is
      // fine — a class name without a package is still a usable identifier.
      val derivedToolName = legacyClassName.substringAfterLast('.')
      val rawFromFlatFields = JsonObject(jsonElement.filterKeys { it != TrailblazeJson.POLYMORPHIC_CLASS_DISCRIMINATOR })
      return OtherTrailblazeTool(derivedToolName, rawFromFlatFields)
    }
    throw SerializationException(
      "OtherTrailblazeTool payload missing required '$OTHER_TRAILBLAZE_TOOL_NAME_FIELD' " +
        "(or legacy '${TrailblazeJson.POLYMORPHIC_CLASS_DISCRIMINATOR}' identifier)",
    )
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
