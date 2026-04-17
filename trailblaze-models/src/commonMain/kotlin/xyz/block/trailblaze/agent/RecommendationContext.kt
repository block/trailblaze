package xyz.block.trailblaze.agent

import kotlinx.serialization.Serializable

/**
 * Context passed from the outer agent to the inner agent for screen analysis.
 *
 * This provides the inner agent with enough context to make a useful recommendation
 * without needing to understand the full conversation history.
 *
 * @property objective The user's goal in natural language (e.g., "Log in with email test@example.com")
 * @property overallObjective The high-level objective when task decomposition is active.
 *   When non-null, [objective] is a focused subtask and this field provides the broader context
 *   so the analyzer can make decisions that serve the overall goal.
 * @property nextSubtaskHint Description of the next subtask after the current one, if known.
 *   Helps the analyzer take proactive actions when the current subtask is already satisfied.
 * @property progressSummary Optional summary of what has been accomplished so far
 * @property hint Optional guidance for the analysis (e.g., "Try scrolling down to find the button")
 * @property attemptNumber Which attempt this is (1 = first try, increments on retries)
 */
@Serializable
data class RecommendationContext(
  /** The user's goal in natural language (e.g., "Log in with email test@example.com") */
  val objective: String,
  /**
   * The high-level objective when task decomposition is active.
   *
   * When non-null, [objective] is a focused subtask and this field provides broader context
   * so the analyzer can make decisions that serve the overall goal. For example, if the
   * current subtask is "Locate John's contact" but the overall objective is "Send a message
   * to John saying Hello", the analyzer can proactively tap on John's contact instead of
   * just reporting the subtask is complete.
   */
  val overallObjective: String? = null,
  /**
   * Description of the next subtask after the current one, if known.
   *
   * When the current subtask appears already satisfied on screen, the analyzer should
   * take the first action needed for this next subtask instead of reporting status.
   * This prevents idle loops where the agent keeps saying "done" without acting.
   */
  val nextSubtaskHint: String? = null,
  /** Summary of progress made toward the objective so far */
  val progressSummary: String? = null,
  /** Guidance from the outer agent to help focus the analysis */
  val hint: String? = null,
  /** Which attempt this is (1 = first try, increments on retries after failures) */
  val attemptNumber: Int = 1,
  /** Text-only mode: skip screenshots, use text-only screen analysis (no vision tokens), and skip disk logging. */
  val fast: Boolean = false,
)
