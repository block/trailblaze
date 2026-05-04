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
import xyz.block.trailblaze.toolcalls.RawArgumentTrailblazeTool
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

    if (trailblazeTool is OtherTrailblazeTool) {
      // OtherTrailblazeTool wraps raw tool call data (e.g., from MCP session recording).
      // Always use its own serializer — even if a known serializer exists for the tool name —
      // because the known serializer expects the concrete tool class and would throw a
      // ClassCastException (e.g., OtherTrailblazeTool cannot be cast to TapOnElementByNodeIdTrailblazeTool).
      val trailblazeToolSerializer = OtherTrailblazeTool.serializer() as KSerializer<TrailblazeTool>
      encoder.encodeSerializableValue(
        MapSerializer(String.serializer(), trailblazeToolSerializer),
        mapOf(value.name to trailblazeTool),
      )
    } else if (trailblazeTool is RawArgumentTrailblazeTool) {
      // Session-scoped dynamic tools (for example subprocess-backed scripted tools) don't have a
      // stable class serializer keyed by their runtime tool name. Preserve their invocation
      // payload verbatim so YAML execution can decode back through OtherTrailblazeTool and let
      // the session tool repo re-resolve the concrete dynamic tool at dispatch time.
      val fallback = OtherTrailblazeTool(toolName = value.name, raw = trailblazeTool.rawToolArguments)
      val trailblazeToolSerializer = OtherTrailblazeTool.serializer() as KSerializer<TrailblazeTool>
      encoder.encodeSerializableValue(
        MapSerializer(String.serializer(), trailblazeToolSerializer),
        mapOf(value.name to fallback),
      )
    } else {
      val knownSerializer = toolSerializersByName[value.name]
      if (knownSerializer != null) {
        val trailblazeToolSerializer = knownSerializer as KSerializer<TrailblazeTool>
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
}
