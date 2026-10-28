package xyz.block.trailblaze.config

import com.charleskorn.kaml.YamlInput
import com.charleskorn.kaml.YamlList
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonEncoder
import xyz.block.trailblaze.logs.client.temp.YamlJsonBridge

/**
 * Schema for per-tool `.yaml` files in `trails/config/trailmaps/<id>/tools/`.
 *
 * Two shapes, selected by which field is present:
 *
 * - **`class:`** — Kotlin-backed tool. [toolClass] points to a fully qualified class name; the
 *   description, parameters and flags are reflected from the class's `@TrailblazeToolClass` /
 *   `@LLMDescription` annotations.
 *
 *   ```yaml
 *   id: my_custom_tool
 *   class: com.example.tools.MyCustomTrailblazeTool
 *   ```
 *
 * - **metadata-only** (no body) — a `*.trailhead.yaml` that carries ONLY a [trailhead] block,
 *   attaching that navigation metadata to a tool registered elsewhere by the same [id] (e.g. a
 *   scripted `.ts` tool delivered via a target's `target.tools:`). The referenced tool supplies
 *   description / parameters / flags; this config contributes only the trailhead metadata.
 *
 *   A scripted (`.ts`) tool needs no sidecar: define it with `trailblaze.trailhead(spec, handler)`
 *   instead of `trailblaze.tool`. The build-time analyzer turns that into
 *   `InlineScriptToolConfig.trailhead`, so `toolbox trailheads` and the Trail Runner picker see it
 *   without a `*.trailhead.yaml` at all — prefer this for any TS-authored trailhead.
 *
 *   ```yaml
 *   # *.trailhead.yaml — marks the already-registered scripted tool `myapp_launchAppSignedIn`
 *   # as a bootstrap trailhead, drawn on the waypoint graph as landing at `to`.
 *   id: myapp_launchAppSignedIn
 *   trailhead:
 *     to: myapp/android/home_signed_in
 *   ```
 *
 * A tool composed from other tools is a TypeScript tool (see
 * `docs/scripted-tools-typed-authoring.md`). The `tools:` body that used to compose tools in YAML
 * is still parsed, only so [validate] can reject it with that pointer instead of silently
 * registering nothing.
 *
 * ## Trailhead tools
 *
 * | File suffix        | Trailmap subdir  | Block populated  | Class      | Available when                      |
 * | ------------------ | ---------------- | ---------------- | ---------- | ----------------------------------- |
 * | `*.tool.yaml`      | `tools/`         | (none)           | tool       | toolset rules (existing)            |
 * | `*.trailhead.yaml` | `trailheads/`    | [trailhead]      | trailhead  | always (bootstrap from any state)   |
 *
 * File-suffix conformance is enforced by [ToolYamlLoader] at load time; the data class itself
 * stays loader-agnostic. Subdirectories below each top-level trailmap dir are organizational
 * only — `trailheads/android/` etc. are walked recursively.
 *
 * A tool with a [trailhead] block, even an empty one, is a **trailhead** (bootstrap): it takes the
 * agent from any state to a known starting point with no precondition. Always available. Use trailheads for
 * "launch app and get to home", "force-quit and re-sign-in", and similar reset/genesis
 * moves that need to work regardless of where the agent currently is.
 *
 * ```yaml
 * # *.trailhead.yaml — bootstrap
 * id: myapp_launchAppSignedIn
 * trailhead:
 *   to: myapp/android/home_signed_in
 * class: com.example.myapp.LaunchAppSignedInTool
 * ```
 */
@Serializable
data class ToolYamlConfig(
  val id: String,
  @SerialName("class") val toolClass: String? = null,
  /** A retired YAML composition body. Parsed only so [validate] can reject it by name. */
  @SerialName("tools")
  @Serializable(with = ToolsListSerializer::class)
  val toolsList: List<JsonObject>? = null,
  /**
   * When present, even as `trailhead: {}`, marks this tool as a **trailhead** (bootstrap): a
   * navigation primitive that takes the agent from any state to a known starting point, with no
   * `from` precondition. The file-suffix loader rule separately requires the file containing a
   * `trailhead:` block to be named `*.trailhead.yaml`.
   */
  val trailhead: TrailheadMetadata? = null,
) {
  /** Which shape this config is in, derived from which fields are populated. */
  val mode: Mode
    get() = when {
      toolsList != null -> error(
        "Tool '$id' declares a 'tools:' body. YAML-composed tools are no longer supported — " +
          "write '$id' as a TypeScript tool (trailblaze.tool(...) in the trailmap's tools/ " +
          "directory, listed under target.tools: or target.platforms.<platform>.tools:). See " +
          "docs/scripted-tools-typed-authoring.md. To share it with other trailmaps, add it to " +
          "the trailmap's exports:.",
      )
      toolClass != null -> Mode.CLASS
      // No body, but a trailhead block is present: a metadata-only config that attaches its
      // navigation metadata to a tool registered elsewhere by the same `id` (e.g. a scripted
      // `.ts` tool delivered via `target.tools:`, which has no Kotlin class for a `class:` body).
      trailhead != null -> Mode.METADATA
      else ->
        error(
          "Tool '$id' declares neither 'class:' nor a 'trailhead:' metadata block — nothing to " +
            "register"
        )
    }

  /** Enforces the one-of rule and the trailhead block's shape. Call at load time after deserialization. */
  fun validate() {
    mode // triggers the one-of check and the 'tools:' rejection
    trailhead?.to?.let { dest ->
      require(dest.isNotBlank()) {
        "Tool '$id' 'trailhead.to' is blank. Omit it, or name the waypoint the trailhead lands on."
      }
      requireValidWaypointIdShape(id = id, fieldName = "trailhead.to", value = dest)
    }
  }

  /**
   * Enforces that a `from`/`to` value matches the waypoint-id shape: slash-separated
   * with at least two non-blank segments. Catches single-slash values (`"/"`),
   * leading/trailing slashes (`"/foo"`, `"foo/"`), and empty intermediate segments
   * (`"a//b"`) at config-load time rather than letting them propagate to the matcher
   * and produce confusing runtime mismatches.
   */
  private fun requireValidWaypointIdShape(id: String, fieldName: String, value: String) {
    val segments = value.split('/')
    require(segments.size >= 2 && segments.all { it.isNotBlank() }) {
      "Tool '$id' '$fieldName' value '$value' is malformed — waypoint ids are " +
        "slash-namespaced with at least two non-blank segments (e.g. 'clock/android/alarm_tab'). " +
        "See WaypointDefinition."
    }
  }

  /**
   * Shape, derived from which body field is populated.
   * - [CLASS]: `class:` body (Kotlin-backed tool).
   * - [METADATA]: no body — a trailhead block attached to a tool registered elsewhere by the same
   *   `id` (e.g. a scripted `.ts` tool). Never instantiated as a tool itself; consumed only for its
   *   navigation metadata via `ToolYamlLoader.discoverTrailheads`.
   */
  enum class Mode { CLASS, METADATA }
}

/**
 * Marks a [ToolYamlConfig] as a **trailhead tool**: a bootstrap primitive that takes the
 * agent from any state to a known starting point. There is no precondition — trailheads are
 * always available, regardless of whether a waypoint matches the current screen, because their
 * job is to handle the current state (whatever it is) — typically by force-quitting,
 * relaunching, and driving to the starting point.
 *
 * The block's presence is the marker; `trailhead: {}` is a complete trailhead. Nothing checks
 * where it lands: a trailhead that must fail on the wrong screen asserts that in its own body.
 *
 * Reusability: a trailhead is a single packaged primitive. Trail-level setup (the trail's
 * own `trailhead.setup:` section in the v2 trail YAML) composes one or more trailhead
 * tools alongside other tools (e.g., setting a feature flag before invoking a launch
 * trailhead). The trailhead tool is the atom; the trail's setup is the orchestration.
 */
@Serializable
data class TrailheadMetadata(
  /**
   * The waypoint this trailhead usually lands on, or null. Optional and never checked: it only
   * draws this trailhead's edge on the waypoint graph.
   */
  val to: String? = null,
)

/**
 * Serializes `List<JsonObject>` from kaml (YAML) or kotlinx-json. Routes YAML through
 * [YamlJsonBridge] since the stock `JsonObject` serializer only works with [JsonDecoder].
 */
internal object ToolsListSerializer : KSerializer<List<JsonObject>> {
  private val jsonListSerializer = ListSerializer(JsonElement.serializer())

  override val descriptor: SerialDescriptor = jsonListSerializer.descriptor

  override fun serialize(encoder: Encoder, value: List<JsonObject>) {
    if (encoder is JsonEncoder) {
      encoder.encodeJsonElement(JsonArray(value))
    } else {
      // Encode as a list of nested maps via YamlJsonBridge.
      error("ToolsListSerializer: non-JSON encoding is not yet wired (YAML encode of tools blocks)")
    }
  }

  override fun deserialize(decoder: Decoder): List<JsonObject> = when (decoder) {
    is YamlInput -> {
      val node = decoder.node
      require(node is YamlList) {
        "Expected a list for tools:, got ${node::class.simpleName}"
      }
      node.items.map { item ->
        YamlJsonBridge.yamlNodeToJsonElement(item) as? JsonObject
          ?: error("Each tool entry must be a map, got ${item::class.simpleName}")
      }
    }
    is JsonDecoder -> {
      val elem = decoder.decodeJsonElement()
      val array = elem as? JsonArray
        ?: error("Expected a JSON array for tools:, got ${elem::class.simpleName}")
      array.mapIndexed { index, item ->
        item as? JsonObject
          ?: error(
            "Each tool entry must be a JSON object, got ${item::class.simpleName} at index $index",
          )
      }
    }
    else -> error("ToolsListSerializer: unsupported decoder ${decoder::class.simpleName}")
  }
}
