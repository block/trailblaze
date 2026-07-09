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
 * KAML serializer for [UnifiedTrailStep]. Step entries have a fixed NL key
 * (exactly one of `step:` / `verify:`) and optional `recordable:` key, plus a
 * dynamic set of per-device-classifier keys whose values are tool lists. The
 * dynamic shape is why this needs a custom serializer rather than stock
 * kotlinx-serialization.
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
  /**
   * When true, this parses a `trailhead:` (the deterministic step 0) rather than a `trail:` step.
   * The only shape difference is the `recording:` value: a trailhead is **one tool per platform**,
   * so each classifier maps to a single tool call (a map, `android: { launchApp: {...} }`), not a
   * list. It's stored internally as a 1-element list so the runtime lowering stays uniform with
   * regular steps.
   */
  private val isTrailhead: Boolean = false,
) : KSerializer<UnifiedTrailStep> {

  override val descriptor: SerialDescriptor = buildClassSerialDescriptor("UnifiedTrailStep")

  override fun deserialize(decoder: Decoder): UnifiedTrailStep {
    require(decoder is YamlInput) { "UnifiedTrailStep can only be deserialized from YAML" }
    val node = decoder.node
    require(node is YamlMap) { "Expected a map for UnifiedTrailStep, but got ${node::class.simpleName}" }

    val yaml = decoder.yaml
    val toolListSerializer = ListSerializer(toolWrapperSerializer)

    var step: String? = null
    var verifyStep: String? = null
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
        KEY_VERIFY -> {
          require(!isTrailhead) {
            "trailhead does not support `verify:` — a trailhead is a deterministic bootstrap, " +
              "not an assertion. Use `step:`."
          }
          require(valueNode is YamlScalar) {
            "UnifiedTrailStep `verify:` must be a string scalar, got ${valueNode::class.simpleName}"
          }
          verifyStep = valueNode.content
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
        KEY_RECORDING -> {
          require(valueNode is YamlMap) {
            "UnifiedTrailStep `recording:` must be a map of device-classifier -> tool list, " +
              "got ${valueNode::class.simpleName}"
          }
          for ((classifierNode, classifierValue) in valueNode.entries) {
            val classifier = classifierNode.content
            if (isTrailhead) {
              // A trailhead is a single tool per platform: each classifier maps to ONE tool call
              // (a map), never a list. Stored as a 1-element list so lowering matches a regular step.
              require(classifierValue is YamlMap) {
                "trailhead `recording:` classifier `$classifier:` must be a single tool call, e.g. " +
                  "`$classifier: { toolName: { ... } }`, got ${classifierValue::class.simpleName}. " +
                  "A trailhead is one tool per platform, not a list."
              }
              // Exactly one tool — the wrapper serializer would otherwise silently decode only the
              // first key of a multi-key map, dropping the rest and violating the one-tool contract.
              require(classifierValue.entries.size == 1) {
                "trailhead `recording:` classifier `$classifier:` must be exactly ONE tool call, got " +
                  "${classifierValue.entries.size}. A trailhead is one tool per platform — compose " +
                  "multiple actions inside that tool's own definition."
              }
              recordings[classifier] = listOf(yaml.decodeFromYamlNode(toolWrapperSerializer, classifierValue))
            } else {
              require(classifierValue is YamlList) {
                "`recording:` classifier `$classifier:` must map to a list of tool calls, " +
                  "got ${classifierValue::class.simpleName}. Use `$classifier: []` for an explicit no-op."
              }
              recordings[classifier] = yaml.decodeFromYamlNode(toolListSerializer, classifierValue)
            }
          }
        }
        else -> throw IllegalArgumentException(
          "Unexpected step-level key `$key`. Valid keys are `step`, `verify`, `recording`, " +
            "`recordable`, `maxRetries`. Device classifiers now nest under `recording:`, not at " +
            "the step level.",
        )
      }
    }

    require(step == null || verifyStep == null) {
      "A unified step has both `step:` and `verify:` — they are mutually exclusive alternatives. " +
        "Author exactly one."
    }
    val nl = step ?: verifyStep
    requireNotNull(nl) {
      "A unified step is missing its required `step:` (or `verify:`) natural language. Every step " +
        "must carry its intent — recording-only steps are not allowed."
    }
    require(recordable || recordings.values.all { it.isEmpty() }) {
      "UnifiedTrailStep has both `recordable: false` and non-empty recordings; " +
        "these are mutually exclusive."
    }

    return UnifiedTrailStep(
      step = nl,
      verify = verifyStep != null,
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
    const val KEY_VERIFY = "verify"
    const val KEY_RECORDABLE = "recordable"
    const val KEY_MAX_RETRIES = "maxRetries"
    const val KEY_RECORDING = "recording"
  }
}
