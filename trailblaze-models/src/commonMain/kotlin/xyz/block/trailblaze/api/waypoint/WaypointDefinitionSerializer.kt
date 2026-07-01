package xyz.block.trailblaze.api.waypoint

import com.charleskorn.kaml.YamlInput
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlScalar
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * KAML serializer for [WaypointDefinition], mirroring the unified trail format's
 * `UnifiedTrailStepSerializer`: a fixed set of reserved top-level keys (`id`, `description`,
 * `route`) plus a dynamic set of per-device-classifier keys whose values are [WaypointVariant]
 * blocks. The dynamic shape is why this needs a custom serializer rather than stock
 * kotlinx-serialization.
 *
 * Unlike the trail step (whose dynamic values are tool lists and whose other reserved keys are a
 * boolean and an int), **every dynamic value here is the uniform [WaypointVariant] type**, so both
 * directions live in the serializer — no separate hand emitter. Encoding goes through a native
 * KAML map serialization with a small surrogate value serializer that emits a reserved field as a
 * scalar and a classifier block as a nested mapping; that keeps us on KAML's well-tested map path
 * instead of hand-driving a `StructureKind.MAP` descriptor.
 */
class WaypointDefinitionSerializer : KSerializer<WaypointDefinition> {

  override val descriptor: SerialDescriptor =
    MapSerializer(String.serializer(), WaypointVariant.serializer()).descriptor

  override fun deserialize(decoder: Decoder): WaypointDefinition {
    require(decoder is YamlInput) { "WaypointDefinition can only be deserialized from YAML" }
    val node = decoder.node
    require(node is YamlMap) { "Expected a map for WaypointDefinition, but got ${node::class.simpleName}" }

    val yaml = decoder.yaml
    val variantSerializer = WaypointVariant.serializer()

    var id: String? = null
    var description: String? = null
    var route: String? = null
    val byClassifier = linkedMapOf<String, WaypointVariant>()

    for ((keyNode, valueNode) in node.entries) {
      when (val key = keyNode.content) {
        KEY_ID -> id = requireScalar(valueNode, KEY_ID)
        KEY_DESCRIPTION -> description = requireScalar(valueNode, KEY_DESCRIPTION)
        KEY_ROUTE -> route = requireScalar(valueNode, KEY_ROUTE)
        // The legacy v1 shape (top-level required/forbidden/platforms) is no longer accepted.
        // Reject it with an actionable message instead of letting it decode as a (malformed)
        // classifier block. Every waypoint must declare per-classifier blocks.
        // Read the id straight from the node (not the loop var) so the message always names the
        // file even when a legacy key appears before `id:` in document order.
        in REJECTED_V1_KEYS -> throw IllegalArgumentException(
          "WaypointDefinition '${id ?: idFromNode(node) ?: "<unknown id>"}' uses the legacy v1 " +
            "top-level '$key:' key. Move required/forbidden under an explicit classifier block " +
            "(e.g. `android:`, `ios:`, `web:`) and drop `platforms:`. See the waypoint v2 format docs.",
        )
        else -> byClassifier[key] = yaml.decodeFromYamlNode(variantSerializer, valueNode)
      }
    }

    requireNotNull(id) { "WaypointDefinition is missing required `id:` key" }

    return WaypointDefinition(
      id = id,
      description = description,
      route = route,
      byClassifier = byClassifier,
    )
  }

  override fun serialize(encoder: Encoder, value: WaypointDefinition) {
    val out = linkedMapOf<String, Field>()
    out[KEY_ID] = Field.Text(value.id)
    value.description?.let { out[KEY_DESCRIPTION] = Field.Text(it) }
    value.route?.let { out[KEY_ROUTE] = Field.Text(it) }
    value.byClassifier.forEach { (classifier, variant) -> out[classifier] = Field.Variant(variant) }
    encoder.encodeSerializableValue(
      MapSerializer(String.serializer(), FieldSerializer),
      out,
    )
  }

  /** A reserved scalar field or a classifier block, unified so the map has one value type to encode. */
  private sealed interface Field {
    data class Text(val value: String) : Field
    data class Variant(val value: WaypointVariant) : Field
  }

  private object FieldSerializer : KSerializer<Field> {
    override val descriptor: SerialDescriptor = WaypointVariant.serializer().descriptor

    override fun serialize(encoder: Encoder, value: Field) {
      when (value) {
        is Field.Text -> encoder.encodeString(value.value)
        is Field.Variant -> encoder.encodeSerializableValue(WaypointVariant.serializer(), value.value)
      }
    }

    override fun deserialize(decoder: Decoder): Field =
      error("WaypointDefinition decoding goes through the node path, not FieldSerializer.")
  }

  /** Reads the `id:` scalar straight from the parsed node so error messages have it regardless of key order. */
  private fun idFromNode(node: YamlMap): String? =
    node.entries.entries.firstOrNull { (key, _) -> key.content == KEY_ID }
      ?.value?.let { (it as? YamlScalar)?.content }

  private fun requireScalar(node: com.charleskorn.kaml.YamlNode, key: String): String {
    require(node is YamlScalar) {
      "WaypointDefinition `$key:` must be a string scalar, got ${node::class.simpleName}"
    }
    return node.content
  }

  companion object {
    const val KEY_ID = "id"
    const val KEY_DESCRIPTION = "description"
    const val KEY_ROUTE = "route"

    // Legacy (v1) top-level keys. v2 moves selectors/examples into per-classifier blocks; these are
    // hard-rejected (see the deserialize loop) so a stray un-migrated file fails loudly with an
    // actionable message instead of silently decoding as a malformed classifier block.
    private val REJECTED_V1_KEYS = setOf("required", "forbidden", "platforms")
  }
}
