package xyz.block.trailblaze.yaml.serializers

import com.charleskorn.kaml.YamlInput
import com.charleskorn.kaml.YamlMap
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.yaml.DirectionStep
import xyz.block.trailblaze.yaml.PromptStep
import xyz.block.trailblaze.yaml.ToolRecording
import xyz.block.trailblaze.yaml.VerificationStep

class PromptStepSerializer : KSerializer<PromptStep> {

  override val descriptor: SerialDescriptor = buildClassSerialDescriptor("PromptStep") {
    element<String>("step", isOptional = true)
    element<String>("verify", isOptional = true)
    element<Boolean>("recordable", isOptional = true)
    element<ToolRecording>("recording", isOptional = true)
  }

  override fun serialize(encoder: Encoder, value: PromptStep) {
    encoder.encodeStructure(descriptor) {
      when (value) {
        is DirectionStep -> {
          encodeStringElement(descriptor, 0, value.step)
          encodeOptionalBooleanElement(this, descriptor, 2, value.recordable)
          encodeOptionalRecording(this, descriptor, 3, value.recording)
        }
        is VerificationStep -> {
          encodeStringElement(descriptor, 1, value.verify)
          encodeOptionalBooleanElement(this, descriptor, 2, value.recordable)
          encodeOptionalRecording(this, descriptor, 3, value.recording)
        }
      }
    }
  }

  private fun encodeOptionalBooleanElement(
    encoder: CompositeEncoder,
    descriptor: SerialDescriptor,
    index: Int,
    value: Boolean,
  ) {
    if (!value) {
      encoder.encodeBooleanElement(descriptor, index, value)
    }
  }

  private fun encodeOptionalRecording(
    encoder: CompositeEncoder,
    descriptor: SerialDescriptor,
    index: Int,
    value: ToolRecording?,
  ) {
    value?.let { recording ->
      encoder.encodeSerializableElement(descriptor, index, ToolRecording.serializer(), recording)
    }
  }

  override fun deserialize(decoder: Decoder): PromptStep = decoder.decodeStructure(descriptor) {
    require(decoder is YamlInput) { "This deserializer only works with YAML input" }

    val node = decoder.node
    require(node is YamlMap) { "Expected a map node" }
    val nodeKeys = node.entries.keys.map { it.content }

    val yaml = decoder.yaml // needed to call `decodeFromYamlNode`
    if (nodeKeys.contains(STEP_KEY)) {
      yaml.decodeFromYamlNode(
        DirectionStep.serializer(),
        node,
      )
    } else if (nodeKeys.contains(VERIFY_KEY)) {
      yaml.decodeFromYamlNode(
        VerificationStep.serializer(),
        node,
      )
    } else {
      throw TrailblazeException("Unable to parse PromptStep without either step or verify key")
    }
  }

  private companion object {
    private const val STEP_KEY = "step"
    private const val VERIFY_KEY = "verify"
  }
}
