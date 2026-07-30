package xyz.block.trailblaze.yaml.serializers

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import xyz.block.trailblaze.yaml.TrailConfig
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.TrailheadDefinition

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

  // Decode is intentionally unsupported: the legacy v1 list-shape trail parser (the only caller of
  // this per-item deserializer) was removed. The serializer is kept for ENCODING only — the v1
  // recording ENCODER (`TrailblazeYaml.encodeToString`) still emits the list shape for previews and
  // the recording-generator fallback buffer. Unified trails decode via their own step serializers,
  // never through this one.
  override fun deserialize(decoder: Decoder): TrailYamlItem =
    throw UnsupportedOperationException(
      "Decoding the legacy v1 trail list shape was removed. Trails are parsed as the unified format " +
        "via TrailblazeYaml.decodeTrailDocument / decodeUnifiedTrail.",
    )
}
