package xyz.block.trailblaze.agent.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Per-verify-step tracker that records which required assertions have been satisfied by
 * successful verification tool calls. When every required target is satisfied at least once,
 * [allSatisfied] returns true and the runner can short-circuit the step via
 * [PromptStepStatus.markAsComplete] without waiting for an explicit `objectiveStatus(COMPLETED)`
 * from the LLM.
 *
 * Background: on multi-assertion verify steps the LLM can rotate through the required targets
 * indefinitely — each `assertVisibleBySelector` returns `Success`, but the LLM never calls
 * `objectiveStatus(COMPLETED)`, so the step burns its full LLM-call budget. The existing
 * `detectActionCycleHint` only catches cycle lengths 1–3, so a 4-target rotation slips
 * through. This ledger is a structural termination signal that doesn't depend on cycle-length
 * heuristics.
 */
class VerifyAssertionLedger(
  verifyText: String,
) {
  val requiredTargets: Set<NormalizedAssertionTarget> =
    VerifyTextParser.parse(verifyText)

  private val satisfiedTargets: MutableSet<NormalizedAssertionTarget> = mutableSetOf()

  // Behavioral fallback bookkeeping: tracks every UNIQUE successful verification target the
  // LLM has chosen during this step, regardless of whether the parser extracted it from the
  // verify text. Used by [isLikelyRotatingLoop] to catch the rotating-loop pattern when
  // verify text uses commas/lists instead of quoted bullets.
  private val seenTargets: MutableSet<NormalizedAssertionTarget> = mutableSetOf()
  private var repeatedAssertionCount: Int = 0

  val satisfiedSnapshot: Set<NormalizedAssertionTarget>
    get() = satisfiedTargets.toSet()

  val seenSnapshot: Set<NormalizedAssertionTarget>
    get() = seenTargets.toSet()

  val repeatCount: Int get() = repeatedAssertionCount

  /**
   * Records a successful verification tool call against the ledger. Two effects:
   * - Parser-based: when the target matches one of [requiredTargets], mark it satisfied.
   * - Behavioral: track the unique set of successful verification targets seen, and increment
   *   [repeatedAssertionCount] each time the LLM asserts on a target it has already
   *   successfully verified once.
   *
   * Calls that are not verification tools, that failed, or whose target cannot be extracted
   * are ignored entirely.
   *
   * @return true if a new target was marked satisfied by this call (parser-based hit). The
   *   behavioral signal is read via [isLikelyRotatingLoop].
   */
  fun recordSuccessfulAssertion(
    toolName: String,
    toolArgs: JsonObject,
    isSuccess: Boolean,
  ): Boolean {
    if (!isSuccess) return false
    val target = AssertionTargetExtractor.extract(toolName, toolArgs) ?: return false

    // Behavioral tracking — independent of the parser. The first time we see a target, add
    // it to seenTargets. Every subsequent successful assertion on the same target counts as
    // a "repeat" — the structural signature of the rotating-loop bug.
    if (!seenTargets.add(target)) {
      repeatedAssertionCount += 1
    }

    if (requiredTargets.isEmpty()) return false
    val match = requiredTargets.firstOrNull { it.matches(target) } ?: return false
    return satisfiedTargets.add(match)
  }

  /** True when every required target has been satisfied at least once (parser path). */
  fun allSatisfied(): Boolean =
    requiredTargets.isNotEmpty() && satisfiedTargets.size == requiredTargets.size

  /**
   * Behavioral fallback: true when the LLM has successfully asserted on ≥ [MIN_UNIQUE_TARGETS]
   * distinct targets AND repeated at least one of them. That repetition is the structural
   * fingerprint of the rotating-assertion loop — the LLM has nothing new to verify and is
   * spinning. Used when [requiredTargets] is empty (parser couldn't extract from the verify
   * text, e.g. "Verify that A, B, and C are present" without quotes).
   */
  fun isLikelyRotatingLoop(): Boolean =
    seenTargets.size >= MIN_UNIQUE_TARGETS && repeatedAssertionCount >= MIN_REPEATS

  /** Either termination signal is satisfied — convenience for the helper wiring point. */
  fun shouldAutoTerminate(): Boolean = allSatisfied() || isLikelyRotatingLoop()

  /** Outstanding targets the LLM still needs to assert — used for diagnostics. */
  fun missingTargets(): Set<NormalizedAssertionTarget> = requiredTargets - satisfiedTargets

  companion object {
    /**
     * Minimum number of UNIQUE successful verification targets that must be seen before
     * behavioral termination is allowed to fire. Set to 2 so single-target re-assertions
     * (e.g., "tap and re-verify") don't false-trigger.
     */
    const val MIN_UNIQUE_TARGETS: Int = 2

    /**
     * Minimum number of repeat-assertions (successful verification on a previously-seen
     * target) before behavioral termination fires. Set to 1 — once the LLM repeats any
     * target after having already established ≥ MIN_UNIQUE_TARGETS unique successes, it has
     * nothing new to verify.
     */
    const val MIN_REPEATS: Int = 1
  }
}

/**
 * Normalized representation of a target string asserted by a verification tool. Equality is
 * case-insensitive with surrounding whitespace stripped and internal whitespace collapsed.
 * This makes the ledger tolerant of trivial LLM rewordings (e.g. "Gift Cards" vs "gift cards")
 * without falsely accepting unrelated targets.
 */
data class NormalizedAssertionTarget(
  val raw: String,
) {
  val normalized: String = normalize(raw)

  fun matches(other: NormalizedAssertionTarget): Boolean = normalized == other.normalized

  companion object {
    fun normalize(input: String): String =
      input.trim().lowercase().replace(WHITESPACE_RUN, " ")

    private val WHITESPACE_RUN = Regex("\\s+")
  }
}

/**
 * Parses the verify-step text into the set of required assertion targets. The parser walks
 * each non-blank line and collects single-quoted substrings (`"…"`) as targets — the
 * convention in Trailblaze verify text. Lines without exactly one quoted target contribute
 * nothing, so multi-quoted lines fall through to the LLM's existing behavior.
 *
 * Single-bullet verify steps are intentionally exempted: returning an empty required set
 * disables auto-termination for those steps so the existing flow keeps running.
 */
object VerifyTextParser {
  private val QUOTED_TARGET_REGEX = Regex("\"([^\"]+)\"")
  private val BULLET_PREFIX_REGEX = Regex("""^\s*[-*•]""")
  private const val MIN_BULLETS_FOR_AUTO_TERMINATION = 2

  fun parse(verifyText: String): Set<NormalizedAssertionTarget> {
    val rawLines = verifyText.lines()
    // Gate on actual bullet markers (`-`, `*`, `•`), not non-blank line count: a single bullet
    // wrapping onto a continuation line would otherwise falsely enable auto-termination.
    val bulletCount = rawLines.count { BULLET_PREFIX_REGEX.containsMatchIn(it) }
    if (bulletCount < MIN_BULLETS_FOR_AUTO_TERMINATION) return emptySet()

    return rawLines
      .map { it.trim().trimStart('-', '*', '•', ' ', '\t') }
      .filter { it.isNotBlank() }
      .mapNotNull { line ->
        val quoted = QUOTED_TARGET_REGEX.findAll(line).map { it.groupValues[1] }.toList()
        if (quoted.size == 1) NormalizedAssertionTarget(quoted.single()) else null
      }
      .toSet()
  }
}

/**
 * Extracts the asserted target text from a Trailblaze verification tool's JSON args. Returns
 * null for tools whose args do not carry a single canonical target string — those calls don't
 * contribute to the ledger and the LLM keeps full control of the step.
 *
 * Supported tools (the ones that fired in the regression that motivated this fix):
 * - `assertVisibleBySelector` → prefers `selector.textRegex`, falls back to
 *   `nodeSelector.iosAccessibility.textRegex` / `nodeSelector.androidAccessibility.textRegex`.
 * - `assertVisibleWithText` → reads the legacy `text` field directly.
 *
 * Unrecognized tool names return null — extending coverage is a one-line addition to the
 * `when` branch once a regression surfaces.
 */
object AssertionTargetExtractor {
  fun extract(toolName: String, toolArgs: JsonObject): NormalizedAssertionTarget? {
    val text = when (toolName) {
      "assertVisibleBySelector" -> extractFromAssertVisibleBySelector(toolArgs)
      "assertVisibleWithText" -> toolArgs["text"]?.jsonPrimitive?.contentOrNull
      else -> null
    } ?: return null
    return NormalizedAssertionTarget(text)
  }

  private fun extractFromAssertVisibleBySelector(args: JsonObject): String? {
    args.jsonObjectAt("selector")?.let { selector ->
      selector["textRegex"]?.jsonPrimitive?.contentOrNull?.let { return it }
    }
    args.jsonObjectAt("nodeSelector")?.let { node ->
      node.jsonObjectAt("iosAccessibility")?.get("textRegex")?.jsonPrimitive?.contentOrNull?.let { return it }
      node.jsonObjectAt("androidAccessibility")?.get("textRegex")?.jsonPrimitive?.contentOrNull?.let { return it }
      node.jsonObjectAt("iosAccessibility")?.get("contentDescriptionRegex")?.jsonPrimitive?.contentOrNull?.let { return it }
      node.jsonObjectAt("androidAccessibility")?.get("contentDescriptionRegex")?.jsonPrimitive?.contentOrNull?.let { return it }
    }
    return null
  }

  private fun JsonObject.jsonObjectAt(key: String): JsonObject? =
    runCatching { this[key]?.jsonObject }.getOrNull()
}
