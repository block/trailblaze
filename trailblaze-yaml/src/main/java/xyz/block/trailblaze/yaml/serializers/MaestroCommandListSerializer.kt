package xyz.block.trailblaze.yaml.serializers

import com.charleskorn.kaml.YamlNode
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import xyz.block.trailblaze.maestro.MaestroYamlSerializer
import xyz.block.trailblaze.yaml.TrailblazeYaml
import xyz.block.trailblaze.yaml.models.MaestroCommandList

class MaestroCommandListSerializer : KSerializer<MaestroCommandList> {

  override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Tool")
  override fun serialize(
    encoder: Encoder,
    value: MaestroCommandList,
  ) {
    // Use the handwritten serializer of Maestro commands as YAML to create a flat structure
    val yaml = MaestroYamlSerializer.toYaml(value.maestroCommands).substringAfter("---\n")

    // Parse the "normal" Maestro Yaml using kaml's standard parser
    val kamlYamlListOfMaestroCommands: List<YamlNode> = TrailblazeYaml.defaultYamlInstance.decodeFromString(
      ListSerializer(YamlNode.serializer()),
      yaml,
    )
    encoder.encodeSerializableValue(
      ListSerializer(YamlNode.serializer()),
      kamlYamlListOfMaestroCommands,
    )
  }

  override fun deserialize(decoder: Decoder): MaestroCommandList = decoder.decodeStructure(descriptor) {
    error("Deserialization not implemented for MaestroCommandListSerializer")
  }
}
