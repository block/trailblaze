package xyz.block.trailblaze.config

import com.charleskorn.kaml.YamlInput
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlScalar
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.encodeStructure
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import xyz.block.trailblaze.logs.client.temp.YamlJsonBridge

/**
 * Describes a single parameter of a YAML-defined (`tools:` mode) Trailblaze tool.
 *
 * Mirrors the Kotlin data-class constructor parameter shape so that LLM descriptors generated
 * from YAML match those generated reflectively from Kotlin-backed tools.
 *
 * Example:
 * ```yaml
 * parameters:
 *   - name: text
 *     type: string
 *     required: true
 *     description: "The text to enter"
 *   - name: index
 *     type: integer
 *     required: false
 *     default: 0
 * ```
 *
 * The [default] field is presence-aware. See [DefaultBehavior].
 */
@Serializable(with = TrailblazeToolParameterConfigSerializer::class)
data class TrailblazeToolParameterConfig(
  val name: String,
  val type: String,
  val required: Boolean = true,
  val description: String? = null,
  val default: DefaultBehavior = DefaultBehavior.DropIfOmitted,
)

/**
 * How to populate a parameter's value when the caller omits it.
 *
 * - [DropIfOmitted] — the parameter key is **dropped entirely** from the emitted JSON. Use this
 *   when the downstream tool distinguishes "absent" from "null." In YAML this is written by simply
 *   not declaring a `default:` field.
 * - [Use] — the declared value is substituted, including an explicit JSON `null` if the author
 *   writes `default: null`. In YAML this is written by declaring a `default:` entry (with any
 *   value, including null/empty).
 */
sealed interface DefaultBehavior {
  data object DropIfOmitted : DefaultBehavior

  data class Use(val value: JsonElement) : DefaultBehavior
}

/**
 * Custom serializer for [TrailblazeToolParameterConfig] that is presence-aware on the `default`
 * field: kotlinx.serialization by itself can't distinguish "key absent" from "key present with
 * null value" (both become Kotlin null), but kaml's YamlInput exposes the raw map so we can
 * inspect key presence and map to [DefaultBehavior] accordingly.
 */
object TrailblazeToolParameterConfigSerializer : KSerializer<TrailblazeToolParameterConfig> {

  override val descriptor: SerialDescriptor =
    buildClassSerialDescriptor("TrailblazeToolParameterConfig") {
      element<String>("name")
      element<String>("type")
      element<Boolean>("required", isOptional = true)
      element<String>("description", isOptional = true)
      // `default` is intentionally NOT declared here — it is extracted manually from the raw
      // YamlMap to distinguish absence from null.
    }

  override fun serialize(encoder: Encoder, value: TrailblazeToolParameterConfig) {
    encoder.encodeStructure(descriptor) {
      encodeStringElement(descriptor, 0, value.name)
      encodeStringElement(descriptor, 1, value.type)
      if (!value.required) encodeBooleanElement(descriptor, 2, value.required)
      value.description?.let { encodeStringElement(descriptor, 3, it) }
    }
  }

  override fun deserialize(decoder: Decoder): TrailblazeToolParameterConfig {
    require(decoder is YamlInput) {
      "TrailblazeToolParameterConfig can only be deserialized from YAML"
    }
    val node = decoder.node
    require(node is YamlMap) {
      "Expected a map for TrailblazeToolParameterConfig, got ${node::class.simpleName}"
    }
    val entries = node.entries.entries.associate { (k, v) -> k.content to v }

    val name = requireString(entries, "name")
    val type = requireString(entries, "type")
    val required = entries["required"]?.let {
      decoder.yaml.decodeFromYamlNode(Boolean.serializer(), it)
    } ?: true
    val description = entries["description"]?.let {
      decoder.yaml.decodeFromYamlNode(String.serializer(), it)
    }
    val default = if (entries.containsKey("default")) {
      val defaultNode = entries.getValue("default")
      // For `type: string`, preserve the scalar's raw content — YamlJsonBridge otherwise coerces
      // numeric-looking strings like "00123" into numbers, which silently mangles string-typed
      // defaults. Non-scalar string defaults (maps/lists) are an author error; fall through to
      // the generic decode path so the existing error surfacing catches them.
      val jsonElement = if (type == "string" && defaultNode is YamlScalar) {
        JsonPrimitive(defaultNode.content)
      } else {
        YamlJsonBridge.yamlNodeToJsonElement(defaultNode)
      }
      DefaultBehavior.Use(jsonElement)
    } else {
      DefaultBehavior.DropIfOmitted
    }

    return TrailblazeToolParameterConfig(
      name = name,
      type = type,
      required = required,
      description = description,
      default = default,
    )
  }

  private fun requireString(entries: Map<String, com.charleskorn.kaml.YamlNode>, key: String): String {
    val node = entries[key] ?: error("Tool parameter is missing required '$key' field.")
    return (node as? YamlScalar)?.content
      ?: error("Tool parameter '$key' must be a scalar string, got ${node::class.simpleName}.")
  }
}
