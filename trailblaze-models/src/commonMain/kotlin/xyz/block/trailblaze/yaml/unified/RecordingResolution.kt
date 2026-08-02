package xyz.block.trailblaze.yaml.unified

import kotlinx.serialization.Serializable

/**
 * How ONE step's `recording:` map resolved for a specific device — the decision
 * [UnifiedTrailAdapter.lowerToTrailItems] makes per step, captured instead of discarded.
 *
 * Four outcomes are distinguishable here that are indistinguishable once the step has been lowered,
 * because lowering collapses them into "has a `ToolRecording`" / "doesn't":
 *
 *  1. **exact match** — [resolvedClassifier] equals the device's own compound identity. The tools
 *     were captured on this device.
 *  2. **family alias** — [resolvedClassifier] is a broader ancestor (`android` covering
 *     `android-phone`). Replays fine, but the tools were captured on a *different* device, which is
 *     weaker evidence than an exact key and worth telling apart from it.
 *  3. **deterministic no-op** — matched, with [toolCount] `0` (an explicit `android: []`). Replays
 *     zero tools and succeeds, deterministically and without AI. Reported today exactly like a
 *     23-tool replay.
 *  4. **no match** — [resolvedClassifier] is `null`. The step silently runs in LLM mode.
 *
 * See [ToolRecording][xyz.block.trailblaze.yaml.ToolRecording] for why 3 and 4 must never collapse
 * into each other.
 */
@Serializable
data class RecordingResolution(
  /** 0-based index into `trail:`, or `null` for the trailhead (the deterministic step 0). */
  val stepIndex: Int?,
  /** `true` for a `verify:` step. An unresolved verify is an LLM-backed assertion, not a coverage
   *  hole in the action path — the two are worth counting separately. */
  val isVerify: Boolean,
  /** Classifier keys the step actually declares, in authored order. Empty means the step was never
   *  recorded for any device, which is authored intent rather than a resolution failure. */
  val declaredClassifiers: List<String>,
  /** The chain entry that won, or `null` when nothing in the device's chain matched. */
  val resolvedClassifier: String?,
  /**
   * Tool names the winning entry replays, in order. Empty for a matched-empty no-op; `null` iff
   * nothing matched. Keeping null and empty distinct is the whole point — see the class doc.
   */
  val toolNames: List<String>?,
) {
  /** `0` for a matched-empty no-op, `null` iff nothing matched. */
  val toolCount: Int? get() = toolNames?.size

  /**
   * `true` when this step replays a conditional wrapper, so its inner tools re-evaluate their
   * condition on every run instead of firing blind.
   *
   * A conditional NL step does NOT automatically produce one: the recorder captures the concrete
   * path it happened to take, unguarded, and `block_runIf` is `surfaceToLlm: false`, so the agent
   * can't choose it either. A step whose text describes a condition but whose recording has no
   * guard has silently become unconditional.
   */
  val isConditionallyGuarded: Boolean
    get() = toolNames?.any { it in CONDITIONAL_TOOL_NAMES } == true

  companion object {
    /** Recorded wrappers that re-evaluate a condition at replay time. */
    val CONDITIONAL_TOOL_NAMES = setOf("block_runIf", "runIf")
  }
}

/**
 * Every step's [RecordingResolution] for one device, plus the chain they were resolved against.
 *
 * Exists because no artifact currently distinguishes the four outcomes above: a session that
 * replayed 23 tools, one that replayed zero deterministically, and one that quietly handed a step
 * to the LLM all report the same `execution_mode`. `recording_skip_reason = NOT_FOUND` does not
 * close that gap either — it is set exactly when the session made a non-self-heal LLM call, so it
 * describes the consequence rather than the lookup.
 */
@Serializable
data class TrailRecordingResolution(
  /** The device's compound identity (`android-phone`), or `null` for an empty classifier list. */
  val deviceClassifier: String?,
  /** Most-specific-first chain the resolution walked, e.g. `[android-phone, android]`. */
  val resolutionChain: List<String>,
  /** Trailhead first (when present), then `trail:` steps in order. */
  val steps: List<RecordingResolution>,
) {
  /** Steps whose device chain matched nothing, so they run via the LLM. Excludes never-recorded
   *  steps, which are authored intent rather than a resolution failure. */
  val unresolvedDeclared: List<RecordingResolution>
    get() = steps.filter { it.resolvedClassifier == null && it.declaredClassifiers.isNotEmpty() }

  /** Matched steps carrying zero tools — deterministic no-ops indistinguishable from real replays
   *  in every report today. */
  val deterministicNoOps: List<RecordingResolution>
    get() = steps.filter { it.toolCount == 0 }

  /** Matched steps whose tools came from a broader ancestor rather than this exact device. */
  val familyAliased: List<RecordingResolution>
    get() = steps.filter { it.resolvedClassifier != null && it.resolvedClassifier != deviceClassifier }

  /** Matched steps replaying a conditional wrapper, so they re-evaluate rather than fire blind. */
  val conditionallyGuarded: List<RecordingResolution>
    get() = steps.filter { it.isConditionallyGuarded }

  /**
   * Steps this device replays UNguarded that a sibling device guards — i.e. an author established
   * the step is conditional, and this device's recording lost the guard.
   *
   * This is the one conditional-coverage signal that needs no prose. Reading the step's natural
   * language can't do it: a measured pass over the corpus found `if`/`when` used descriptively
   * ("Tap Charge (or Review sale if the Skip review sale setting is disabled)") about as often as
   * imperatively, and missed a quarter of the steps that are provably conditional. A sibling
   * device's own recording is evidence instead of a guess.
   *
   * @param siblings the same trail's resolution for other devices.
   */
  fun lostGuardsVersus(siblings: List<TrailRecordingResolution>): List<RecordingResolution> {
    val guardedElsewhere = siblings
      .filter { it.deviceClassifier != deviceClassifier }
      .flatMap { sibling -> sibling.conditionallyGuarded.map { it.stepIndex } }
      .toSet()
    return steps.filter {
      it.resolvedClassifier != null && !it.isConditionallyGuarded && it.stepIndex in guardedElsewhere
    }
  }

  /**
   * One-line census for a log or report column. Named counts rather than a bare total, because the
   * total is the number that hid all four shapes in the first place.
   */
  fun summarize(): String = buildString {
    append("${steps.size} step(s)")
    val exact = steps.count { it.resolvedClassifier != null && it.resolvedClassifier == deviceClassifier }
    if (exact > 0) append(", $exact exact")
    familyAliased.groupingBy { it.resolvedClassifier }.eachCount().forEach { (key, n) ->
      append(", $n via family alias '$key'")
    }
    if (deterministicNoOps.isNotEmpty()) append(", ${deterministicNoOps.size} zero-tool no-op")
    if (conditionallyGuarded.isNotEmpty()) append(", ${conditionallyGuarded.size} conditional")
    if (unresolvedDeclared.isNotEmpty()) append(", ${unresolvedDeclared.size} unmatched -> LLM")
    val never = steps.count { it.declaredClassifiers.isEmpty() }
    if (never > 0) append(", $never never recorded")
  }
}
