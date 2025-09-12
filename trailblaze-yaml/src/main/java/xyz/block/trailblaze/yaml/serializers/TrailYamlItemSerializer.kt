package xyz.block.trailblaze.yaml.serializers

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlInput
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
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
import xyz.block.trailblaze.maestro.MaestroYamlParser
import xyz.block.trailblaze.maestro.MaestroYamlSerializer
import xyz.block.trailblaze.yaml.MaestroCommandList
import xyz.block.trailblaze.yaml.TrailConfig
import xyz.block.trailblaze.yaml.TrailYamlItem
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

      is TrailYamlItem.MaestroTrailItem -> {
        // Use the handwritten serializer of Maestro commands as YAML to create a flat structure
        val yaml = MaestroYamlSerializer.toYaml(value.maestro.maestroCommands).substringAfter("---\n")

        // Parse the "normal" Maestro Yaml using kaml's standard parser
        val kamlYamlListOfMaestroCommands: List<YamlNode> = defaultYaml.decodeFromString(
          ListSerializer(YamlNode.serializer()),
          yaml,
        )
        encoder.encodeSerializableValue(
          MapSerializer(
            String.serializer(),
            ListSerializer(YamlNode.serializer()),
          ),
          mapOf(TrailYamlItem.KEYWORD_MAESTRO to kamlYamlListOfMaestroCommands),
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

      TrailYamlItem.KEYWORD_MAESTRO -> {
        // Dump the "Maestro" node to unindented YAML so the Maestro Parser can parse it
        val maestroYaml = defaultYaml.encodeToString(
          YamlNode.serializer(),
          valueNode,
        )
        // Parse the generated YAML into Maestro commands
        val maestroCommands = MaestroYamlParser.parseYaml(maestroYaml)
        TrailYamlItem.MaestroTrailItem(MaestroCommandList(maestroCommands))
      }

      TrailYamlItem.KEYWORD_CONFIG -> {
        val config = yaml.decodeFromYamlNode(
          TrailConfig.serializer(),
          valueNode,
        )
        TrailYamlItem.ConfigTrailItem(config)
      }

      else -> error("Unknown key in TrailItem: $key")
    }
  }
}
