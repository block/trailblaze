package xyz.block.trailblaze.mcp.agent

/**
 * Detects when the Koog strategy-graph agent is **stuck repeating itself** and produces a nudge to
 * surface that fact back to the agent.
 *
 * ## Why this exists
 *
 * The agent's minimal-context machinery is a double-edged sword. [KoogStrategyGraphAgent]'s screen
 * prune keeps only the *latest* view hierarchy, and history compression folds older turns into a
 * TLDR — so the agent never re-sees the identical tool calls it already made. That's great for
 * token cost, but it means the agent is effectively **blind to its own repetition**: when it loops
 * (calls the same tool with the same arguments over and over without the screen advancing), nothing
 * in its context tells it so, and it happily burns its whole iteration budget. "Spins / gives up /
 * loops" was one of the named symptoms of the legacy agent; pruning made it *worse*, not better.
 *
 * This tracker restores that awareness. It records each tool dispatch's signature and, once the
 * same signature repeats [repeatThreshold] times in a row, returns a short nudge that the caller
 * appends to the tool result the agent sees. We deliberately surface a *signal*, not a forced
 * action — the LLM (Koog's loop) decides whether to try a different approach or report the
 * objective as failed. That keeps orchestration with Koog and only re-attaches the perception the
 * minimal-context design stripped.
 *
 * ## Scope (v1)
 *
 * Detects **consecutive identical** dispatches (same `tool.toString()`, which for the data-class
 * tools captures both the tool type and its arguments). A different action resets the streak.
 * Non-consecutive cycles (A, B, A, B …) are out of scope for now. Stateful and single-objective —
 * construct a fresh instance per agent run.
 *
 * @param repeatThreshold number of back-to-back identical dispatches before the nudge fires.
 *   Values `<= 0` disable detection entirely (the kill-switch). Default [DEFAULT_REPEAT_THRESHOLD].
 */
class KoogProgressTracker(
  private val repeatThreshold: Int = DEFAULT_REPEAT_THRESHOLD,
) {
  private var lastSignature: String? = null
  private var repeatCount = 0

  /**
   * Records one tool dispatch identified by [toolSignature] and returns a nudge string to append to
   * that tool's result when the agent appears stuck, or `null` when it's making progress (or
   * detection is disabled). Increments an internal streak for repeated signatures and resets it
   * when the signature changes.
   */
  fun observe(toolSignature: String): String? {
    if (repeatThreshold <= 0) return null

    if (toolSignature == lastSignature) {
      repeatCount++
    } else {
      lastSignature = toolSignature
      repeatCount = 1
    }

    if (repeatCount < repeatThreshold) return null
    return buildNudge(repeatCount)
  }

  companion object {
    /** Default back-to-back identical dispatches before the loop nudge fires. */
    const val DEFAULT_REPEAT_THRESHOLD = 3

    /**
     * Override env var for [DEFAULT_REPEAT_THRESHOLD]. A value `<= 0` disables loop detection.
     * Malformed values fall back to the default.
     */
    const val LOOP_DETECT_THRESHOLD_ENV = "TRAILBLAZE_KOOG_LOOP_DETECT_THRESHOLD"

    /** Resolves the configured repeat threshold from the environment, falling back to the default. */
    fun resolveThresholdFromEnv(): Int =
      System.getenv(LOOP_DETECT_THRESHOLD_ENV)?.toIntOrNull() ?: DEFAULT_REPEAT_THRESHOLD

    /** The nudge appended to a repeated tool's result. Public so tests can assert on it. */
    fun buildNudge(repeatCount: Int): String =
      "⚠️ Loop detected: you have called this exact tool with identical arguments $repeatCount " +
        "times in a row and the screen is not advancing. Repeating it will not help. Re-examine the " +
        "current screen and try a DIFFERENT element, selector, or action. If the objective genuinely " +
        "cannot be completed, call objectiveStatus with status=FAILED and explain why."
  }
}
