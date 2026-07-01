package xyz.block.trailblaze.yaml

import com.charleskorn.kaml.MultiLineStringStyle
import com.charleskorn.kaml.SingleLineStringStyle
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNamingStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.modules.SerializersModule
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.yaml.serializers.TrailYamlItemSerializer
import xyz.block.trailblaze.yaml.serializers.TrailblazeToolYamlWrapperSerializer
import xyz.block.trailblaze.yaml.unified.TrailDocument
import xyz.block.trailblaze.yaml.unified.UnifiedTrail
import xyz.block.trailblaze.yaml.unified.UnifiedTrailAdapter
import xyz.block.trailblaze.yaml.unified.UnifiedTrailConfig
import xyz.block.trailblaze.yaml.unified.UnifiedTrailEmitter
import xyz.block.trailblaze.yaml.unified.UnifiedTrailStep
import xyz.block.trailblaze.yaml.unified.UnifiedTrailStepSerializer

class TrailblazeYaml(
  toolSerializersByName: Map<String, KSerializer<out TrailblazeTool>> = emptyMap(),
) {

  companion object {
    val yamlConfiguration = YamlConfiguration(
      encodeDefaults = false,
      breakScalarsAt = 500,
      yamlNamingStrategy = YamlNamingStrategy.CamelCase,
      multiLineStringStyle = MultiLineStringStyle.Literal,
      singleLineStringStyle = SingleLineStringStyle.PlainExceptAmbiguous,
      strictMode = false,
    )

    val defaultYamlInstance = Yaml(
      configuration = yamlConfiguration,
    )

    /**
     * Shared default instance, lazily built on first access.
     *
     * On JVM/Android, first read invokes [buildTrailblazeYamlDefault], which calls
     * `TrailblazeSerializationInitializer.buildAllTools()` to collect classpath-discovered
     * and imperatively-registered tool classes. That call seals the tool set — any later
     * imperative registration of a new class throws. All registrations must complete
     * before any serialization read.
     *
     * On wasmJs the actual returns an empty [TrailblazeYaml] (wasmJs consumers decode
     * unknown tools via the `OtherTrailblazeTool` fallback and do not need typed
     * serializers).
     *
     * To register tool classes that are not classpath-discoverable (e.g. Android on-device
     * runners loading YAML from AssetManager), call
     * `TrailblazeSerializationInitializer.registerImperativeToolClasses(...)` during
     * class-load init blocks.
     */
    val Default: TrailblazeYaml by lazy { buildTrailblazeYamlDefault() }

    fun toolToYaml(toolName: String, trailblazeTool: TrailblazeTool): String =
      Default.encodeToolToYaml(toolName, trailblazeTool)

    /**
     * Encode a [JsonElement] as YAML.
     *
     * [JsonElement.serializer] only works with the JSON format (it verifies the encoder is a
     * [kotlinx.serialization.json.JsonEncoder]), so we convert manually instead of going
     * through kaml.
     */
    fun jsonToYaml(jsonElement: JsonElement): String =
      buildString { appendJsonElementAsYaml(jsonElement, indent = 0) }.trimEnd()

    private fun StringBuilder.appendJsonElementAsYaml(
      element: JsonElement,
      indent: Int,
      inlineFirst: Boolean = false,
    ) {
      when (element) {
        is JsonNull -> append("null")
        is JsonPrimitive -> appendYamlScalar(element)
        is JsonObject -> {
          if (element.isEmpty()) {
            append("{}")
            return
          }
          val pad = "  ".repeat(indent)
          element.entries.forEachIndexed { i, (key, value) ->
            if (i > 0 || !inlineFirst) append(pad)
            append(key)
            append(":")
            if ((value is JsonObject || value is JsonArray) && !isEmptyContainer(value)) {
              append("\n")
              appendJsonElementAsYaml(value, indent + 1)
            } else {
              append(" ")
              appendJsonElementAsYaml(value, indent + 1)
            }
            if (i < element.entries.size - 1) append("\n")
          }
        }
        is JsonArray -> {
          if (element.isEmpty()) {
            append("[]")
            return
          }
          val pad = "  ".repeat(indent)
          element.forEachIndexed { i, item ->
            append(pad)
            append("- ")
            appendJsonElementAsYaml(item, indent + 1, inlineFirst = true)
            if (i < element.size - 1) append("\n")
          }
        }
      }
    }

    private fun isEmptyContainer(element: JsonElement): Boolean = when (element) {
      is JsonObject -> element.isEmpty()
      is JsonArray -> element.isEmpty()
      else -> false
    }

    private fun StringBuilder.appendYamlScalar(primitive: JsonPrimitive) {
      if (!primitive.isString) {
        append(primitive.content)
        return
      }
      val s = primitive.content
      if (needsYamlQuoting(s)) {
        append('"')
        append(s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n"))
        append('"')
      } else {
        append(s)
      }
    }

    private fun needsYamlQuoting(s: String): Boolean =
      s.isEmpty() ||
        s.contains(':') || s.contains('#') || s.contains('\n') ||
        s.startsWith('{') || s.startsWith('[') || s.startsWith('"') ||
        s.startsWith('\'') || s.startsWith('*') || s.startsWith('&') ||
        s.startsWith('!') || s.startsWith('|') || s.startsWith('>') ||
        s.startsWith('%') || s.startsWith('@') ||
        s == "true" || s == "false" || s == "null" || s == "~" ||
        s == "yes" || s == "no" || s == "on" || s == "off" ||
        s.toLongOrNull() != null || s.toDoubleOrNull() != null
  }

  private val trailblazeToolYamlWrapperSerializer: TrailblazeToolYamlWrapperSerializer

  val trailYamlItemSerializer: TrailYamlItemSerializer

  /** Custom KAML serializer for [UnifiedTrailStep]; handles the dynamic per-classifier keys. */
  val unifiedTrailStepSerializer: UnifiedTrailStepSerializer

  private val yamlInstance: Yaml

  init {
    // Use a lazy provider so the serializer can reference the yaml instance
    // that contains its own serializers module (needed for contextual lookups).
    var lazyYaml: Yaml? = null
    trailblazeToolYamlWrapperSerializer = TrailblazeToolYamlWrapperSerializer(
      toolSerializersByName = toolSerializersByName,
      yamlInstanceProvider = { lazyYaml ?: error("Yaml instance not yet initialized") },
    )
    trailYamlItemSerializer = TrailYamlItemSerializer(
      defaultYamlInstance,
      trailblazeToolYamlWrapperSerializer,
    )
    unifiedTrailStepSerializer = UnifiedTrailStepSerializer(trailblazeToolYamlWrapperSerializer)
    yamlInstance = Yaml(
      configuration = yamlConfiguration,
      serializersModule = SerializersModule {
        contextual(TrailYamlItem::class, trailYamlItemSerializer)
        contextual(TrailblazeToolYamlWrapper::class, trailblazeToolYamlWrapperSerializer)
        contextual(UnifiedTrailStep::class, unifiedTrailStepSerializer)
      },
    )
    lazyYaml = yamlInstance
  }

  fun getInstance() = yamlInstance

  /** Serialize a single tool to YAML using this instance's tool serializers. */
  fun encodeToolToYaml(toolName: String, trailblazeTool: TrailblazeTool): String =
    yamlInstance.encodeToString(
      trailblazeToolYamlWrapperSerializer,
      TrailblazeToolYamlWrapper(
        name = toolName,
        trailblazeTool = trailblazeTool,
      ),
    )

  @OptIn(ExperimentalSerializationApi::class)
  fun encodeToString(items: List<TrailYamlItem>): String {
    val encoded = yamlInstance.encodeToString(
      ListSerializer(
        yamlInstance.serializersModule.getContextual(TrailYamlItem::class)
          ?: error("Missing contextual serializer for TrailYamlItem"),
      ),
      items,
    )
    return if (encoded.endsWith("\n")) encoded else "$encoded\n"
  }

  /**
   * Version-aware trail decode. Legacy v1 input passes through as-is; unified
   * input is lowered to the v1 shape using closest-wins resolution against
   * [deviceClassifiers].
   *
   * **Guarded against silent LLM-mode fallback.** If the input is the unified
   * format and any step has a non-empty classifier recording but
   * [deviceClassifiers] is empty, this throws — the alternative would be to
   * silently drop every recording during lowering and execute the trail in
   * LLM mode without the caller realizing. Errors point at three valid
   * alternatives:
   *
   *  - Pass real classifiers for execution (the device's broad-first
   *    classifier segments as a provider emits them, e.g. `[ios, iphone]`;
   *    the adapter expands them through the lineage to a most-specific-first
   *    chain via [xyz.block.trailblaze.devices.TrailblazeClassifierLineage])
   *  - Use [extractTrailConfig] if you only need static config — works for
   *    both formats without needing classifiers
   *  - Use [decodeTrailDocument] if you need format-native access to the
   *    classifier-keyed recordings
   *
   * Unified trails that have only `recordable: false` steps (no recordings to
   * lose) pass through with empty classifiers — there's nothing to silently
   * drop. v1 input never trips the guard regardless of classifiers.
   */
  fun decodeTrail(
    yaml: String,
    deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList(),
  ): List<TrailYamlItem> = when (val doc = decodeTrailDocument(yaml)) {
    is TrailDocument.V1 -> doc.items
    is TrailDocument.Unified -> {
      if (deviceClassifiers.isEmpty()) {
        val hasRecordings = doc.trail.trail.any { step ->
          step.recordings.values.any { it.isNotEmpty() }
        }
        check(!hasRecordings) {
          "decodeTrail was called on a unified trail with recordings, but no device " +
            "classifiers were provided. Lowering with no classifiers would drop every " +
            "recording (closest-wins finds nothing) and silently execute every step in " +
            "LLM mode. Pass the device's classifier segments (e.g. [ios, iphone]) " +
            "for execution; or call extractTrailConfig(yaml) if you only need static " +
            "config; or call decodeTrailDocument(yaml) for format-native access."
        }
      }
      UnifiedTrailAdapter.lowerToTrailItems(doc.trail, deviceClassifiers)
    }
  }

  /**
   * Strict-v1 parse. Used by [decodeTrailDocument] for the v1 fast path; do
   * NOT call from outside the dispatcher — public callers should always go
   * through [decodeTrail] (or [decodeTrailDocument] directly) so v3 inputs
   * route correctly.
   */
  @OptIn(ExperimentalSerializationApi::class)
  internal fun decodeV1TrailStrict(yaml: String): List<TrailYamlItem> {
    val trailItemList = yamlInstance.decodeFromString(
      ListSerializer(
        yamlInstance.serializersModule.getContextual(TrailYamlItem::class)
          ?: error("Missing contextual serializer for TrailYamlItem"),
      ),
      yaml,
    )
    val configItems = trailItemList.filterIsInstance<TrailYamlItem.ConfigTrailItem>()
    require(configItems.isEmpty() || (configItems.size == 1 && configItems[0] == trailItemList[0])) {
      "Only one config item is allowed, and it must be the first item in the trail."
    }
    return trailItemList
  }

  /**
   * Decodes a YAML list of tool wrappers directly (no TrailYamlItem wrapping).
   * Input format: `- tapOnPoint:\n    x: 200\n    y: 400`
   */
  @OptIn(ExperimentalSerializationApi::class)
  fun decodeTools(yaml: String): List<TrailblazeToolYamlWrapper> {
    val contextual = yamlInstance.serializersModule
      .getContextual(TrailblazeToolYamlWrapper::class)
      ?: error("Missing contextual serializer for TrailblazeToolYamlWrapper")
    return yamlInstance.decodeFromString(ListSerializer(contextual), yaml)
  }

  /**
   * Extract the config block from either format without needing classifiers.
   * Routes through [decodeTrailDocument] so it never trips the
   * unified-with-recordings guard in [decodeTrail]; for the unified format it
   * lowers only the config (recordings are irrelevant for config extraction).
   */
  fun extractTrailConfig(yaml: String): TrailConfig? = when (val doc = decodeTrailDocument(yaml)) {
    is TrailDocument.V1 -> doc.items.filterIsInstance<TrailYamlItem.ConfigTrailItem>()
      .firstOrNull()?.config
    is TrailDocument.Unified -> UnifiedTrailAdapter.lowerConfig(doc.trail.config)
  }

  @OptIn(ExperimentalSerializationApi::class)
  fun extractTrailConfig(trailItems: List<TrailYamlItem>): TrailConfig? {
    val configItem = trailItems.filterIsInstance<TrailYamlItem.ConfigTrailItem>().firstOrNull()
    return configItem?.config
  }

  /**
   * Returns the trimmed `skip:` reason from the first config block in this trail, or null if no
   * config block has a non-blank skip reason set.
   *
   * Mirrors the semantics of [xyz.block.trailblaze.cli.TrailCommand.readSkipReason] / the CLI's
   * `planTrailExecution` planner step. Every runner-side entry point that iterates trail items —
   * `AndroidTrailblazeRule.runSuspend`, the equivalent loop in any host-side or downstream rule,
   * `TrailblazeHostYamlRunner` (Playwright / Maestro / Electron paths), `BasePlaywrightNativeTest`,
   * `BasePlaywrightElectronTest`, `BaseHostTrailblazeTest`, `BaseComposeTest` — must consult this
   * helper before iterating so a `config.skip:` marker short-circuits execution consistently
   * regardless of which entry point the YAML went through.
   *
   * The CLI's pre-flight planner already honors `skip:` for the `trailblaze run` path; this helper
   * exists so the *runtime* paths match. Without it, a trail with `skip:` set runs end-to-end
   * whenever someone wires it into an instrumentation test instead of the CLI.
   */
  fun firstSkipReason(trailItems: List<TrailYamlItem>): String? =
    extractTrailConfig(trailItems)?.skip?.trim()?.takeIf { it.isNotEmpty() }

  fun hasActionableSteps(trailItems: List<TrailYamlItem>): Boolean =
    trailItems.any { item ->
      when (item) {
        is TrailYamlItem.PromptsTrailItem -> item.promptSteps.isNotEmpty()
        is TrailYamlItem.ToolTrailItem -> item.tools.isNotEmpty()
        is TrailYamlItem.ConfigTrailItem -> false
      }
    }

  fun hasRecordedSteps(trailItems: List<TrailYamlItem>): Boolean = trailItems.any { item ->
    when (item) {
      is TrailYamlItem.PromptsTrailItem -> {
        item.promptSteps.any { promptStep -> promptStep.recording != null }
      }
      is TrailYamlItem.ConfigTrailItem,
      is TrailYamlItem.ToolTrailItem -> false
    }
  }

  /**
   * Version-aware "does this trail contain ANY recordings?" check, used by
   * pre-execution paths (e.g. the desktop app's auto-detect) that don't yet
   * know which device will run the trail. For v1, looks for any non-null
   * `recording:`. For the unified format, looks for any classifier with a
   * non-empty tool list. Never throws — malformed input returns false.
   */
  fun hasRecordedSteps(yaml: String): Boolean = try {
    when (val doc = decodeTrailDocument(yaml)) {
      is TrailDocument.V1 -> hasRecordedSteps(doc.items)
      is TrailDocument.Unified -> doc.trail.trail.any { step ->
        step.recordings.values.any { it.isNotEmpty() }
      }
    }
  } catch (_: Throwable) {
    false
  }

  /**
   * Decode a unified Trail YAML document. The unified format has exactly two
   * top-level keys — `config:` (singleton mapping) and `trail:` (ordered list
   * of steps). See [docs/devlog/2026-05-22-trail-yaml-unified-syntax.md].
   *
   * Throws on malformed input — callers that need version-agnostic parsing
   * should use [decodeTrailDocument] instead.
   */
  fun decodeUnifiedTrail(yaml: String): UnifiedTrail {
    val rootNode = yamlInstance.parseToYamlNode(yaml)
    require(rootNode is YamlMap) {
      "Unified Trail YAML root must be a mapping with `config:` and `trail:` keys; " +
        "got a ${rootNode::class.simpleName} at the root."
    }
    var config: UnifiedTrailConfig? = null
    var trail: List<UnifiedTrailStep>? = null
    for ((keyNode, valueNode) in rootNode.entries) {
      when (val key = keyNode.content) {
        "config" -> config = yamlInstance.decodeFromYamlNode(
          UnifiedTrailConfig.serializer(),
          valueNode,
        )
        "trail" -> trail = yamlInstance.decodeFromYamlNode(
          ListSerializer(unifiedTrailStepSerializer),
          valueNode,
        )
        else -> throw IllegalArgumentException(
          "Unexpected top-level key `$key` in unified trail (expected `config` or `trail`).",
        )
      }
    }
    requireNotNull(config) { "unified trail is missing required top-level `config:` key" }
    requireNotNull(trail) { "unified trail is missing required top-level `trail:` key" }
    return UnifiedTrail(config = config, trail = trail)
  }

  /**
   * Version-aware parse. **Legacy v1 is the default** (the vast majority of
   * files in the repo are still v1); the unified format is the fallback. The
   * check is a plain try/catch — KAML throws a clean exception when the v1
   * list-shape serializer hits a unified-format mapping root, so the v1
   * attempt is essentially free for v1 inputs and pays for one extra parse
   * attempt only when the file is actually the unified format.
   *
   * When both parsers reject the input, this rethrows the v1 error verbatim
   * (preserving its concrete type — typically `SerializationException`) with
   * the unified-format error attached as a suppressed exception. We preserve
   * the v1 type because v1 is the default format; callers that catch the
   * specific KAML exception types keep working unchanged.
   */
  fun decodeTrailDocument(yaml: String): TrailDocument {
    val v1Error: Throwable = try {
      return TrailDocument.V1(decodeV1TrailStrict(yaml))
    } catch (e: Throwable) {
      e
    }
    try {
      return TrailDocument.Unified(decodeUnifiedTrail(yaml))
    } catch (unifiedError: Throwable) {
      v1Error.addSuppressed(unifiedError)
    }
    throw v1Error
  }

  /**
   * Encode a [UnifiedTrail] document as YAML. The migrator uses this to write
   * the unified output file; recorder integration will follow later.
   *
   * [leadingComments] are emitted as `# ` comment lines at the top of the
   * file before the `config:` block — used by the migrator to surface NL
   * drift warnings inline.
   */
  fun encodeUnifiedTrailToString(
    trail: UnifiedTrail,
    leadingComments: List<String> = emptyList(),
  ): String = UnifiedTrailEmitter(
    yamlInstance = yamlInstance,
    toolWrapperSerializer = trailblazeToolYamlWrapperSerializer,
  ).emit(trail, leadingComments)
}
