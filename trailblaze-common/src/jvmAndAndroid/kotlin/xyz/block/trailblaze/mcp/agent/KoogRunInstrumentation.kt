package xyz.block.trailblaze.mcp.agent

/**
 * Records what one Koog objective actually did, so a run that throws can say **where** it threw.
 *
 * ## Why this exists
 *
 * When the strategy graph throws, the only thing [KoogTestAgentRunner] holds is the exception at the
 * outside of `agent.run()`. Every failure therefore reported the same thing — "ended without
 * reporting a status", `callCount = 0` — so a blown LLM budget, a wedged transport and a node bug
 * were indistinguishable in the session log an A/B comparison reads. Koog's `EventHandler` feature fires
 * on the way out of the failing node, before the exception has finished unwinding, which is the one
 * place that knows which node it was.
 *
 * ## The distinction that makes this useful
 *
 * A **node failure** always precedes the throw that propagates out of `run()`, so it is the
 * attribution. A **tool failure** does not: Koog turns it into a `ToolResultKind.Failure` result and
 * feeds it back to the model, which usually recovers. Recording both as "the failure" would let a
 * recovered tap from turn 2 be blamed for a budget exhaustion at turn 20. So tool failures are kept
 * separately and reported as context, explicitly labelled as recovered.
 *
 * Recording is Koog-free (plain strings and ints) so the reporting rules are unit-testable without
 * standing up a graph; [KoogStrategyGraphAgent.createInProcess] installs the Koog features that feed
 * it, and does so only when one of these is passed.
 *
 * Not thread-safe, and doesn't need to be: Koog executes one node at a time within a single `run()`,
 * and [KoogTestAgentRunner] creates a fresh instance per objective.
 */
class KoogRunInstrumentation {

  /** A node that threw. Recorded before the exception left the node, so it is the real attribution. */
  data class NodeFailure(
    val nodeName: String,
    val errorType: String,
    val errorMessage: String?,
  )

  /** A tool call Koog turned into a failure result and fed back to the model — context, not a cause. */
  data class RecoveredToolFailure(
    val toolName: String,
    val summary: String,
  )

  /** LLM requests the graph started, counted at the Koog pipeline rather than at our client. */
  var llmCallsStarted: Int = 0
    private set

  /** LLM requests that returned. Lags [llmCallsStarted] by one while a request is in flight. */
  var llmCallsCompleted: Int = 0
    private set

  /**
   * Largest estimated prompt size seen across this objective's requests, or null if no estimate was
   * available. An **estimate** ([ai.koog.prompt.tokenizer.SimpleRegexBasedTokenizer], measured before
   * the call) — the authoritative counts stay in each request's `TrailblazeLlmRequestLog`, which
   * reads the provider's own numbers. This one answers a question those can't: how big the context
   * had grown at the moment the compression gate ran, which is what a token-based gate would need.
   */
  var peakEstimatedPromptTokens: Int? = null
    private set

  /** The FIRST node failure of the objective — the innermost, hence the root cause. */
  var firstNodeFailure: NodeFailure? = null
    private set

  /** How many nodes failed. More than one means the failure was re-thrown through outer nodes. */
  var nodeFailureCount: Int = 0
    private set

  /** The most recent recovered tool failure, kept because it is the one nearest whatever went wrong. */
  var lastRecoveredToolFailure: RecoveredToolFailure? = null
    private set

  /** How many tool calls failed and were fed back to the model over the whole objective. */
  var recoveredToolFailureCount: Int = 0
    private set

  /**
   * How many times the objective was re-run after the model reported `FAILED` — see
   * [KoogObjectiveRetryPolicy]. Counted here so the session log can say a passing step passed on its
   * second attempt; the A/B readout needs that to tell a retry-rescued pass from a first-try pass.
   */
  var objectiveRetries: Int = 0
    private set

  fun recordObjectiveRetry() {
    objectiveRetries++
  }

  fun recordLlmCallStarting(estimatedPromptTokens: Int?) {
    llmCallsStarted++
    if (estimatedPromptTokens != null) {
      peakEstimatedPromptTokens = maxOf(peakEstimatedPromptTokens ?: 0, estimatedPromptTokens)
    }
  }

  fun recordLlmCallCompleted() {
    llmCallsCompleted++
  }

  /** First recorded failure wins: outer nodes re-throw the same error, and the innermost one caused it. */
  fun recordNodeFailure(nodeName: String, errorType: String, errorMessage: String?) {
    nodeFailureCount++
    if (firstNodeFailure == null) {
      firstNodeFailure = NodeFailure(nodeName, errorType, truncate(errorMessage))
    }
  }

  /** Last recorded failure wins here — unlike a node failure, an earlier one was probably recovered from. */
  fun recordRecoveredToolFailure(toolName: String, summary: String) {
    recoveredToolFailureCount++
    lastRecoveredToolFailure = RecoveredToolFailure(toolName, truncate(summary).orEmpty())
  }

  /**
   * The `llmExplanation` for an objective that threw instead of reporting a status.
   *
   * Leads with the attribution (which node, which error) because that is what a reader of a failed
   * session needs first. [thrown] is the exception that reached the caller: it is named separately
   * only when it differs from the recorded one, since the common case is the same exception seen at
   * two depths and repeating it reads as two problems.
   */
  fun describeThrow(thrown: Throwable?): String = buildString {
    val nodeFailure = firstNodeFailure
    if (nodeFailure != null) {
      append("Koog strategy graph threw at node '${nodeFailure.nodeName}' ")
      append("after $llmCallsCompleted LLM call(s): ")
      append(formatError(nodeFailure.errorType, nodeFailure.errorMessage))
      if (nodeFailureCount > 1) append(" (first of $nodeFailureCount node failures)")
      val thrownType = thrown?.let { it::class.simpleName }
      if (thrownType != null && thrownType != nodeFailure.errorType) {
        append(", propagated as ").append(formatError(thrownType, truncate(thrown.message)))
      }
    } else if (thrown != null) {
      append("Koog strategy graph threw before any node reported a failure, ")
      append("after $llmCallsCompleted LLM call(s): ")
      append(formatError(thrown::class.simpleName ?: "Throwable", truncate(thrown.message)))
    } else {
      // Not a throw at all: the run returned without an objectiveStatus. Saying "threw" here would
      // send a reader hunting for an exception that never existed.
      append("Koog strategy graph ended without reporting a status ")
      append("after $llmCallsCompleted LLM call(s); no node failure and no exception were recorded")
    }
    lastRecoveredToolFailure?.let { toolFailure ->
      append(". Earlier in the step $recoveredToolFailureCount tool call(s) failed and were fed ")
      append("back to the model; the last was '${toolFailure.toolName}': ${toolFailure.summary}")
    }
    if (objectiveRetries > 0) {
      // The LLM-call count above spans every attempt, so say there was more than one.
      append(". The objective had been retried $objectiveRetries time(s) after the model reported FAILED")
    }
  }

  /** One-line readout of the objective, logged whether it passed or failed. */
  fun describeRun(): String = buildString {
    append("$llmCallsCompleted of $llmCallsStarted LLM call(s) completed")
    append(", peak estimated prompt size ")
    append(peakEstimatedPromptTokens?.let { "~$it tokens" } ?: "unknown")
    append(", $nodeFailureCount node failure(s)")
    append(", $recoveredToolFailureCount recovered tool failure(s)")
    append(", $objectiveRetries retry(ies) after a FAILED report")
  }

  private fun formatError(type: String, message: String?): String =
    if (message.isNullOrBlank()) type else "$type: $message"

  private fun truncate(message: String?): String? = when {
    message == null -> null
    message.length <= MAX_ERROR_MESSAGE_CHARS -> message
    else -> message.take(MAX_ERROR_MESSAGE_CHARS) + "… (truncated)"
  }

  companion object {
    /**
     * Cap on any single error message folded into a description. A tool error can carry a whole view
     * hierarchy, and these strings land in `llmExplanation`, which the report renders on a card.
     */
    const val MAX_ERROR_MESSAGE_CHARS = 500
  }
}
