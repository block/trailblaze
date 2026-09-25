package xyz.block.trailblaze.yaml

import com.charleskorn.kaml.YamlInput
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlScalar
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One value of a trail's `config.metadata:` map: a string, a list, or a map, nested to any depth,
 * so authors can shape metadata however suits them. Every leaf is a string.
 *
 * ```yaml
 * config:
 *   metadata:
 *     jira: PROJ-123                # StringValue
 *     owners: [payments, checkout]  # ListValue
 *     tracker:                      # MapValue
 *       ticket: PROJ-123
 *       suites: [smoke, nightly]
 * ```
 *
 * Metadata is mostly informational. The exceptions are keys a reader gives meaning to (the reserved
 * `source` / `sourceReason` bridge keys, the report's `owner`, integration flags); those read a
 * string with [string] and must not coerce any other shape. Read values with [string] / [list] /
 * [map]; build them with [metadataOf] or the constructors.
 *
 * Leaves stay strings (`caseId: 1017` reads as `"1017"`), exactly as the old plain string map did,
 * so no reader has to guess whether a YAML scalar came back as a number, a boolean, or a string.
 */
@Serializable(with = TrailMetadataValueSerializer::class)
sealed interface TrailMetadataValue {
  data class StringValue(val value: String) : TrailMetadataValue

  data class ListValue(val items: List<TrailMetadataValue>) : TrailMetadataValue {
    companion object {
      /** A list of strings, the common list shape. */
      fun of(vararg items: String): ListValue = ListValue(items.map(::StringValue))
    }
  }

  data class MapValue(val entries: Map<String, TrailMetadataValue>) : TrailMetadataValue
}

/** The string stored under [key], or null when it is absent or not a string. */
fun Map<String, TrailMetadataValue>.string(key: String): String? =
  (get(key) as? TrailMetadataValue.StringValue)?.value

/** The list of strings stored under [key], or null when it is absent, not a list, or holds a non-string. */
fun Map<String, TrailMetadataValue>.list(key: String): List<String>? =
  (get(key) as? TrailMetadataValue.ListValue)?.items?.map { (it as? TrailMetadataValue.StringValue)?.value ?: return null }

/** The map stored under [key], or null when it is absent or not a map. */
fun Map<String, TrailMetadataValue>.map(key: String): Map<String, TrailMetadataValue>? =
  (get(key) as? TrailMetadataValue.MapValue)?.entries

/** Every string leaf, depth-first — for searching or filtering a value of any shape. */
val TrailMetadataValue.leaves: List<String>
  get() = when (this) {
    is TrailMetadataValue.StringValue -> listOf(value)
    is TrailMetadataValue.ListValue -> items.flatMap { it.leaves }
    is TrailMetadataValue.MapValue -> entries.values.flatMap { it.leaves }
  }

/**
 * For display: a string as-is, a list comma-joined, a map as `key: value` pairs. Nested lists and
 * maps are bracketed (`[a, b]`, `{k: v}`) so their boundaries stay readable.
 */
val TrailMetadataValue.displayText: String
  get() = when (this) {
    is TrailMetadataValue.StringValue -> value
    is TrailMetadataValue.ListValue -> items.joinToString(", ") { it.nestedDisplayText }
    is TrailMetadataValue.MapValue -> entries.entries.joinToString(", ") { (k, v) -> "$k: ${v.nestedDisplayText}" }
  }

private val TrailMetadataValue.nestedDisplayText: String
  get() = when (this) {
    is TrailMetadataValue.StringValue -> value
    is TrailMetadataValue.ListValue -> "[$displayText]"
    is TrailMetadataValue.MapValue -> "{$displayText}"
  }

/** As JSON — strings, arrays, and objects — the shape reports publish. */
fun TrailMetadataValue.toJsonElement(): JsonElement = when (this) {
  is TrailMetadataValue.StringValue -> JsonPrimitive(value)
  is TrailMetadataValue.ListValue -> JsonArray(items.map { it.toJsonElement() })
  is TrailMetadataValue.MapValue -> entries.toJsonObject()
}

/** As a JSON object; see [toJsonElement]. */
fun Map<String, TrailMetadataValue>.toJsonObject(): JsonObject = JsonObject(mapValues { (_, v) -> v.toJsonElement() })

/** Metadata from string entries, the common case: `metadataOf("jira" to "PROJ-123")`. */
fun metadataOf(vararg entries: Pair<String, String>): Map<String, TrailMetadataValue> =
  entries.associate { (k, v) -> k to TrailMetadataValue.StringValue(v) }

/** Wraps every value of a string map as a [TrailMetadataValue.StringValue]. */
fun Map<String, String>.toTrailMetadata(): Map<String, TrailMetadataValue> =
  mapValues { (_, v) -> TrailMetadataValue.StringValue(v) }

/**
 * Reads YAML (authored trails) and JSON (the session-log wire format) into [TrailMetadataValue]:
 * scalars become strings, sequences lists, mappings maps. A null anywhere is rejected: YAML names
 * its full path (`config.metadata.owner.team`); JSON names the path inside the value (`team`).
 *
 * The descriptor is [SerialKind.CONTEXTUAL] so kaml hands over the raw node whatever its shape;
 * any other kind makes kaml refuse a scalar, list, or map before this serializer sees it.
 */
@OptIn(ExperimentalSerializationApi::class, InternalSerializationApi::class)
object TrailMetadataValueSerializer : KSerializer<TrailMetadataValue> {
  override val descriptor: SerialDescriptor =
    buildSerialDescriptor("xyz.block.trailblaze.yaml.TrailMetadataValue", SerialKind.CONTEXTUAL)

  // Lazy: both refer back to this object, which is still initializing when its fields are.
  private val listSerializer by lazy { ListSerializer(TrailMetadataValueSerializer) }
  private val mapSerializer by lazy { MapSerializer(String.serializer(), TrailMetadataValueSerializer) }

  override fun serialize(encoder: Encoder, value: TrailMetadataValue) = when (value) {
    is TrailMetadataValue.StringValue -> encoder.encodeString(value.value)
    is TrailMetadataValue.ListValue -> encoder.encodeSerializableValue(listSerializer, value.items)
    is TrailMetadataValue.MapValue -> encoder.encodeSerializableValue(mapSerializer, value.entries)
  }

  override fun deserialize(decoder: Decoder): TrailMetadataValue = when (decoder) {
    is YamlInput -> fromYaml(decoder.node)
    // This serializer sees one map value at a time, so a JSON path can only be relative to it.
    is JsonDecoder -> fromJson(decoder.decodeJsonElement(), path = "")
    else -> TrailMetadataValue.StringValue(decoder.decodeString())
  }

  private fun fromYaml(node: YamlNode): TrailMetadataValue = when (node) {
    is YamlScalar -> TrailMetadataValue.StringValue(node.content)
    is YamlList -> TrailMetadataValue.ListValue(node.items.map(::fromYaml))
    is YamlMap -> TrailMetadataValue.MapValue(node.entries.entries.associate { (k, v) -> k.content to fromYaml(v) })
    else -> throw IllegalArgumentException(
      "$VALUE_ERROR at ${node.path.toHumanReadableString()}, got ${node::class.simpleName}.",
    )
  }

  private fun fromJson(element: JsonElement, path: String): TrailMetadataValue = when (element) {
    is JsonNull -> throw IllegalArgumentException(
      "$VALUE_ERROR, got null" + (if (path.isEmpty()) "." else " at `$path` inside the value."),
    )
    is JsonPrimitive -> TrailMetadataValue.StringValue(element.content)
    is JsonArray -> TrailMetadataValue.ListValue(element.mapIndexed { i, item -> fromJson(item, "$path[$i]") })
    is JsonObject -> TrailMetadataValue.MapValue(
      element.mapValues { (k, v) -> fromJson(v, if (path.isEmpty()) k else "$path.$k") },
    )
  }

  private const val VALUE_ERROR = "A config.metadata value must be a string, a list, or a map, not null"
}
