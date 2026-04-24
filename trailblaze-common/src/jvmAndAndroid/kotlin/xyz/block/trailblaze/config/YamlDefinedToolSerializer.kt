package xyz.block.trailblaze.config

import com.charleskorn.kaml.YamlInput
import com.charleskorn.kaml.YamlMap
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.logs.client.temp.JsonElementSerializer
import xyz.block.trailblaze.logs.client.temp.YamlJsonBridge

/**
 * Serializer for one specific YAML-defined Trailblaze tool, pre-bound to its [ToolYamlConfig].
 *
 * Every YAML-defined tool name registers its own [YamlDefinedToolSerializer] instance in
 * `TrailblazeYaml.Default`'s `toolSerializersByName` map (and the equivalent JSON polymorphic
 * table). When a trail YAML contains `- eraseText: { charactersToErase: 3 }`, the outer
 * `TrailblazeToolYamlWrapperSerializer` resolves `"eraseText"` to the matching serializer here,
 * which:
 *
 * 1. Decodes the argument payload (the `{ charactersToErase: 3 }` map) into
 *    `Map<String, JsonElement>`.
 * 2. Constructs `YamlDefinedTrailblazeTool(config, params)` with the captured [config] and the
 *    decoded params.
 *
 * On serialize it writes the tool's `params` back out; the [config] is not serialized (it's
 * always resolved from the classpath-discovered YAML file at load time).
 */
class YamlDefinedToolSerializer(
  private val config: ToolYamlConfig,
) : KSerializer<YamlDefinedTrailblazeTool> {

  private val paramsMapSerializer =
    MapSerializer(String.serializer(), JsonElementSerializer)

  // Share the map descriptor so kaml/kotlinx route to the right shape (key/value pairs),
  // not a synthetic class descriptor.
  override val descriptor: SerialDescriptor = paramsMapSerializer.descriptor

  override fun serialize(encoder: Encoder, value: YamlDefinedTrailblazeTool) {
    if (encoder is JsonEncoder) {
      encoder.encodeJsonElement(JsonObject(value.params))
    } else {
      // YAML / generic-structured path: convert the JsonElement params into the `Any?` shape
      // that JsonElementSerializer handles, so kaml can walk through strings/numbers/maps/lists
      // without needing JsonEncoder-only `encodeJsonElement`. This is the path hit by
      // TrailblazeYaml.encodeToString when re-saving a trail file that contains a YAML-defined
      // tool.
      val serializable: Map<String, Any?> = value.params
        .mapValues { (_, v) -> YamlJsonBridge.jsonElementToSerializable(v) }
      encoder.encodeSerializableValue(paramsMapSerializer, serializable)
    }
  }

  override fun deserialize(decoder: Decoder): YamlDefinedTrailblazeTool {
    val params: Map<String, JsonElement> = when (decoder) {
      is YamlInput -> {
        when (val node = decoder.node) {
          is YamlMap -> node.entries.entries.associate { (k, v) ->
            k.content to YamlJsonBridge.yamlNodeToJsonElement(v)
          }
          // Empty YAML value (`- eraseText:`) lands as YamlNull — legitimately zero args.
          is com.charleskorn.kaml.YamlNull -> emptyMap()
          else -> error(
            "YAML tool '${config.id}' expects a map of params (or empty), got " +
              "${node::class.simpleName}. Did you write `- ${config.id}: <scalar>` instead of " +
              "`- ${config.id}:\\n    <key>: <value>`?",
          )
        }
      }
      is JsonDecoder -> {
        when (val element = decoder.decodeJsonElement()) {
          is JsonObject -> element.toMap()
          is kotlinx.serialization.json.JsonNull -> emptyMap()
          else -> error(
            "YAML tool '${config.id}' expects a JSON object of params (or null), got " +
              "${element::class.simpleName}.",
          )
        }
      }
      else -> error("YamlDefinedToolSerializer: unsupported decoder ${decoder::class.simpleName}")
    }
    return YamlDefinedTrailblazeTool(config = config, params = params)
  }
}
