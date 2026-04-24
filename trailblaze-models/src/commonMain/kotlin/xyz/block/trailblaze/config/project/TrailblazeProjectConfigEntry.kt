package xyz.block.trailblaze.config.project

import com.charleskorn.kaml.YamlInput
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlScalar
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import xyz.block.trailblaze.config.AppTargetYamlConfig
import xyz.block.trailblaze.config.ToolSetYamlConfig
import xyz.block.trailblaze.config.ToolYamlConfig
import xyz.block.trailblaze.llm.config.BuiltInProviderConfig

/**
 * Each of the list-shaped sections in [TrailblazeProjectConfig] (`targets`, `toolsets`,
 * `tools`, `providers`) accepts entries in one of two forms:
 *
 * - **Inline**: the full YAML body for the entry is written directly inside
 *   `trailblaze.yaml`. Carries the same schema the standalone per-file YAMLs use today.
 * - **Ref**: a pointer to an external YAML file via `ref: path/to/file.yaml`. The loader
 *   resolves the path and inlines the referenced file at load time.
 *
 * Ref paths follow the resolution rules described in the configuration redesign plan
 * and implemented by [xyz.block.trailblaze.config.project.TrailblazeProjectConfigLoader]:
 * paths are anchor-relative (to the directory containing `trailblaze.yaml`) by default,
 * and leading `/` is likewise stripped and treated as anchor-relative — a Unix-style
 * absolute path such as `/Users/foo.yaml` is **not** an OS-absolute escape hatch. Only
 * paths the JVM considers absolute in other forms (e.g. Windows `C:\...`) are passed
 * through unchanged.
 */
@Serializable(with = TargetEntrySerializer::class)
sealed interface TargetEntry {
  @Serializable
  data class Inline(val config: AppTargetYamlConfig) : TargetEntry

  @Serializable
  data class Ref(val path: String) : TargetEntry
}

@Serializable(with = ToolsetEntrySerializer::class)
sealed interface ToolsetEntry {
  @Serializable
  data class Inline(val config: ToolSetYamlConfig) : ToolsetEntry

  @Serializable
  data class Ref(val path: String) : ToolsetEntry
}

@Serializable(with = ToolEntrySerializer::class)
sealed interface ToolEntry {
  @Serializable
  data class Inline(val config: ToolYamlConfig) : ToolEntry

  @Serializable
  data class Ref(val path: String) : ToolEntry
}

@Serializable(with = ProviderEntrySerializer::class)
sealed interface ProviderEntry {
  @Serializable
  data class Inline(val config: BuiltInProviderConfig) : ProviderEntry

  @Serializable
  data class Ref(val path: String) : ProviderEntry
}

/**
 * Shared logic for the four inline-or-ref serializers. Inspects the raw YamlMap for a
 * `ref:` key; if present, returns a Ref, otherwise delegates to the inline type's own
 * serializer.
 *
 * Serialization (write path) encodes inline entries through the inline serializer and
 * refs as a single-key `{ ref: "path" }` map.
 */
internal inline fun <T, R> decodeProjectConfigEntry(
  decoder: Decoder,
  typeName: String,
  inlineSerializer: KSerializer<T>,
  onRef: (String) -> R,
  onInline: (T) -> R,
): R {
  require(decoder is YamlInput) { "$typeName can only be deserialized from YAML" }
  val node = decoder.node
  require(node is YamlMap) {
    "Expected a map for $typeName, got ${node::class.simpleName}"
  }
  val refEntry = node.entries.entries.firstOrNull { it.key.content == "ref" }
  return if (refEntry != null) {
    val siblingKeys = node.entries.keys.map { it.content }.filter { it != "ref" }
    require(siblingKeys.isEmpty()) {
      "$typeName entry mixes 'ref:' with inline fields (${siblingKeys.sorted()}). " +
        "A ref entry must have only a 'ref:' key."
    }
    val value = refEntry.value
    require(value is YamlScalar) {
      "$typeName 'ref:' must be a scalar string, got ${value::class.simpleName}"
    }
    val refPath = value.content
    require(refPath.isNotBlank()) { "$typeName 'ref:' must not be blank" }
    onRef(refPath)
  } else {
    onInline(decoder.yaml.decodeFromYamlNode(inlineSerializer, node))
  }
}

/** Single-key map shape used to encode a Ref back to YAML. */
@Serializable
private data class RefOnly(val ref: String)

object TargetEntrySerializer : KSerializer<TargetEntry> {
  override val descriptor: SerialDescriptor = buildClassSerialDescriptor("TargetEntry")

  override fun serialize(encoder: Encoder, value: TargetEntry) {
    when (value) {
      is TargetEntry.Inline ->
        encoder.encodeSerializableValue(AppTargetYamlConfig.serializer(), value.config)
      is TargetEntry.Ref ->
        encoder.encodeSerializableValue(RefOnly.serializer(), RefOnly(value.path))
    }
  }

  override fun deserialize(decoder: Decoder): TargetEntry =
    decodeProjectConfigEntry(
      decoder = decoder,
      typeName = "TargetEntry",
      inlineSerializer = AppTargetYamlConfig.serializer(),
      onRef = { TargetEntry.Ref(it) },
      onInline = { TargetEntry.Inline(it) },
    )
}

object ToolsetEntrySerializer : KSerializer<ToolsetEntry> {
  override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ToolsetEntry")

  override fun serialize(encoder: Encoder, value: ToolsetEntry) {
    when (value) {
      is ToolsetEntry.Inline ->
        encoder.encodeSerializableValue(ToolSetYamlConfig.serializer(), value.config)
      is ToolsetEntry.Ref ->
        encoder.encodeSerializableValue(RefOnly.serializer(), RefOnly(value.path))
    }
  }

  override fun deserialize(decoder: Decoder): ToolsetEntry =
    decodeProjectConfigEntry(
      decoder = decoder,
      typeName = "ToolsetEntry",
      inlineSerializer = ToolSetYamlConfig.serializer(),
      onRef = { ToolsetEntry.Ref(it) },
      onInline = { ToolsetEntry.Inline(it) },
    )
}

object ToolEntrySerializer : KSerializer<ToolEntry> {
  override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ToolEntry")

  override fun serialize(encoder: Encoder, value: ToolEntry) {
    when (value) {
      is ToolEntry.Inline ->
        encoder.encodeSerializableValue(ToolYamlConfig.serializer(), value.config)
      is ToolEntry.Ref ->
        encoder.encodeSerializableValue(RefOnly.serializer(), RefOnly(value.path))
    }
  }

  override fun deserialize(decoder: Decoder): ToolEntry =
    decodeProjectConfigEntry(
      decoder = decoder,
      typeName = "ToolEntry",
      inlineSerializer = ToolYamlConfig.serializer(),
      onRef = { ToolEntry.Ref(it) },
      onInline = { ToolEntry.Inline(it) },
    )
}

object ProviderEntrySerializer : KSerializer<ProviderEntry> {
  override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ProviderEntry")

  override fun serialize(encoder: Encoder, value: ProviderEntry) {
    when (value) {
      is ProviderEntry.Inline ->
        encoder.encodeSerializableValue(BuiltInProviderConfig.serializer(), value.config)
      is ProviderEntry.Ref ->
        encoder.encodeSerializableValue(RefOnly.serializer(), RefOnly(value.path))
    }
  }

  override fun deserialize(decoder: Decoder): ProviderEntry =
    decodeProjectConfigEntry(
      decoder = decoder,
      typeName = "ProviderEntry",
      inlineSerializer = BuiltInProviderConfig.serializer(),
      onRef = { ProviderEntry.Ref(it) },
      onInline = { ProviderEntry.Inline(it) },
    )
}
