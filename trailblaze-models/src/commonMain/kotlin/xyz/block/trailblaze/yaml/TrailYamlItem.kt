package xyz.block.trailblaze.yaml

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Represents the top level items in a trail yaml.
 */
@Serializable
sealed interface TrailYamlItem {
  /**
   * "prompt"
   *
   * This is used to represent a prompt step in the trail.
   * It can contain a text prompt and an optional recording of tools used in that step.
   */
  @Serializable
  data class PromptsTrailItem(val promptSteps: List<PromptStep>) : TrailYamlItem

  /**
   * tools
   *
   * This is used to represent a list of static tools used in the trail.
   */
  @Serializable
  data class ToolTrailItem(
    val tools: List<@Contextual TrailblazeToolYamlWrapper>,
  ) : TrailYamlItem

  companion object {
    val KEYWORD_PROMPTS = "prompts"
    val KEYWORD_TOOLS = "tools"
    val KEYWORD_CONFIG = "config"
    val KEYWORD_TRAILHEAD = "trailhead"
  }

  /**
   *  config
   *
   *  This is used to represent additional test context and metadata.
   *  Use this to provide test data that will be added to the system prompt,
   *  as well as metadata like test ID, title, description, priority, and tags.
   */
  @Serializable
  data class ConfigTrailItem(val config: TrailConfig) : TrailYamlItem

  /**
   * trailhead
   *
   * The deterministic starting state for this trail — a single bootstrap step run before any
   * `prompts:` / `trail:` steps. A trailhead answers "where does this trail begin?" (sign in,
   * launch into a known account state, deep-link to a tab) and is the test's step 0.
   *
   * Optional. Trails without a trailhead parse and run unchanged. See the decided design in
   * `docs/devlog/2026-03-06-trail-yaml-v2-syntax.md` (Revision 2026-05-17).
   */
  @Serializable
  data class TrailheadTrailItem(val trailhead: TrailheadDefinition) : TrailYamlItem
}

/**
 * The body of a `trailhead:` root element — structurally identical to one trail step:
 * an optional natural-language [step] describing the starting state plus the [tools] that reach it.
 *
 * Authored three ways, all decoding to this one shape:
 * - **Bare string shorthand** — `trailhead: myapp_freshInstall` → one tool, no NL step.
 * - **`{ step, tools }`** — the canonical form: NL intent + the trailhead-tagged tool calls.
 * - **NL-only** — `trailhead: { step: "Sign in fresh" }` (e.g. a cross-platform `blaze.yaml`,
 *   where the per-platform `tools:` are materialized later in each `*.trail.yaml`).
 *
 * [tools] is nullable so "never declared" ([tools] `== null`, blaze via AI) is distinguishable
 * from "explicitly declared zero tools" ([tools] `== emptyList()`, a deterministic no-op — see
 * [ToolRecording]'s three-state doc). At least one of [step] / [tools] must be present, and an
 * explicit-empty [tools] still requires [step] — an empty trailhead with no NL intent at all is
 * meaningless.
 */
@Serializable
data class TrailheadDefinition(
  val step: String? = null,
  val tools: List<@Contextual TrailblazeToolYamlWrapper>? = null,
  /** Per-step retry-budget override for the blazed (NL-only / unrecorded) case; mirrors
   *  [PromptStep.maxRetries]. Carried through [toPromptStep] so the unified `trailhead:`'s
   *  `maxRetries:` isn't a silent no-op. Null = trail-wide default. */
  val maxRetries: Int? = null,
) {
  init {
    require(step != null || !tools.isNullOrEmpty()) {
      "A `trailhead:` must declare a `step:` and/or at least one tool — an empty trailhead is " +
        "meaningless. Use `trailhead: <toolId>` for a single bootstrap tool, or " +
        "`trailhead: { step: ..., tools: [...] }`."
    }
  }

  /**
   * Lower this trailhead to the leading [PromptStep] that runners execute as step 0. Runners invoke
   * this with `useRecordedSteps = true` so a non-null [tools] always replays deterministically (a
   * trailhead is a fixed starting move, not a refreshable recording), which is why [recordable] is
   * `true`. [tools] `== null` (never declared) carries no recording and blazes via AI; [tools]
   * `== emptyList()` (explicitly declared empty) carries a zero-tool [ToolRecording] and runs as a
   * deterministic no-op — neither falls through to AI the same way.
   */
  fun toPromptStep(): PromptStep = DirectionStep(
    step = step ?: DEFAULT_STEP,
    recordable = true,
    recording = tools?.let { ToolRecording(tools = it) },
    maxRetries = maxRetries,
    isTrailhead = true,
  )

  companion object {
    const val DEFAULT_STEP = "Reach the trailhead starting state"
  }
}
