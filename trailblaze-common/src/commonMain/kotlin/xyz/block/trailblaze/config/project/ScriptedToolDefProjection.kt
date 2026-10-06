package xyz.block.trailblaze.config.project

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import xyz.block.trailblaze.config.InlineScriptToolConfig
import xyz.block.trailblaze.config.ScriptedToolRuntime
import xyz.block.trailblaze.config.TrailheadMetadata
import xyz.block.trailblaze.scripting.ScriptedToolSchemaRefFlattener
import xyz.block.trailblaze.util.Console

/**
 * One typed tool as the analyzer (`sdks/typescript/tools/extract-tool-defs.mjs`) extracts it from an
 * `export const X = trailblaze.tool<I>(spec, handler)`: the fields of its envelope that describe the
 * tool, without the machine-specific `sourcePath`.
 *
 * This is also the shape of a generated `<tool>.tooldefs.json`, which the build writes beside a
 * tool's `.bundle.js` so a runtime with no analyzer (a device, an installed CLI) still knows the
 * tool's name, description and schema without a hand-written `<tool>.yaml`.
 */
@Serializable
data class GeneratedScriptedToolDef(
  val name: String,
  /** TSDoc above the `export const`; the spec's `description` wins over it. */
  val description: String? = null,
  val inputSchema: JsonObject = JsonObject(emptyMap()),
  /** The inline spec literal, keyed by `TrailblazeTypedToolSpec` field name. */
  val spec: JsonObject? = null,
  /** The spec was a reference the analyzer couldn't read, so [spec] is missing even if authored. */
  val uncapturedSpec: Boolean = false,
)

/** Contents of a generated `<tool>.tooldefs.json`: every typed tool its `.ts` exports. */
@Serializable
data class GeneratedScriptedToolDefsFile(val tools: List<GeneratedScriptedToolDef>) {
  companion object {
    const val SUFFIX = ".tooldefs.json"
  }
}

/**
 * Turns what the analyzer read from a typed `.ts` tool into the runtime [InlineScriptToolConfig].
 *
 * Shared by the host's live analyzer path (`AnalyzerScriptedToolEnrichment`) and by
 * [xyz.block.trailblaze.config.ScriptedToolNameDiscoverer], which reads the same analyzer output from
 * a generated `.tooldefs.json`, so a tool resolves to the same config whichever way it was read.
 */
object ScriptedToolDefProjection {

  /**
   * The config for a tool with no hand-written descriptor: everything comes from the `.ts`.
   *
   * [descriptor] is a meta-only YAML (`script:` plus gates, no `name:`) when one exists. Its fields
   * win over the spec's, and its gates are combined with the spec's the same way
   * [mergeMeta] combines them.
   */
  fun descriptorlessConfig(
    def: GeneratedScriptedToolDef,
    script: String,
    descriptor: TrailmapScriptedToolFile? = null,
  ): InlineScriptToolConfig {
    val spec = def.spec
    val surfaceToLlm = (descriptor?.surfaceToLlm ?: true) && specBoolean(spec, "surfaceToLlm", default = true)
    val isRecordable = (descriptor?.isRecordable ?: true) && specBoolean(spec, "isRecordable", default = true)
    return InlineScriptToolConfig(
      script = script,
      name = def.name,
      description = specDescriptionOf(spec) ?: def.description,
      requiresHost = (descriptor?.requiresHost ?: false) || specBoolean(spec, "requiresHost", default = false),
      surfaceToLlm = surfaceToLlm,
      isRecordable = isRecordable,
      runtime = descriptor?.runtime ?: specRuntimeOf(def.name, spec),
      meta = mergeMeta(
        descriptorMeta = descriptor?.meta,
        requiresHost = descriptor?.requiresHost ?: false,
        supportedPlatforms = descriptor?.supportedPlatforms,
        analyzerSpec = spec,
        surfaceToLlm = surfaceToLlm,
        isRecordable = isRecordable,
      ),
      // Inline any `$ref` (named enum / Record / nested type) so every consumer sees a
      // self-contained schema — see [ScriptedToolSchemaRefFlattener].
      inputSchema = ScriptedToolSchemaRefFlattener.flatten(def.inputSchema),
      // The analyzer read the tool's `<I>` generic, so this schema IS the complete argument
      // contract — including `properties: {}` for a genuinely no-arg tool.
      inputSchemaExhaustive = true,
      trailhead = trailheadOf(def.name, spec),
    )
  }

  /**
   * The spec's `description`, the middle tier of the description precedence (YAML `description:` >
   * spec `description` > TSDoc). Null when absent, non-string or blank, so the TSDoc applies.
   */
  fun specDescriptionOf(spec: JsonObject?): String? =
    (spec?.get("description") as? JsonPrimitive)
      ?.takeIf { it.isString }
      ?.content
      ?.takeIf { it.isNotBlank() }

  /**
   * The spec's `runtime`, or null when it declares none (in-process). An unknown value fails: a
   * misspelled `"subprocess"` would otherwise run in-process, where the Node APIs it needs are absent.
   */
  fun specRuntimeOf(toolName: String, spec: JsonObject?): ScriptedToolRuntime? {
    val raw = spec?.get("runtime") ?: return null
    val value = (raw as? JsonPrimitive)?.takeIf { it.isString }?.content
    return ScriptedToolRuntime.entries.firstOrNull { it.wireName == value }
      ?: throw IllegalArgumentException(
        "Tool '$toolName': spec `runtime` is $raw, expected one of " +
          ScriptedToolRuntime.entries.joinToString { "\"${it.wireName}\"" } + ".",
      )
  }

  /**
   * The spec's `trailhead` as [TrailheadMetadata], the same shape a `*.trailhead.yaml` sidecar's
   * `trailhead:` block produces. A malformed block is logged and dropped (or, with both `to` and
   * `dynamic: true`, treated as dynamic) rather than failing the tool.
   */
  fun trailheadOf(toolName: String, spec: JsonObject?): TrailheadMetadata? {
    val raw = spec?.get("trailhead") as? JsonObject ?: return null
    val to = (raw["to"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
    val dynamic = (raw["dynamic"] as? JsonPrimitive)?.booleanOrNull ?: false
    return when {
      to != null && dynamic -> {
        Console.log(
          "[ScriptedToolDefProjection] tool '$toolName': spec's 'trailhead' block sets both " +
            "'to' and 'dynamic: true' — mutually exclusive (see TrailheadMetadata). Dropping 'to' " +
            "and treating as dynamic. Raw value: $raw",
        )
        TrailheadMetadata(dynamic = true)
      }
      to == null && !dynamic -> {
        Console.log(
          "[ScriptedToolDefProjection] tool '$toolName': spec declares a 'trailhead' block " +
            "with neither a non-blank 'to' nor 'dynamic: true' — not a real bootstrap " +
            "destination, dropping trailhead role for this tool. Raw value: $raw",
        )
        null
      }
      else -> TrailheadMetadata(to = to, dynamic = dynamic)
    }
  }

  /**
   * Merges the namespaced `_meta` keys from, lowest precedence first: the `.ts` spec, the
   * descriptor's explicit `_meta:` map, and the descriptor's top-level shortcuts. Later sources win,
   * except `sensitiveArgNames`, which is the union of all three — overriding could only un-mask an
   * argument some author declared secret. Null when nothing contributes a key.
   */
  fun mergeMeta(
    descriptorMeta: JsonObject?,
    requiresHost: Boolean,
    supportedPlatforms: List<String>?,
    analyzerSpec: JsonObject?,
    surfaceToLlm: Boolean = true,
    isRecordable: Boolean = true,
    sensitiveArgNames: List<String>? = null,
  ): JsonObject? {
    val explicit = descriptorMeta ?: JsonObject(emptyMap())
    val needsSupportedPlatforms = !supportedPlatforms.isNullOrEmpty()
    val analyzerProjected = projectAnalyzerSpec(analyzerSpec)
    val unionSensitiveArgNames = buildSet {
      (analyzerProjected["trailblaze/sensitiveArgNames"] as? JsonArray)?.let { addAll(it.strings()) }
      (explicit["trailblaze/sensitiveArgNames"] as? JsonArray)?.let { addAll(it.strings()) }
      sensitiveArgNames?.let(::addAll)
    }
    if (
      explicit.isEmpty() &&
      !needsSupportedPlatforms &&
      !requiresHost &&
      surfaceToLlm &&
      isRecordable &&
      unionSensitiveArgNames.isEmpty() &&
      analyzerProjected.isEmpty()
    ) {
      return null
    }
    return buildJsonObject {
      // `put` is last-write-wins, so later sources override earlier ones.
      analyzerProjected.forEach { (k, v) -> put(k, v) }
      explicit.forEach { (k, v) -> put(k, v) }
      if (needsSupportedPlatforms) {
        put("trailblaze/supportedPlatforms", buildJsonArray { supportedPlatforms.orEmpty().forEach { add(JsonPrimitive(it)) } })
      }
      if (requiresHost) put("trailblaze/requiresHost", JsonPrimitive(true))
      // `true` is the default and emits no key; only the opt-out folds in.
      if (!surfaceToLlm) put("trailblaze/surfaceToLlm", JsonPrimitive(false))
      if (!isRecordable) put("trailblaze/isRecordable", JsonPrimitive(false))
      if (unionSensitiveArgNames.isNotEmpty()) {
        // Sorted so the output doesn't depend on which source contributed which name.
        put(
          "trailblaze/sensitiveArgNames",
          buildJsonArray { unionSensitiveArgNames.sorted().forEach { add(JsonPrimitive(it)) } },
        )
      }
    }
  }

  /**
   * Maps the spec's bare field names (`supportedPlatforms`, ...) onto the namespaced `_meta` keys the
   * runtime reads (`trailblaze/supportedPlatforms`, ...). Unrecognized fields are dropped.
   *
   * `description`, `trailhead` and `runtime` are deliberately absent: they are primary descriptor
   * fields, not `_meta` gates, and route into [InlineScriptToolConfig] directly.
   *
   * SISTER-IMPL-TAG: typed-tool-spec-fields. The field set must stay in lockstep with
   * `sdks/typescript/src/tool-core.ts` (`TrailblazeTypedToolSpec`) and
   * `sdks/typescript/tools/extract-tool-defs.mjs` (`RECOGNIZED_SPEC_FIELDS`); the runtime parsers
   * (`TrailblazeToolMeta.fromJsonObject`, `QuickJsToolMeta.fromSpec`) read the namespaced keys.
   */
  fun projectAnalyzerSpec(analyzerSpec: JsonObject?): Map<String, JsonElement> {
    if (analyzerSpec == null || analyzerSpec.isEmpty()) return emptyMap()
    return PROJECTED_SPEC_FIELDS.mapNotNull { field ->
      analyzerSpec[field]?.let { "trailblaze/$field" to it }
    }.toMap()
  }

  private val PROJECTED_SPEC_FIELDS = listOf(
    "supportedPlatforms",
    "requiresContext",
    "requiresHost",
    "supportedDrivers",
    "surfaceToLlm",
    "isRecordable",
    "sensitiveArgNames",
  )

  private fun specBoolean(spec: JsonObject?, field: String, default: Boolean): Boolean =
    (spec?.get(field) as? JsonPrimitive)?.booleanOrNull ?: default

  private fun JsonArray.strings(): List<String> =
    mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }

  private val ScriptedToolRuntime.wireName: String
    get() = when (this) {
      ScriptedToolRuntime.SUBPROCESS -> "subprocess"
      ScriptedToolRuntime.IN_PROCESS -> "inProcess"
    }
}
