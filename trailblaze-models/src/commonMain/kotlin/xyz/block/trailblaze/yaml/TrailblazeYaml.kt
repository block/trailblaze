package xyz.block.trailblaze.yaml

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.yaml.unified.UnifiedTrail

/** Runtime facade: document parsing is shared; tool discovery belongs to the runtime. */
class TrailblazeYaml internal constructor(
  toolSerializersByName: Map<String, KSerializer<out TrailblazeTool>>,
  strict: Boolean,
) {
  private val codec = TrailFormatYaml(toolSerializersByName, strict)

  constructor() : this(emptyMap(), false)
  constructor(toolSerializersByName: Map<String, KSerializer<out TrailblazeTool>>) :
    this(toolSerializersByName, false)

  // Keep facade methods (including JVM default-argument bridges) on the original class.
  val unifiedTrailStepSerializer
    get() = codec.unifiedTrailStepSerializer

  val unifiedTrailheadSerializer
    get() = codec.unifiedTrailheadSerializer

  fun getInstance() = codec.getInstance()

  fun encodeToolToYaml(toolName: String, trailblazeTool: TrailblazeTool) =
    codec.encodeToolToYaml(toolName, trailblazeTool)

  fun encodeTools(tools: List<TrailblazeToolYamlWrapper>) = codec.encodeTools(tools)

  fun decodeTrail(
    yaml: String,
    deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList(),
    selectedDeviceConfiguration: String? = null,
  ) = codec.decodeTrail(yaml, deviceClassifiers, selectedDeviceConfiguration)

  fun decodeTools(yaml: String) = codec.decodeTools(yaml)

  fun decodeTrailOrToolEnvelope(
    yaml: String,
    deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList(),
    selectedDeviceConfiguration: String? = null,
  ) = codec.decodeTrailOrToolEnvelope(yaml, deviceClassifiers, selectedDeviceConfiguration)

  fun extractTrailConfig(yaml: String) = codec.extractTrailConfig(yaml)

  fun extractTrailConfig(
    yaml: String,
    deviceClassifiers: List<TrailblazeDeviceClassifier>,
  ) = codec.extractTrailConfig(yaml, deviceClassifiers)

  fun extractTrailConfig(
    yaml: String,
    deviceClassifiers: List<TrailblazeDeviceClassifier>,
    selectedDeviceConfiguration: String?,
  ) = codec.extractTrailConfig(yaml, deviceClassifiers, selectedDeviceConfiguration)

  fun extractTrailConfig(trailItems: List<TrailYamlItem>) = codec.extractTrailConfig(trailItems)

  fun firstSkipReason(trailItems: List<TrailYamlItem>) = codec.firstSkipReason(trailItems)

  fun hasActionableSteps(trailItems: List<TrailYamlItem>) = codec.hasActionableSteps(trailItems)

  fun hasRecordedSteps(trailItems: List<TrailYamlItem>) = codec.hasRecordedSteps(trailItems)

  fun hasRecordedSteps(yaml: String) = codec.hasRecordedSteps(yaml)

  fun decodeUnifiedTrail(yaml: String) = codec.decodeUnifiedTrail(yaml)

  fun decodeUnifiedTrailConfig(yaml: String) = codec.decodeUnifiedTrailConfig(yaml)

  fun requiresHostRefusal(
    yaml: String,
    deviceClassifiers: List<TrailblazeDeviceClassifier>,
    trailName: String?,
  ) = codec.requiresHostRefusal(yaml, deviceClassifiers, trailName)

  fun decodeTrailDocument(yaml: String) = codec.decodeTrailDocument(yaml)

  fun encodeUnifiedTrailToString(
    trail: UnifiedTrail,
    leadingComments: List<String> = emptyList(),
  ) = codec.encodeUnifiedTrailToString(trail, leadingComments)

  companion object {
    val yamlConfiguration = TrailFormatYaml.yamlConfiguration
    val defaultYamlInstance = TrailFormatYaml.defaultYamlInstance
    val Default: TrailblazeYaml by lazy { buildTrailblazeYamlDefault() }
    fun toolToYaml(toolName: String, trailblazeTool: TrailblazeTool): String =
      Default.encodeToolToYaml(toolName, trailblazeTool)
    fun jsonToYaml(jsonElement: JsonElement): String = TrailFormatYaml.jsonToYaml(jsonElement)
  }
}
