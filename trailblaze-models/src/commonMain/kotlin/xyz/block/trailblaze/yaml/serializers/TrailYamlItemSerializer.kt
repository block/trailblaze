package xyz.block.trailblaze.yaml.serializers

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlInput
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
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
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.yaml.TrailConfig
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.TrailheadDefinition
import xyz.block.trailblaze.yaml.TrailblazeToolYamlWrapper

class TrailYamlItemSerializer(
  private val defaultYaml: Yaml,
  private val trailblazeToolYamlWrapperSerializer: TrailblazeToolYamlWrapperSerializer,
) : KSerializer<TrailYamlItem> {

  override val descriptor: SerialDescriptor = buildClassSerialDescriptor("TrailItem")

  override fun serialize(encoder: Encoder, value: TrailYamlItem) {
    when (value) {
      is TrailYamlItem.PromptsTrailItem -> {
        encoder.encodeSerializableValue(
          MapSerializer(
            String.serializer(),
            ListSerializer(PromptStepSerializer()),
          ),
          mapOf(TrailYamlItem.KEYWORD_PROMPTS to value.promptSteps),
        )
      }

      is TrailYamlItem.ToolTrailItem -> {
        encoder.encodeSerializableValue(
          MapSerializer(
            String.serializer(),
            ListSerializer(trailblazeToolYamlWrapperSerializer),
          ),
          mapOf(TrailYamlItem.KEYWORD_TOOLS to value.tools),
        )
      }

      is TrailYamlItem.ConfigTrailItem -> {
        encoder.encodeSerializableValue(
          MapSerializer(
            String.serializer(),
            TrailConfig.serializer(),
          ),
          mapOf(TrailYamlItem.KEYWORD_CONFIG to value.config),
        )
      }

      is TrailYamlItem.TrailheadTrailItem -> {
        encoder.encodeSerializableValue(
          MapSerializer(
            String.serializer(),
            TrailheadDefinition.serializer(),
          ),
          mapOf(TrailYamlItem.KEYWORD_TRAILHEAD to value.trailhead),
        )
      }
    }
  }

  override fun deserialize(decoder: Decoder): TrailYamlItem = decoder.decodeStructure(descriptor) {
    require(decoder is YamlInput) { "This deserializer only works with YAML input" }

    val node = decoder.node
    require(node is YamlMap) { "Expected a map node" }
    require(node.entries.size == 1) { "Expected a single key in map" }

    val (keyNode, valueNode: YamlNode) = node.entries.entries.first()
    val key = keyNode.content

    val yaml = decoder.yaml // needed to call `decodeFromYamlNode`

    when (key) {
      TrailYamlItem.KEYWORD_PROMPTS -> {
        val steps = yaml.decodeFromYamlNode(
          ListSerializer(PromptStepSerializer()),
          valueNode,
        )
        TrailYamlItem.PromptsTrailItem(steps)
      }

      TrailYamlItem.KEYWORD_TOOLS -> {
        @OptIn(ExperimentalSerializationApi::class)
        val contextual = yaml.serializersModule
          .getContextual(TrailblazeToolYamlWrapper::class)
          ?: error("Missing contextual serializer for TrailblazeToolYamlWrapper")

        val wrappedTools = yaml.decodeFromYamlNode(
          ListSerializer(contextual),
          valueNode,
        )

        TrailYamlItem.ToolTrailItem(wrappedTools)
      }

      TrailYamlItem.KEYWORD_CONFIG -> {
        val config = yaml.decodeFromYamlNode(
          TrailConfig.serializer(),
          valueNode,
        )
        TrailYamlItem.ConfigTrailItem(config)
      }

      TrailYamlItem.KEYWORD_TRAILHEAD -> {
        val trailhead = if (valueNode is YamlScalar) {
          // Bare-string shorthand: `trailhead: myapp_freshInstall` → one bootstrap tool with
          // no args. The tool resolves from the session's tool repo at run time, so it's wrapped as
          // an OtherTrailblazeTool (name + empty raw) rather than a typed serializer here.
          val toolId = valueNode.content
          TrailheadDefinition(
            tools = listOf(
              TrailblazeToolYamlWrapper(
                name = toolId,
                trailblazeTool = OtherTrailblazeTool(toolName = toolId, raw = JsonObject(emptyMap())),
              ),
            ),
          )
        } else {
          yaml.decodeFromYamlNode(TrailheadDefinition.serializer(), valueNode)
        }
        TrailYamlItem.TrailheadTrailItem(trailhead)
      }

      else -> error("Unknown key in TrailItem: $key")
    }
  }
}
