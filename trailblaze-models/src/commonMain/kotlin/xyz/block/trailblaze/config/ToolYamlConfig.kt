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
 * Schema for per-tool `.yaml` files in `trailblaze-config/tools/`.
 *
 * Supports two authoring modes, selected by which field is present:
 *
 * - **`class:` mode** (existing) — Kotlin-backed tool. [toolClass] points to a fully qualified
 *   class name; [description], [parameters], [isForLlm], [isRecordable], [requiresHost] are all
 *   reflected from the class's `@TrailblazeToolClass` / `@LLMDescription` annotations.
 *
 *   ```yaml
 *   id: my_custom_tool
 *   class: com.example.tools.MyCustomTrailblazeTool
 *   ```
 *
 * - **`tools:` mode** (YAML-defined) — static composition. [toolsList] is an inline list of
 *   Trailblaze tool calls. [description] and [parameters] MUST be supplied in the YAML since
 *   there is no Kotlin class to reflect. [isForLlm], [isRecordable], [requiresHost] mirror the
 *   `@TrailblazeToolClass` annotation fields and let YAML-defined tools opt out of LLM
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
 *   is_for_llm: true       # default — declare false to hide from the LLM
 *   is_recordable: true    # default — declare false to skip recording
 *   requires_host: false   # default — declare true to forbid on-device agents
 *   tools:
 *     - maestro:
 *         commands:
 *           - eraseText:
 *               charactersToErase: "{{params.charactersToErase}}"
 *   ```
 *
 * Exactly one of [toolClass] or [toolsList] must be present. Load-time validation enforces this
 * in [validate] — this class itself is a plain data holder to keep kaml deserialization boring.
 *
 * ## Shortcut tools and trailhead tools
 *
 * Three operational classes of tool live in this single data class, distinguished by which
 * (if any) metadata block is populated:
 *
 * | File suffix          | Block populated  | Class      | Available when                      |
 * | -------------------- | ---------------- | ---------- | ----------------------------------- |
 * | `*.tool.yaml`        | (neither)        | tool       | toolset rules (existing)            |
 * | `*.shortcut.yaml`    | [shortcut]       | shortcut   | current waypoint matches `from`     |
 * | `*.trailhead.yaml`   | [trailhead]      | trailhead  | always (bootstrap from any state)   |
 *
 * The blocks are mutually exclusive — a tool is at most one of those classes. File-suffix
 * conformance is enforced by [ToolYamlLoader] at load time; the data class itself
 * stays loader-agnostic.
 *
 * ### Shortcut
 *
 * A tool with a populated [shortcut] block is a **shortcut**: a navigation primitive that
 * promises to take the agent from one waypoint ([ShortcutMetadata.from]) to another
 * ([ShortcutMetadata.to]). The framework adds:
 *
 * 1. A **contextual descriptor filter** — shortcut tools only appear in the LLM's tool list
 *    when their `from` matches the current waypoint.
 * 2. A **pre/post-condition wrapper** at execution time — assert current waypoint matches
 *    `from` before invoke, assert it matches `to` after. Mismatch = tool failure with normal
 *    retry/recovery semantics.
 *
 * Both adaptations land in `trailblaze-common` follow-up changes; this class only declares
 * the schema and validates structural invariants.
 *
 * ### Trailhead
 *
 * A tool with a populated [trailhead] block is a **trailhead** (bootstrap): it takes the
 * agent from any state to a known waypoint with no `from` precondition. Always available.
 * Only the post-condition (lands at [TrailheadMetadata.to]) is enforced. Use trailheads for
 * "launch app and get to home", "force-quit and re-sign-in", and similar reset/genesis
 * moves that need to work regardless of where the agent currently is.
 *
 * ```yaml
 * # *.shortcut.yaml — transition shortcut
 * id: clock_create_alarm
 * description: Create an alarm at a given time.
 * parameters:
 *   - name: hour
 *     type: integer
 *     required: true
 *   - name: minute
 *     type: integer
 *     required: true
 * shortcut:
 *   from: clock/android/alarm_tab
 *   to:   clock/android/alarm_saved
 * tools:
 *   - tapElement: { selector: { textRegex: 'Add alarm' } }
 *   - inputText:  { text: '{{params.hour}}:{{params.minute}}' }
 *   - tapElement: { selector: { text: 'OK' } }
 * ```
 *
 * ```yaml
 * # *.trailhead.yaml — bootstrap (no `from`)
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
   * 1:1 with `@TrailblazeToolClass.isForLlm`. Only valid in `tools:` mode (in `class:` mode the
   * annotation is the source of truth and YAML must not declare it). `null` means
   * "framework default" — currently `true` for tools-mode, mirroring the annotation default.
   */
  @SerialName("is_for_llm") val isForLlm: Boolean? = null,
  /**
   * 1:1 with `@TrailblazeToolClass.isRecordable`. Same authoring rule as [isForLlm]: only set
   * in `tools:` mode. `null` defaults to `true`.
   */
  @SerialName("is_recordable") val isRecordable: Boolean? = null,
  /**
   * 1:1 with `@TrailblazeToolClass.requiresHost`. Same authoring rule as [isForLlm]. `null`
   * defaults to `false`. Setting `true` in `tools:` mode prevents the YAML-defined tool from
   * dispatching to on-device agents — host-only execution.
   */
  @SerialName("requires_host") val requiresHost: Boolean? = null,
  /**
   * 1:1 with `@TrailblazeToolClass.isVerification`. Same authoring rule as [isForLlm]. `null`
   * defaults to `false`. Setting `true` declares the YAML-defined tool as a read-only assertion
   * whose successful execution is itself the verify verdict — `blaze(hint=VERIFY)` will allow
   * such tools to run while short-circuiting any non-verification tool to a failed assertion.
   */
  @SerialName("is_verification") val isVerification: Boolean? = null,
  @SerialName("tools")
  @Serializable(with = ToolsListSerializer::class)
  val toolsList: List<JsonObject>? = null,
  /**
   * When populated, marks this tool as a **shortcut**: an authored navigation edge between
   * two waypoints with a runtime pre/post-condition contract. See class kdoc for the
   * runtime semantics. Mutually exclusive with [trailhead]. The file-suffix loader rule
   * separately requires the file containing a populated `shortcut:` block to be named
   * `*.shortcut.yaml`.
   */
  val shortcut: ShortcutMetadata? = null,
  /**
   * When populated, marks this tool as a **trailhead** (bootstrap): a navigation primitive
   * that takes the agent from any state to a known waypoint, with no `from` precondition.
   * The post-condition (the [TrailheadMetadata.to] waypoint must match after execution)
   * is what makes the trailhead trustworthy as a reusable starting move. Mutually exclusive
   * with [shortcut]. The file-suffix loader rule separately requires the file containing a
   * populated `trailhead:` block to be named `*.trailhead.yaml`.
   *
   * Trailheads exist as their own block (rather than a shortcut with optional `from`) so
   * the schema explicitly distinguishes the two operational classes — transition shortcuts
   * are gated on the current waypoint, trailheads are always available — and so the
   * trailhead's own metadata can grow distinct fields (memory preconditions, etc.) without
   * overloading shortcut semantics.
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
      else -> error("Tool '$id' declares neither 'class:' nor 'tools:' — exactly one is required")
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
        require(isForLlm == null) {
          "Tool '$id' uses 'class:' mode; 'is_for_llm' is reflected from the class's " +
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
    }
    // Shortcut / trailhead block invariants — independent of mode. Any tool can be a
    // shortcut OR a trailhead regardless of whether its body is `class:` or `tools:`
    // (a future SCRIPT mode would also be eligible). At most one block may be populated:
    // shortcut tools gate on the current waypoint, trailheads are always available, and a
    // tool can't be both because the operational semantics are mutually exclusive.
    //
    // File-suffix conformance (the file holding a `shortcut:` block must be named
    // `*.shortcut.yaml`, etc.) is enforced separately by ToolYamlLoader at load time.
    // The data class itself stays loader-agnostic.
    require(shortcut == null || trailhead == null) {
      "Tool '$id' declares both a 'shortcut:' block and a 'trailhead:' block — at most one " +
        "is allowed. A shortcut is a transition (gated on the current waypoint); a trailhead " +
        "is a bootstrap (always available). A tool can't be both."
    }
    shortcut?.let { sc ->
      require(sc.from.isNotBlank()) {
        "Tool '$id' shortcut block must declare a non-blank 'from:' waypoint id"
      }
      require(sc.to.isNotBlank()) {
        "Tool '$id' shortcut block must declare a non-blank 'to:' waypoint id"
      }
      requireValidWaypointIdShape(id = id, fieldName = "shortcut.from", value = sc.from)
      requireValidWaypointIdShape(id = id, fieldName = "shortcut.to", value = sc.to)
      sc.variant?.let { v ->
        require(v.isNotBlank()) {
          "Tool '$id' shortcut 'variant:' is present but blank — omit the field, or set it to a " +
            "non-blank disambiguator when multiple shortcuts share the same (from, to)."
        }
      }
    }
    trailhead?.let { th ->
      require(th.to.isNotBlank()) {
        "Tool '$id' trailhead block must declare a non-blank 'to:' waypoint id"
      }
      requireValidWaypointIdShape(id = id, fieldName = "trailhead.to", value = th.to)
    }
  }

  /**
   * Enforces that a `from`/`to` value matches the waypoint-id shape: slash-separated
   * with at least two non-blank segments. Catches single-slash values (`"/"`),
   * leading/trailing slashes (`"/foo"`, `"foo/"`), and empty intermediate segments
   * (`"a//b"`) at config-load time rather than letting them propagate to the matcher
   * and produce confusing runtime mismatches. Used for both shortcut and trailhead
   * waypoint references.
   */
  private fun requireValidWaypointIdShape(id: String, fieldName: String, value: String) {
    val segments = value.split('/')
    require(segments.size >= 2 && segments.all { it.isNotBlank() }) {
      "Tool '$id' '$fieldName' value '$value' is malformed — waypoint ids are " +
        "slash-namespaced with at least two non-blank segments (e.g. 'clock/android/alarm_tab'). " +
        "See WaypointDefinition."
    }
  }

  enum class Mode { CLASS, TOOLS }

  companion object {
    /** Reserved identifier grammar for parameter names. Dots are forbidden — they separate
     *  namespaces in interpolation tokens like `{{params.x}}` / `{{memory.x}}`. */
    val PARAM_NAME_REGEX = Regex("[a-zA-Z_][a-zA-Z0-9_]*")
  }
}

/**
 * Marks a [ToolYamlConfig] as a **shortcut tool**: a navigation edge between two waypoints
 * with a runtime contract.
 *
 * The framework enforces:
 *
 * - **Pre-condition**: at invocation time, the current waypoint must match [from]. The
 *   contextual descriptor filter only surfaces shortcut tools whose [from] matches the
 *   matched current waypoint, so the LLM cannot pick a shortcut from the wrong place;
 *   the execution-time check guards against drift between the LLM's choice and execution.
 * - **Post-condition**: after the body runs, the current waypoint must match [to]. Mismatch
 *   is a tool failure with the same retry/recovery semantics as any other tool failure.
 *
 * The `(from, to, variant?)` tuple is the user-facing addressing key — the CLI invokes
 * shortcuts by `<from> <to> [variant=...]`, not by tool id. Tool ids exist for the runtime
 * registry but aren't the load-bearing handle, which means waypoint refactors (e.g. splitting
 * `home` into `home/admin` and `home/employee`) re-point shortcuts' `from`/`to` references
 * without forcing tool-id renames.
 *
 * [variant] is optional. Populate it only when two shortcuts genuinely share the same
 * `(from, to)` pair AND their parameter signatures don't disambiguate. Most edges have one
 * shortcut and never need it.
 */
@Serializable
data class ShortcutMetadata(
  val from: String,
  val to: String,
  val variant: String? = null,
)

/**
 * Marks a [ToolYamlConfig] as a **trailhead tool**: a bootstrap primitive that takes the
 * agent from any state to a known waypoint. Distinct from a shortcut in that there is no
 * `from` precondition — trailheads are always available, regardless of whether a waypoint
 * matches the current screen, because their job is to handle the current state (whatever
 * it is) and reach a known starting point.
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
 * Trailheads exist as their own metadata block (separate from [ShortcutMetadata]) rather
 * than a shortcut with optional `from` for two reasons. First, the schema is honest about
 * the operational distinction: gated-on-waypoint vs always-available. Second, future
 * trailhead-only fields (memory predicates, expected side effects, etc.) have a clean home
 * to grow into without overloading shortcut semantics.
 *
 * Reusability: a trailhead is a single packaged primitive. Trail-level setup (the trail's
 * own `trailhead.setup:` section in the v2 trail YAML) composes one or more trailhead
 * tools alongside other tools (e.g., setting a feature flag before invoking a launch
 * trailhead). The trailhead tool is the atom; the trail's setup is the orchestration.
 */
@Serializable
data class TrailheadMetadata(
  val to: String,
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
