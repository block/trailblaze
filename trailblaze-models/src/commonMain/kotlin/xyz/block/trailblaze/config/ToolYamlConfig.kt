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
 * Supports two authoring modes, selected by which field is present:
 *
 * - **`class:` mode** (existing) — Kotlin-backed tool. [toolClass] points to a fully qualified
 *   class name; [description], [parameters], [surfaceToLlm], [isRecordable], [requiresHost] are
 *   all reflected from the class's `@TrailblazeToolClass` /
 *   `@LLMDescription` annotations.
 *
 *   ```yaml
 *   id: my_custom_tool
 *   class: com.example.tools.MyCustomTrailblazeTool
 *   ```
 *
 * - **`tools:` mode** (YAML-defined) — static composition. [toolsList] is an inline list of
 *   Trailblaze tool calls. [description] and [parameters] MUST be supplied in the YAML since
 *   there is no Kotlin class to reflect. [surfaceToLlm], [isRecordable], [requiresHost] mirror
 *   the `@TrailblazeToolClass` annotation fields and let YAML-defined tools opt out of LLM
 *   visibility / recording / on-device execution. Parameter values are substituted into the
 *   tool tree via `{{params.x}}` tokens at expansion time.
 *
 *   ```yaml
 *   id: eraseText
 *   description: "Erases characters from the focused text field."
 *   parameters:
 *     - name: charactersToErase
 *       type: integer
 *       required: false
 *   surface_to_llm: true   # default — declare false to hide from the LLM
 *   is_recordable: true    # default — declare false to skip recording
 *   requires_host: false   # default — declare true to forbid on-device agents
 *   tools:
 *     - mobile_maestro:
 *         commands:
 *           - eraseText:
 *               charactersToErase: "{{params.charactersToErase}}"
 *   ```
 *
 * - **metadata-only** (no body) — a `*.trailhead.yaml` that carries ONLY a [trailhead] block,
 *   attaching that navigation metadata to a tool registered elsewhere by the same [id]. Use this
 *   when the underlying tool isn't Kotlin-backed (so there's no class for a `class:` body) and you
 *   don't want to re-define it — e.g. a scripted (`.ts`) tool delivered via a target's
 *   `target.tools:`. The referenced tool supplies description / parameters / flags; this config
 *   contributes only the trailhead metadata.
 *
 *   A scripted (`.ts`) tool has a second, sidecar-free option: declaring `trailhead: { to, dynamic }`
 *   directly in its `TrailblazeTypedToolSpec` (mirrors this [TrailheadMetadata] shape
 *   field-for-field). The build-time analyzer captures it into
 *   `InlineScriptToolConfig.trailhead`, so `toolbox trailheads` and the Trail Runner picker see it
 *   without a `*.trailhead.yaml` at all — prefer this for any TS-authored trailhead.
 *
 *   ```yaml
 *   # *.trailhead.yaml — marks the already-registered scripted tool `myapp_launchAppSignedIn`
 *   # as a bootstrap trailhead landing at the given waypoint.
 *   id: myapp_launchAppSignedIn
 *   trailhead:
 *     to: myapp/android/home_signed_in
 *   ```
 *
 * Exactly one of [toolClass] or [toolsList] must be present, UNLESS the config is metadata-only
 * (no body, but a [trailhead] block). Load-time validation enforces this in [validate]
 * — this class itself is a plain data holder to keep kaml deserialization boring.
 *
 * ## Trailhead tools
 *
 * Two operational classes of tool live in this single data class, distinguished by whether
 * the [trailhead] block is populated:
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
 * A tool with a populated [trailhead] block is a **trailhead** (bootstrap): it takes the
 * agent from any state to a known waypoint with no precondition. Always available.
 * Only the post-condition (lands at [TrailheadMetadata.to]) is enforced. Use trailheads for
 * "launch app and get to home", "force-quit and re-sign-in", and similar reset/genesis
 * moves that need to work regardless of where the agent currently is.
 *
 * ```yaml
 * # *.trailhead.yaml — bootstrap
 * id: myapp_launchAppSignedIn
 * description: Launch MyApp and sign in to the home screen.
 * parameters:
 *   - name: email
 *     default: '{{memory.email}}'
 *   - name: password
 *     default: '{{memory.password}}'
 * trailhead:
 *   to: myapp/android/home_signed_in
 * class: com.example.myapp.LaunchAppSignedInTool
 * ```
 */
@Serializable
data class ToolYamlConfig(
  val id: String,
  @SerialName("class") val toolClass: String? = null,
  val description: String? = null,
  val parameters: List<TrailblazeToolParameterConfig> = emptyList(),
  /**
   * 1:1 with `@TrailblazeToolClass.surfaceToLlm`. Only valid in `tools:` mode (in `class:` mode
   * the annotation is the source of truth and YAML must not declare it). `null` means
   * "framework default" — currently `true` for tools-mode, mirroring the annotation default.
   */
  @SerialName("surface_to_llm") val surfaceToLlm: Boolean? = null,
  /**
   * 1:1 with `@TrailblazeToolClass.isRecordable`. Same authoring rule as [surfaceToLlm]: only
   * set in `tools:` mode. `null` defaults to `true`.
   */
  @SerialName("is_recordable") val isRecordable: Boolean? = null,
  /**
   * 1:1 with `@TrailblazeToolClass.requiresHost`. Same authoring rule as [surfaceToLlm]. `null`
   * defaults to `false`. Setting `true` in `tools:` mode prevents the YAML-defined tool from
   * dispatching to on-device agents — host-only execution.
   */
  @SerialName("requires_host") val requiresHost: Boolean? = null,
  /**
   * 1:1 with `@TrailblazeToolClass.isVerification`. Same authoring rule as [surfaceToLlm].
   * `null` defaults to `false`. Setting `true` declares the YAML-defined tool as a read-only
   * assertion whose successful execution is itself the verify verdict — `blaze(hint=VERIFY)`
   * will allow such tools to run while short-circuiting any non-verification tool to a failed
   * assertion.
   */
  @SerialName("is_verification") val isVerification: Boolean? = null,
  @SerialName("tools")
  @Serializable(with = ToolsListSerializer::class)
  val toolsList: List<JsonObject>? = null,
  /**
   * When populated, marks this tool as a **trailhead** (bootstrap): a navigation primitive
   * that takes the agent from any state to a known waypoint, with no `from` precondition.
   * The post-condition (the [TrailheadMetadata.to] waypoint must match after execution)
   * is what makes the trailhead trustworthy as a reusable starting move. The file-suffix
   * loader rule separately requires the file containing a populated `trailhead:` block to be
   * named `*.trailhead.yaml`.
   */
  val trailhead: TrailheadMetadata? = null,
) {
  /** Which authoring mode this config is in, derived from which fields are populated. */
  val mode: Mode
    get() = when {
      toolClass != null && toolsList != null ->
        error("Tool '$id' declares both 'class:' and 'tools:' — exactly one is allowed")
      toolClass != null -> Mode.CLASS
      toolsList != null -> Mode.TOOLS
      // No body, but a trailhead block is present: a metadata-only config that attaches its
      // navigation metadata to a tool registered elsewhere by the same `id` (e.g. a scripted
      // `.ts` tool delivered via `target.tools:`, which has no Kotlin class for a `class:` body).
      trailhead != null -> Mode.METADATA
      else ->
        error(
          "Tool '$id' declares neither 'class:' nor 'tools:' nor a 'trailhead:' metadata " +
            "block — nothing to register"
        )
    }

  /**
   * Enforces the one-of rule and mode-specific required-field rules. Call at load time after
   * deserialization.
   */
  fun validate() {
    val m = mode // triggers one-of check
    when (m) {
      Mode.CLASS -> {
        require(description == null) {
          "Tool '$id' uses 'class:' mode; 'description' is reflected from the class and must not " +
            "be declared in YAML"
        }
        require(parameters.isEmpty()) {
          "Tool '$id' uses 'class:' mode; 'parameters' are reflected from the class and must not " +
            "be declared in YAML"
        }
        require(surfaceToLlm == null) {
          "Tool '$id' uses 'class:' mode; 'surface_to_llm' is reflected from the class's " +
            "@TrailblazeToolClass annotation and must not be declared in YAML"
        }
        require(isRecordable == null) {
          "Tool '$id' uses 'class:' mode; 'is_recordable' is reflected from the class's " +
            "@TrailblazeToolClass annotation and must not be declared in YAML"
        }
        require(requiresHost == null) {
          "Tool '$id' uses 'class:' mode; 'requires_host' is reflected from the class's " +
            "@TrailblazeToolClass annotation and must not be declared in YAML"
        }
        require(isVerification == null) {
          "Tool '$id' uses 'class:' mode; 'is_verification' is reflected from the class's " +
            "@TrailblazeToolClass annotation and must not be declared in YAML"
        }
      }
      Mode.TOOLS -> {
        require(!description.isNullOrBlank()) {
          "Tool '$id' uses 'tools:' mode and must declare a 'description:' (nothing to reflect)"
        }
        val paramNames = parameters.map { it.name }
        require(paramNames.size == paramNames.toSet().size) {
          "Tool '$id' declares duplicate parameter names: $paramNames"
        }
        paramNames.forEach { name ->
          require(name.matches(PARAM_NAME_REGEX)) {
            "Tool '$id' parameter name '$name' is invalid — must match ${PARAM_NAME_REGEX.pattern} " +
              "(no dots; those are reserved for namespace separators in {{params.x}} / {{memory.x}})"
          }
        }
      }
      Mode.METADATA -> {
        // Metadata-only: no `class:`/`tools:` body to reflect or compose. The trailhead block
        // (validated below) attaches to a tool registered elsewhere by the same `id`, which
        // supplies the description / parameters / flags. Body-bearing fields have nothing to bind
        // to here, so reject them rather than silently ignore.
        require(description == null) {
          "Tool '$id' is a metadata-only trailhead (no 'class:'/'tools:' body); " +
            "'description' is reflected from the referenced tool and must not be declared in YAML"
        }
        require(parameters.isEmpty()) {
          "Tool '$id' is a metadata-only trailhead; 'parameters' are reflected from the " +
            "referenced tool and must not be declared in YAML"
        }
        require(surfaceToLlm == null) {
          "Tool '$id' is a metadata-only trailhead; 'surface_to_llm' is reflected from " +
            "the referenced tool and must not be declared in YAML"
        }
        require(isRecordable == null) {
          "Tool '$id' is a metadata-only trailhead; 'is_recordable' is reflected from " +
            "the referenced tool and must not be declared in YAML"
        }
        require(requiresHost == null) {
          "Tool '$id' is a metadata-only trailhead; 'requires_host' is reflected from " +
            "the referenced tool and must not be declared in YAML"
        }
        require(isVerification == null) {
          "Tool '$id' is a metadata-only trailhead; 'is_verification' is reflected from " +
            "the referenced tool and must not be declared in YAML"
        }
      }
    }
    trailhead?.let { th ->
      // A trailhead is a deterministic bootstrap. Most land on a single known waypoint (`to:`),
      // but some — e.g. a deep-link launcher whose destination varies by input — have no fixed
      // destination; those set `dynamic: true` instead of `to:`. The two are mutually exclusive:
      // `dynamic: true` says "destination varies, don't anchor me to a waypoint."
      val dest = th.to
      if (th.dynamic) {
        require(dest == null) {
          "Tool '$id' trailhead block sets 'dynamic: true' (destination varies by input), so it " +
            "must not also declare a 'to:' waypoint. Drop 'to:', or drop 'dynamic:' and name the " +
            "single destination."
        }
      } else {
        require(!dest.isNullOrBlank()) {
          "Tool '$id' trailhead block must declare a non-blank 'to:' waypoint id, or set " +
            "'dynamic: true' for a trailhead whose destination varies by input."
        }
        requireValidWaypointIdShape(id = id, fieldName = "trailhead.to", value = dest)
      }
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
   * Authoring mode, derived from which body field is populated.
   * - [CLASS]: `class:` body (Kotlin-backed tool).
   * - [TOOLS]: `tools:` body (YAML-defined composition).
   * - [METADATA]: no body — a trailhead block attached to a tool registered elsewhere by the same
   *   `id` (e.g. a scripted `.ts` tool). Never instantiated as a tool itself; consumed only for its
   *   navigation metadata via `ToolYamlLoader.discoverTrailheads`.
   */
  enum class Mode { CLASS, TOOLS, METADATA }

  companion object {
    /** Reserved identifier grammar for parameter names. Dots are forbidden — they separate
     *  namespaces in interpolation tokens like `{{params.x}}` / `{{memory.x}}`. */
    val PARAM_NAME_REGEX = Regex("[a-zA-Z_][a-zA-Z0-9_]*")
  }
}

/**
 * Marks a [ToolYamlConfig] as a **trailhead tool**: a bootstrap primitive that takes the
 * agent from any state to a known waypoint. There is no precondition — trailheads are always
 * available, regardless of whether a waypoint matches the current screen, because their job is
 * to handle the current state (whatever it is) and reach a known starting point.
 *
 * The framework enforces:
 *
 * - **Post-condition**: after the body runs, the current waypoint must match [to]. Mismatch
 *   is a tool failure with the same retry/recovery semantics as any other tool failure.
 *   This is what makes the trailhead trustworthy as a reusable starting move — the agent
 *   (or test author) can rely on the result without separately verifying state.
 * - **No pre-condition**: the body is invoked regardless of current state. The body is
 *   expected to handle "any state → [to]" — typically by force-quitting, relaunching, and
 *   driving to the destination.
 *
 * Reusability: a trailhead is a single packaged primitive. Trail-level setup (the trail's
 * own `trailhead.setup:` section in the v2 trail YAML) composes one or more trailhead
 * tools alongside other tools (e.g., setting a feature flag before invoking a launch
 * trailhead). The trailhead tool is the atom; the trail's setup is the orchestration.
 */
@Serializable
data class TrailheadMetadata(
  /**
   * The single waypoint this trailhead lands on. Required unless [dynamic] is true. Null only for
   * [dynamic] trailheads, whose destination varies by input (e.g. a deep-link launcher).
   */
  val to: String? = null,
  /**
   * Marks a trailhead whose destination is not a single fixed waypoint — it varies by input, so no
   * `to:` is specified. Mutually exclusive with [to] (enforced in [ToolYamlConfig.validate]). Such a
   * trailhead is a real bootstrap (the `trailhead:` block's presence is what makes it a trailhead),
   * it just isn't anchored to a waypoint in the navigation graph.
   */
  val dynamic: Boolean = false,
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
