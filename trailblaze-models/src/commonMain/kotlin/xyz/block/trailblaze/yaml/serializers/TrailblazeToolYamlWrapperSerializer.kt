package xyz.block.trailblaze.yaml.serializers

import com.charleskorn.kaml.YamlInput
import com.charleskorn.kaml.YamlMap
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.yaml.TrailblazeToolYamlWrapper

/**
 * Serializer for [TrailblazeToolYamlWrapper] that maps tool names to their serializers.
 *
 * @param toolSerializersByName A pre-built map of tool name to serializer. This allows
 *   the serializer to be platform-agnostic — the map can be built using reflection on JVM
 *   or statically on other platforms.
 */
class TrailblazeToolYamlWrapperSerializer(
  private val toolSerializersByName: Map<String, KSerializer<out TrailblazeTool>>,
  private val yamlInstanceProvider: () -> com.charleskorn.kaml.Yaml,
) : KSerializer<TrailblazeToolYamlWrapper> {

  override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Tool")

  override fun deserialize(decoder: Decoder): TrailblazeToolYamlWrapper {
    require(decoder is YamlInput) { "Tool can only be deserialized from YAML" }

    val node = decoder.node
    require(node is YamlMap) { "Expected a map for Tool, but got ${node::class.simpleName}" }

    val (keyNode, valueNode) = node.entries.entries.first()
    val toolName = keyNode.content

    @Suppress("UNCHECKED_CAST")
    val trailblazeToolSerializer: KSerializer<TrailblazeTool> =
      (toolSerializersByName[toolName] ?: OtherTrailblazeTool.serializer()) as KSerializer<TrailblazeTool>

    val trailblazeTool = yamlInstanceProvider().decodeFromYamlNode(trailblazeToolSerializer, valueNode)

    // When the tool is deserialized as OtherTrailblazeTool, the toolName inside it is a placeholder
    // because the deserializer doesn't have access to the YAML key. Propagate the actual name here.
    val resolvedTool = if (trailblazeTool is OtherTrailblazeTool) {
      trailblazeTool.copy(toolName = toolName)
    } else {
      trailblazeTool
    }
    return TrailblazeToolYamlWrapper(toolName, resolvedTool)
  }

  @Suppress("UNCHECKED_CAST")
  override fun serialize(
    encoder: Encoder,
    value: TrailblazeToolYamlWrapper,
  ) {
    val trailblazeTool = value.trailblazeTool

    val knownSerializer = toolSerializersByName[value.name]
    if (knownSerializer != null) {
      val trailblazeToolSerializer = knownSerializer as KSerializer<TrailblazeTool>
      encoder.encodeSerializableValue(
        MapSerializer(String.serializer(), trailblazeToolSerializer),
        mapOf(value.name to trailblazeTool),
      )
    } else if (trailblazeTool is OtherTrailblazeTool) {
      val trailblazeToolSerializer = OtherTrailblazeTool.serializer() as KSerializer<TrailblazeTool>
      encoder.encodeSerializableValue(
        MapSerializer(String.serializer(), trailblazeToolSerializer),
        mapOf(value.name to trailblazeTool),
      )
    } else {
      // Concrete TrailblazeTool subclass without a registered YAML serializer —
      // serialize to JSON first to capture all fields, then wrap as OtherTrailblazeTool.
      @OptIn(InternalSerializationApi::class)
      val raw = try {
        val concreteSerializer = trailblazeTool::class.serializer() as KSerializer<TrailblazeTool>
        val lenientJson = Json { encodeDefaults = false; ignoreUnknownKeys = true }
        val jsonStr = lenientJson.encodeToString(concreteSerializer, trailblazeTool)
        lenientJson.decodeFromString<JsonObject>(jsonStr)
      } catch (_: Exception) {
        JsonObject(emptyMap())
      }
      val fallback = OtherTrailblazeTool(toolName = value.name, raw = raw)
      val trailblazeToolSerializer = OtherTrailblazeTool.serializer() as KSerializer<TrailblazeTool>
      encoder.encodeSerializableValue(
        MapSerializer(String.serializer(), trailblazeToolSerializer),
        mapOf(value.name to fallback),
      )
    }
  }
}
