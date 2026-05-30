package xyz.block.trailblaze.yaml.unified

import com.charleskorn.kaml.YamlInput
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlScalar
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import xyz.block.trailblaze.yaml.TrailblazeToolYamlWrapper
import xyz.block.trailblaze.yaml.serializers.TrailblazeToolYamlWrapperSerializer

/**
 * KAML serializer for [UnifiedTrailStep]. Step entries have a fixed `step:`
 * and optional `recordable:` key, plus a dynamic set of per-device-classifier
 * keys whose values are tool lists. The dynamic shape is why this needs a
 * custom serializer rather than stock kotlinx-serialization.
 *
 * Only the deserialize path is implemented — encoding goes through
 * [UnifiedTrailEmitter.emit], which hand-emits the surrounding map structure
 * and delegates tool-list emission to the existing tool-wrapper serializer.
 * That split avoids the awkwardness of describing a map whose value type
 * changes per key (string for `step`, boolean for `recordable`, list for each
 * classifier).
 */
class UnifiedTrailStepSerializer(
  private val toolWrapperSerializer: TrailblazeToolYamlWrapperSerializer,
) : KSerializer<UnifiedTrailStep> {

  override val descriptor: SerialDescriptor = buildClassSerialDescriptor("UnifiedTrailStep")

  override fun deserialize(decoder: Decoder): UnifiedTrailStep {
    require(decoder is YamlInput) { "UnifiedTrailStep can only be deserialized from YAML" }
    val node = decoder.node
    require(node is YamlMap) { "Expected a map for UnifiedTrailStep, but got ${node::class.simpleName}" }

    val yaml = decoder.yaml
    val toolListSerializer = ListSerializer(toolWrapperSerializer)

    var step: String? = null
    var recordable = true
    var maxRetries: Int? = null
    val recordings = linkedMapOf<String, List<TrailblazeToolYamlWrapper>>()

    for ((keyNode, valueNode) in node.entries) {
      when (val key = keyNode.content) {
        KEY_STEP -> {
          require(valueNode is YamlScalar) {
            "UnifiedTrailStep `step:` must be a string scalar, got ${valueNode::class.simpleName}"
          }
          step = valueNode.content
        }
        KEY_RECORDABLE -> {
          require(valueNode is YamlScalar) {
            "UnifiedTrailStep `recordable:` must be a boolean scalar, got ${valueNode::class.simpleName}"
          }
          recordable = valueNode.toBoolean()
        }
        KEY_MAX_RETRIES -> {
          require(valueNode is YamlScalar) {
            "UnifiedTrailStep `maxRetries:` must be an integer scalar, got ${valueNode::class.simpleName}"
          }
          maxRetries = valueNode.toInt()
        }
        in RESERVED_KEYS -> throw IllegalArgumentException(
          "UnifiedTrailStep key `$key` is reserved by the schema but not valid at the step level. " +
            "Valid reserved keys here: `step`, `recordable`, `maxRetries`. Anything else is a " +
            "device-classifier name.",
        )
        else -> {
          require(valueNode is YamlList) {
            "UnifiedTrailStep classifier `$key:` must map to a list of tool calls, " +
              "got ${valueNode::class.simpleName}. Use `$key: []` for an explicit no-op."
          }
          recordings[key] = yaml.decodeFromYamlNode(toolListSerializer, valueNode)
        }
      }
    }

    requireNotNull(step) { "UnifiedTrailStep is missing required `step:` key" }
    require(recordable || recordings.values.all { it.isEmpty() }) {
      "UnifiedTrailStep has both `recordable: false` and non-empty classifier recordings; " +
        "these are mutually exclusive."
    }

    return UnifiedTrailStep(
      step = step,
      recordings = recordings,
      recordable = recordable,
      maxRetries = maxRetries,
    )
  }

  override fun serialize(encoder: Encoder, value: UnifiedTrailStep) {
    error(
      "UnifiedTrailStep encoding does not go through KSerializer.serialize — use " +
        "UnifiedTrailEmitter.emit() instead.",
    )
  }

  companion object {
    const val KEY_STEP = "step"
    const val KEY_RECORDABLE = "recordable"
    const val KEY_MAX_RETRIES = "maxRetries"

    /**
     * Keys that are part of the unified-format vocabulary but not valid at
     * the step level. If one of these shows up as a step-level key, the
     * parser raises a clean error rather than mis-treating it as a device
     * classifier name.
     */
    private val RESERVED_KEYS = setOf(
      "config",
      "trail",
      "tools",
      "recording",
      "on",
      "setup",
      "teardown",
      "trailhead",
      "verify",
    )
  }
}
