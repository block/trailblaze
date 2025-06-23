package xyz.block.trailblaze.yaml.models

import com.charleskorn.kaml.YamlInput
import com.charleskorn.kaml.YamlMap
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.serializer
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool.Companion.toKoogToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.getToolNameFromAnnotation
import xyz.block.trailblaze.yaml.TrailblazeYaml
import kotlin.reflect.KClass

/**
 * This structure is used to wrap a [TrailblazeTool] in a YAML-friendly format using the toolName.
 *
 * toolName:
 *   property1: value1
 *   property2: value2
 */
@Serializable
data class TrailblazeToolYamlWrapper(
  val name: String,
  val trailblazeTool: TrailblazeTool,
) {
  companion object {
    fun fromTrailblazeTool(trailblazeTool: TrailblazeTool): TrailblazeToolYamlWrapper = TrailblazeToolYamlWrapper(
      name = trailblazeTool::class.toKoogToolDescriptor().name,
      trailblazeTool = trailblazeTool,
    )
  }

  class TrailblazeToolYamlWrapperSerializer(
    private val allTrailblazeToolClasses: Set<KClass<out TrailblazeTool>>,
  ) : KSerializer<TrailblazeToolYamlWrapper> {

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Tool")
    override fun deserialize(decoder: Decoder): TrailblazeToolYamlWrapper {
      require(decoder is YamlInput) { "Tool can only be deserialized from YAML" }

      val node = decoder.node
      require(node is YamlMap) { "Expected a map for Tool, but got ${node::class.simpleName}" }

      val (keyNode, valueNode) = node.entries.entries.first()
      val toolName = keyNode.content

      val toolKClass: KClass<out TrailblazeTool>? = allTrailblazeToolClasses.firstOrNull { toolKClass ->
        toolKClass.toKoogToolDescriptor().name == toolName
      }
      if (toolKClass == null) {
        throw IllegalArgumentException("TrailblazeYaml could not TrailblazeTool found with name: $toolName.  Did you register it?")
      }

      @OptIn(InternalSerializationApi::class)
      val trailblazeToolSerializer: KSerializer<TrailblazeTool> = toolKClass.serializer() as KSerializer<TrailblazeTool>

      val trailblazeTool = TrailblazeYaml.defaultYamlInstance.decodeFromYamlNode(trailblazeToolSerializer, valueNode)
      return TrailblazeToolYamlWrapper(toolName, trailblazeTool)
    }

    override fun serialize(
      encoder: Encoder,
      value: TrailblazeToolYamlWrapper,
    ) {
      val trailblazeTool = value.trailblazeTool

      @OptIn(InternalSerializationApi::class)
      val trailblazeToolSerializer: KSerializer<TrailblazeTool> =
        trailblazeTool::class.serializer() as KSerializer<TrailblazeTool>

      encoder.encodeSerializableValue(
        MapSerializer(String.serializer(), trailblazeToolSerializer),
        mapOf(trailblazeTool.getToolNameFromAnnotation() to trailblazeTool),
      )
    }
  }
}
