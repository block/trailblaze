package xyz.block.trailblaze.yaml

import com.charleskorn.kaml.MultiLineStringStyle
import com.charleskorn.kaml.SingleLineStringStyle
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlList
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

class TrailblazeYaml internal constructor(
  toolSerializersByName: Map<String, KSerializer<out TrailblazeTool>>,
  /**
   * When true, unknown YAML keys in closed shapes (config blocks, class-backed tool args,
   * selectors) fail the decode with kaml's `UnknownPropertyException` instead of being silently
   * dropped. Off by default: runtime decoding stays lenient so an older binary can still run
   * trail files that carry newer schema fields it doesn't know about. Lint/validation surfaces
   * opt in via `createTrailblazeYaml(strict = true)` to catch typo'd or stale keys; no runtime
   * path enables it today.
   */
  private val strict: Boolean,
) {

  /** Preserves the pre-strict no-arg binary signature `TrailblazeYaml()`. */
  constructor() : this(emptyMap(), strict = false)

  /**
   * Preserves the pre-strict `TrailblazeYaml(Map)` binary signature; strict parsing is opt-in
   * through the `createTrailblazeYaml` factories, which call the `internal` primary. These
   * overloads are written out explicitly rather than via `@JvmOverloads` because `TrailblazeYaml`
   * lives in commonMain and `kotlin.jvm.JvmOverloads` isn't available on the wasmJs target — the
   * explicit form is multiplatform-safe (same pattern as
   * [xyz.block.trailblaze.api.TrailblazeNodeSelectorResolver]). They carry no default arg so they
   * don't collide with the primary under Kotlin overload resolution.
   */
  constructor(
    toolSerializersByName: Map<String, KSerializer<out TrailblazeTool>>,
  ) : this(toolSerializersByName, strict = false)

  companion object {
    /**
     * Reserved top-level keys of a legacy list-shape trail item. Used by [isBareToolEnvelope] to
     * tell a per-tool dispatch envelope (`- <toolName>:`) apart from a legacy trail list
     * (`- config:` / `- prompts:` / …). No tool is ever named one of these.
     */
    private val RESERVED_TRAIL_ITEM_KEYS = setOf("config", "prompts", "tools", "trailhead")

    val yamlConfiguration = yamlConfigurationWithStrictness(strict = false)

    private fun yamlConfigurationWithStrictness(strict: Boolean) = YamlConfiguration(
      encodeDefaults = false,
      breakScalarsAt = 500,
      yamlNamingStrategy = YamlNamingStrategy.CamelCase,
      multiLineStringStyle = MultiLineStringStyle.Literal,
      singleLineStringStyle = SingleLineStringStyle.PlainExceptAmbiguous,
      strictMode = strict,
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

  /**
   * Trailhead variant of [unifiedTrailStepSerializer]: same shape, except each `recording:`
   * classifier is a single tool call (a map), not a list — a trailhead is one tool per platform.
   */
  val unifiedTrailheadSerializer: UnifiedTrailStepSerializer

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
    unifiedTrailheadSerializer = UnifiedTrailStepSerializer(trailblazeToolYamlWrapperSerializer, isTrailhead = true)
    yamlInstance = Yaml(
      configuration = yamlConfigurationWithStrictness(strict),
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
   * Encode a bare tool-wrapper list — `- <toolName>:` at the root, NOT wrapped in a `- tools:`
   * trail item. This is the wire shape of the on-device-RPC per-tool dispatch envelope (the host
   * serializes one authored tool, sends it to the device, and the device runs it). Decoded back
   * via [decodeTools] / [decodeTrailOrToolEnvelope], never via [decodeTrailDocument] — so the
   * per-tool round trip carries no dependency on the legacy list-shape trail parser.
   */
  @OptIn(ExperimentalSerializationApi::class)
  fun encodeTools(tools: List<TrailblazeToolYamlWrapper>): String {
    // Guard the envelope-vs-trail discrimination at the source. A tool whose name collides with a
    // reserved trail-item key would encode to e.g. `- config:`, which [decodeTrailOrToolEnvelope]
    // would route to the trail parser and silently mis-read as a ConfigTrailItem rather than a tool.
    // The discrimination is structural (single-entry, non-reserved key), so this invariant must hold
    // on encode too — fail loud here instead of corrupting silently on the far side.
    val reserved = tools.map { it.name }.filter { it in RESERVED_TRAIL_ITEM_KEYS }
    require(reserved.isEmpty()) {
      "Cannot encode a tool envelope: tool name(s) $reserved collide with reserved trail-item keys " +
        "$RESERVED_TRAIL_ITEM_KEYS. A tool with such a name is indistinguishable from a trail item."
    }
    val contextual = yamlInstance.serializersModule
      .getContextual(TrailblazeToolYamlWrapper::class)
      ?: error("Missing contextual serializer for TrailblazeToolYamlWrapper")
    val encoded = yamlInstance.encodeToString(ListSerializer(contextual), tools)
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
        // A classifier declared as explicitly empty (`classifier: []`, a deterministic no-op —
        // see ToolRecording's 3-state doc) has no tool calls to drop either way, so it doesn't
        // trip this guard — only a classifier with real tool calls does.
        val hasRecordings = doc.trail.trail.any { step ->
          step.recordings.values.any { it.isNotEmpty() }
        } || doc.trail.trailhead?.recordings?.values?.any { it.isNotEmpty() } == true
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
    val trailheadItems = trailItemList.filterIsInstance<TrailYamlItem.TrailheadTrailItem>()
    require(trailheadItems.size <= 1) {
      "Only one trailhead item is allowed in a trail."
    }
    if (trailheadItems.size == 1) {
      val trailheadIndex = trailItemList.indexOf(trailheadItems[0])
      val firstStepIndex = trailItemList.indexOfFirst {
        it is TrailYamlItem.PromptsTrailItem || it is TrailYamlItem.ToolTrailItem
      }
      // The trailhead is the deterministic step 0: it must sit after the config (if any) and before
      // any prompts/tools steps, so it always runs first.
      require(configItems.isEmpty() || trailheadIndex > trailItemList.indexOf(configItems[0])) {
        "The trailhead item must come after the config item."
      }
      require(firstStepIndex == -1 || trailheadIndex < firstStepIndex) {
        "The trailhead item must come before any prompts/tools steps — it is the trail's step 0."
      }
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
   * Decode a value received over the on-device-RPC `yaml` field, which is EITHER a full trail
   * document OR a per-tool dispatch envelope. The device-side runner receives both shapes on the
   * same field: the CLI/desktop "run this whole trail on device" path sends a trail document,
   * while the host-drives-the-loop path sends one authored tool at a time as a bare tool-wrapper
   * list (see [encodeTools]).
   *
   * Scope of the v1 decoupling: ONLY the per-tool-envelope branch is off the legacy parser — it is
   * decoded straight through [decodeTools] and wrapped into a single [TrailYamlItem.ToolTrailItem],
   * never touching [decodeTrailDocument]. A trail *document* still falls through to [decodeTrail] →
   * [decodeTrailDocument], which continues to try v1 strict first (unified today; legacy v1 tolerated
   * while it exists). So this method moves per-tool dispatch off v1; trail-document dispatch remains
   * v1-coupled until [decodeTrailDocument]'s v1-first path is reworked (a follow-up).
   */
  fun decodeTrailOrToolEnvelope(
    yaml: String,
    deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList(),
  ): List<TrailYamlItem> =
    if (isBareToolEnvelope(yaml)) {
      listOf(TrailYamlItem.ToolTrailItem(decodeTools(yaml)))
    } else {
      decodeTrail(yaml, deviceClassifiers)
    }

  /**
   * True when [yaml] is a bare tool-wrapper list (root is a YAML sequence whose every item is a
   * single-entry tool map) rather than a trail document. A trail document is either a mapping
   * (unified format) or a sequence of reserved `config` / `prompts` / `tools` / `trailhead`
   * items (legacy list shape) — a per-tool envelope is a sequence whose item keys are tool names,
   * which can never be one of those reserved trail-item keys. Discriminating on the reserved-key
   * set (rather than attempting a tool decode) is exact: the tool-wrapper deserializer silently
   * falls back to a raw tool for any unknown key, so a `- config:` item would otherwise be
   * mis-read as a tool named "config".
   *
   * Each item must be a map with EXACTLY ONE entry whose key is non-reserved. The single-entry
   * requirement matters because [TrailblazeToolYamlWrapperSerializer] reads only the first entry:
   * an empty map (`- {}`) or a multi-key map would otherwise be routed to [decodeTools] and either
   * mis-classified or have its extra keys silently dropped. A genuine tool wrapper is always a
   * single-entry `<toolName>: <args>` map, so this is exact, not merely conservative.
   */
  private fun isBareToolEnvelope(yaml: String): Boolean {
    val root = try {
      yamlInstance.parseToYamlNode(yaml)
    } catch (_: Throwable) {
      return false
    }
    if (root !is YamlList || root.items.isEmpty()) return false
    return root.items.all { item ->
      item is YamlMap &&
        item.entries.size == 1 &&
        item.entries.keys.single().content !in RESERVED_TRAIL_ITEM_KEYS
    }
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
    // No device here (static config extraction), so no per-classifier driver to resolve —
    // pass resolvedDriver = null explicitly. The device-aware driver resolution happens in
    // lowerToTrailItems, not here (or via the device-aware overload below). Skip resolves
    // device-agnostically: skipped if any classifier declares a reason (see resolveSkip).
    is TrailDocument.Unified -> UnifiedTrailAdapter.lowerConfig(
      doc.trail.config,
      resolvedDriver = null,
      resolvedSkip = UnifiedTrailAdapter.resolveSkip(doc.trail.config, emptyList()),
    )
  }

  /**
   * Device-aware config extraction: same as [extractTrailConfig] but resolves the unified
   * format's per-classifier `devices:` driver pin for [deviceClassifiers] (closest-wins), so
   * the returned [TrailConfig.driver] reflects the driver the trail pins for THIS device.
   * Needed by the CLI, which resolves the driver before device selection and must see a
   * unified trail's `devices: {android: ANDROID_ONDEVICE_ACCESSIBILITY}` pin rather than the
   * device's default driver. Still routes through [decodeTrailDocument] (never the recordings
   * guard), so it is safe for any classifier list including empty (empty → null driver, same
   * as the no-classifier overload). v1 configs carry their scalar `driver:` unchanged.
   */
  fun extractTrailConfig(
    yaml: String,
    deviceClassifiers: List<TrailblazeDeviceClassifier>,
  ): TrailConfig? = when (val doc = decodeTrailDocument(yaml)) {
    is TrailDocument.V1 -> doc.items.filterIsInstance<TrailYamlItem.ConfigTrailItem>()
      .firstOrNull()?.config
    is TrailDocument.Unified -> UnifiedTrailAdapter.lowerConfig(
      doc.trail.config,
      resolvedDriver = UnifiedTrailAdapter.resolveDriver(doc.trail.config, deviceClassifiers),
      resolvedSkip = UnifiedTrailAdapter.resolveSkip(doc.trail.config, deviceClassifiers),
    )
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
        // A trailhead is always actionable — its `init` guarantees a step and/or tools to run.
        is TrailYamlItem.TrailheadTrailItem -> true
        is TrailYamlItem.ConfigTrailItem -> false
      }
    }

  fun hasRecordedSteps(trailItems: List<TrailYamlItem>): Boolean = trailItems.any { item ->
    when (item) {
      is TrailYamlItem.PromptsTrailItem -> {
        item.promptSteps.any { promptStep -> promptStep.recording != null }
      }
      // A declared-but-empty trailhead (`tools = emptyList()`, an explicit no-op) still counts:
      // it replays deterministically (zero tools, no AI), same as a step whose `recording` is a
      // non-null empty ToolRecording just above. Only `tools == null` (never declared) is excluded.
      is TrailYamlItem.TrailheadTrailItem -> item.trailhead.tools != null
      // A root-level `- tools:` step IS a recording: every runner loop force-executes it directly
      // (no AI), so a tools-only trail replays deterministically and must not be classified as
      // agent-driven by auto mode, session badges, or the CLI.
      is TrailYamlItem.ToolTrailItem -> true
      is TrailYamlItem.ConfigTrailItem -> false
    }
  }

  /**
   * Version-aware "does this trail contain ANY recordings?" check, used by
   * pre-execution paths (e.g. the desktop app's auto-detect, and
   * [xyz.block.trailblaze.cli.TrailCommand.resolveUseRecordedSteps]'s CLI
   * auto mode) that don't yet know which device will run the trail. For v1,
   * looks for any non-null `recording:`. For the unified format, looks for
   * any DECLARED classifier — including an explicit `classifier: []` /
   * `classifier: {}` no-op, which replays deterministically (zero tools, no
   * AI) same as a non-empty recording, and must not be treated as "no
   * recordings" here or auto mode would route it through pure AI execution
   * instead. Never throws — malformed input returns false.
   */
  fun hasRecordedSteps(yaml: String): Boolean = try {
    when (val doc = decodeTrailDocument(yaml)) {
      is TrailDocument.V1 -> hasRecordedSteps(doc.items)
      is TrailDocument.Unified -> doc.trail.trail.any { step ->
        step.recordings.isNotEmpty()
      } || doc.trail.trailhead?.recordings?.isNotEmpty() == true
    }
  } catch (_: Throwable) {
    false
  }

  /**
   * Decode a unified Trail YAML document. Top-level keys: `config:` (optional —
   * defaults to an empty config when absent), `trailhead:` (optional — the
   * deterministic step 0), and `trail:` (required and non-empty — the ordered
   * list of steps, the one key that makes the file a test).
   * See [docs/devlog/2026-05-22-trail-yaml-unified-syntax.md].
   *
   * Throws on malformed input — callers that need version-agnostic parsing
   * should use [decodeTrailDocument] instead.
   */
  fun decodeUnifiedTrail(yaml: String): UnifiedTrail {
    val rootNode = yamlInstance.parseToYamlNode(yaml)
    require(rootNode is YamlMap) {
      "Unified Trail YAML root must be a mapping with a `trail:` key (and optional `config:` / " +
        "`trailhead:`); got a ${rootNode::class.simpleName} at the root."
    }
    var config: UnifiedTrailConfig? = null
    var trailhead: UnifiedTrailStep? = null
    var trail: List<UnifiedTrailStep>? = null
    for ((keyNode, valueNode) in rootNode.entries) {
      when (val key = keyNode.content) {
        "config" -> config = yamlInstance.decodeFromYamlNode(
          UnifiedTrailConfig.serializer(),
          valueNode,
        )
        "trailhead" -> trailhead = yamlInstance.decodeFromYamlNode(
          unifiedTrailheadSerializer,
          valueNode,
        )
        "trail" -> trail = yamlInstance.decodeFromYamlNode(
          ListSerializer(unifiedTrailStepSerializer),
          valueNode,
        )
        else -> throw IllegalArgumentException(
          "Unexpected top-level key `$key` in unified trail (expected `config`, `trailhead`, or `trail`).",
        )
      }
    }
    // `config:` is optional — every UnifiedTrailConfig field defaults (no target → generic tools;
    // no devices → inherit from the trailmap manifest; no id → still runs), so an absent config is
    // an empty config. `trail:` is normally required and non-empty: it is what makes the file a
    // test, and a trailhead-only / empty trail would run its bootstrap and then pass with no real
    // test steps (a vacuous always-pass).
    //
    // The ONE exception is a config-only metadata document: a `config:` block with no `trail:`
    // steps and no `trailhead:`. This is the shape a test case with no runnable steps yet lowers
    // to — it is not meant to run as a test, it preserves the case's
    // metadata. Accept it so those files stay readable; reject every other stepless shape (a
    // trailhead-only trail, or a document with neither config nor steps).
    val resolvedTrail = trail ?: emptyList()
    require(resolvedTrail.isNotEmpty() || (config != null && trailhead == null)) {
      "unified trail is missing a non-empty top-level `trail:` — every trail needs at least one " +
        "step. The only stepless document allowed is a config-only metadata doc (a `config:` " +
        "block with no `trail:` and no `trailhead:`)."
    }
    return UnifiedTrail(
      config = config ?: UnifiedTrailConfig(),
      trailhead = trailhead,
      trail = resolvedTrail,
    )
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
