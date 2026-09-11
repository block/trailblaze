package xyz.block.trailblaze.mcp.agent

/**
 * Decides, after each attempt at an objective, whether the Koog graph should try again.
 *
 * ## What a retry is here
 *
 * The strategy graph wraps its tool-calling loop in Koog's `subgraphWithRetry`. When an attempt ends
 * with the model reporting `objectiveStatus=FAILED`, Koog **rewinds the conversation** to the state
 * before the attempt, appends the feedback from [judgeAttempt] as a user message, and runs the loop
 * again. The device is NOT rewound — it sits wherever the failed attempt left it — so the feedback
 * says so and tells the model to look before it acts. That asymmetry is deliberate: a fresh
 * conversation on the current screen is what a human tester does after a dead end, and it is the
 * only rewind Trailblaze can offer honestly (there is no inverse of `tapOn`).
 *
 * A `FAILED` report is a reasoned give-up, not a crash, so a retry is only worth it when the model
 * might be wrong about the dead end. One retry is the default: the second attempt has both the
 * feedback and a clean context; a third would mostly repeat the second. Every attempt draws on the
 * same per-objective LLM-call budget, so the retry can never spend more than the objective was
 * already allowed to.
 *
 * Koog-free (plain lambdas and counters) so the accept / retry / give-up rule is unit-testable
 * without a graph; [KoogStrategyGraphAgent.buildStrategy] maps [Verdict] onto Koog's
 * `ConditionResult`. Not thread-safe, and doesn't need to be: Koog runs one node at a time.
 *
 * @property maxRetries How many times a rejected attempt may be re-run. `0` judges but never retries.
 * @property feedbackForRetry Consulted after every attempt. Returns the feedback to retry with, or
 *   `null` to accept the attempt as it stands. The host runner's implementation reads the
 *   `objectiveStatus` outcome its dispatcher captured.
 * @property onRetry Fired once per retry that will actually happen (never for an exhausted reject),
 *   with the 1-based number of the attempt being retried and the feedback it will receive.
 */
class KoogObjectiveRetryPolicy(
  val maxRetries: Int = DEFAULT_MAX_RETRIES,
  val feedbackForRetry: () -> String?,
  val onRetry: (attempt: Int, feedback: String) -> Unit = { _, _ -> },
) {
  init {
    require(maxRetries >= 0) { "maxRetries must be >= 0, was $maxRetries" }
  }

  /** The outcome of judging one attempt. */
  sealed interface Verdict {
    /** The attempt stands; the graph finishes with it. */
    data object Accept : Verdict

    /**
     * The attempt is rejected. [willRetry] is false when the retry budget is spent — the graph then
     * finishes with this attempt's result anyway, and [feedback] is recorded but never sent.
     */
    data class Reject(val feedback: String, val willRetry: Boolean) : Verdict
  }

  /** Attempts judged so far, including the one being judged. */
  var attempts: Int = 0
    private set

  /** Retries actually granted so far. Never exceeds [maxRetries]. */
  var retries: Int = 0
    private set

  /** Judges the attempt that just ended. Call exactly once per attempt, in order. */
  fun judgeAttempt(): Verdict {
    attempts++
    val feedback = feedbackForRetry() ?: return Verdict.Accept
    val willRetry = retries < maxRetries
    if (willRetry) {
      retries++
      onRetry(attempts, feedback)
    }
    return Verdict.Reject(feedback = feedback, willRetry = willRetry)
  }

  /**
   * Same rule and budget, with [listener] appended to [onRetry]. Lets the agent add its own listener
   * to a host-built policy. Only for a policy that has not judged anything yet: the copy starts
   * with fresh counters, so decorating mid-run would hand the objective a second retry budget.
   */
  fun withRetryListener(listener: (attempt: Int, feedback: String) -> Unit): KoogObjectiveRetryPolicy {
    check(attempts == 0) { "withRetryListener must be called before the first attempt is judged (already judged $attempts)" }
    return KoogObjectiveRetryPolicy(
      maxRetries = maxRetries,
      feedbackForRetry = feedbackForRetry,
      onRetry = { attempt, feedback ->
        onRetry(attempt, feedback)
        listener(attempt, feedback)
      },
    )
  }

  companion object {
    /** See the class doc for why one. */
    const val DEFAULT_MAX_RETRIES = 1

    /**
     * Kill-switch env var: when set to `1`/`true`, a `FAILED` report ends the objective on the first
     * attempt, as it did before retries existed. Read once when the agent is built, like the other
     * `TRAILBLAZE_KOOG_*` knobs.
     */
    const val DISABLE_FAILED_RETRY_ENV = "TRAILBLAZE_KOOG_DISABLE_FAILED_RETRY"

    /**
     * The user message a retried attempt opens with. It has to carry the one fact the rewound
     * conversation no longer contains — that the previous attempt happened and how it ended — and
     * the one fact the model would otherwise get wrong: the screen did not rewind with the chat.
     * That second fact cuts both ways, so the text says which way first: a step whose effect already
     * landed (a payment taken, an item added) must be reported COMPLETED, not redone.
     */
    fun feedbackForFailedObjective(explanation: String?): String = buildString {
      append("Your previous attempt at this objective ended with objectiveStatus=FAILED")
      val reason = explanation?.trim()?.takeIf { it.isNotEmpty() }
      if (reason != null) {
        append(": \"").append(reason).append('"')
      }
      append(". That attempt's conversation has been discarded, but the device has NOT been reset — ")
      append("the screen is exactly where that attempt left it. Look at the current screen before ")
      append("acting. First check whether the objective is already met: the device kept every effect ")
      append("of that attempt, so if the screen already shows the goal reached, report ")
      append("objectiveStatus=COMPLETED instead of doing it again. Otherwise try a different ")
      append("approach from the one that failed: scroll to reveal ")
      append("elements that may be off-screen, look for the same control under another label or ")
      append("along another path, or navigate back and approach the goal again. Report FAILED again ")
      append("only if the objective is genuinely impossible from here.")
    }
  }
}
