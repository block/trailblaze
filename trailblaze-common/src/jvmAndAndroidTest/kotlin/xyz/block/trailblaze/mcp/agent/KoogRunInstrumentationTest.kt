package xyz.block.trailblaze.mcp.agent

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import kotlin.test.Test

/**
 * Pins the reporting rules of [KoogRunInstrumentation] — what a failed Koog objective is allowed to
 * claim about itself. The end-to-end half (that Koog's `EventHandler` actually feeds it) lives in
 * [KoogRunInstrumentationEventsTest].
 */
class KoogRunInstrumentationTest {

  private fun recorded(
    nodeFailures: List<Pair<String, Throwable>> = emptyList(),
    toolFailures: List<Pair<String, String>> = emptyList(),
    llmCallsCompleted: Int = 0,
  ) = KoogRunInstrumentation().apply {
    repeat(llmCallsCompleted) {
      recordLlmCallStarting(null)
      recordLlmCallCompleted()
    }
    nodeFailures.forEach { (node, error) ->
      recordNodeFailure(node, error::class.simpleName ?: "Throwable", error.message)
    }
    toolFailures.forEach { (tool, summary) -> recordRecoveredToolFailure(tool, summary) }
  }

  @Test
  fun `a failure names the node that threw and how far the objective got`() {
    val instrumentation = recorded(
      nodeFailures = listOf("callLlm" to IllegalStateException("model returned no tool call")),
      llmCallsCompleted = 4,
    )

    val explanation = instrumentation.describeThrow(IllegalStateException("model returned no tool call"))

    assertThat(explanation).contains("callLlm")
    assertThat(explanation).contains("4 LLM call(s)")
    assertThat(explanation).contains("IllegalStateException: model returned no tool call")
  }

  @Test
  fun `the innermost node failure is the attribution, not the outer node that re-threw it`() {
    // A node failure propagates through its enclosing nodes, each firing its own event. Keeping the
    // LAST would name whichever node happened to be outermost — always the same one, and never the
    // one that broke.
    val instrumentation = recorded(
      nodeFailures = listOf(
        "executeTools" to IllegalStateException("tap dispatch failed"),
        "prunePreSendResults" to IllegalStateException("tap dispatch failed"),
      ),
    )

    val explanation = instrumentation.describeThrow(null)

    assertThat(explanation).contains("executeTools")
    assertThat(explanation).doesNotContain("prunePreSendResults")
    assertThat(explanation).contains("first of 2 node failures")
  }

  @Test
  fun `a recovered tool failure is never the attribution`() {
    // Koog turns a failed tool call into a result it feeds back to the model, so the agent usually
    // carries on. Treating one as the cause would blame a tap the agent recovered from at turn 2
    // for a budget exhaustion at turn 20.
    val instrumentation = recorded(
      toolFailures = listOf("tapOnElementWithText" to "no such element"),
      llmCallsCompleted = 20,
    )

    val explanation = instrumentation.describeThrow(IllegalStateException("budget exhausted"))

    assertThat(explanation).contains("before any node reported a failure")
    assertThat(explanation).contains("IllegalStateException: budget exhausted")
  }

  @Test
  fun `recovered tool failures are still reported as context, with a count and the most recent one`() {
    val instrumentation = recorded(
      nodeFailures = listOf("callLlm" to IllegalStateException("connection reset")),
      toolFailures = listOf(
        "tapOnElementWithText" to "no such element",
        "swipe" to "gesture rejected",
      ),
    )

    val explanation = instrumentation.describeThrow(null)

    assertThat(explanation).contains("2 tool call(s) failed and were fed back to the model")
    assertThat(explanation).contains("'swipe': gesture rejected")
  }

  @Test
  fun `an exception that differs from the node's is named too, since one wrapped the other`() {
    val instrumentation = recorded(nodeFailures = listOf("callLlm" to IllegalStateException("connection reset")))

    val explanation = instrumentation.describeThrow(RuntimeException("agent run failed"))

    assertThat(explanation).contains("IllegalStateException: connection reset")
    assertThat(explanation).contains("propagated as RuntimeException: agent run failed")
  }

  @Test
  fun `the same exception seen at two depths is reported once`() {
    // The common case: the node's error IS what reaches the caller. Printing it twice reads as two
    // separate problems.
    val instrumentation = recorded(nodeFailures = listOf("callLlm" to IllegalStateException("connection reset")))

    val explanation = instrumentation.describeThrow(IllegalStateException("connection reset"))

    assertThat(explanation).doesNotContain("propagated as")
  }

  @Test
  fun `an objective that ended with neither a node failure nor an exception says exactly that`() {
    val explanation = recorded(llmCallsCompleted = 2).describeThrow(null)

    assertThat(explanation).contains("no node failure and no exception were recorded")
    assertThat(explanation).contains("2 LLM call(s)")
    // Nothing threw, so the explanation must not claim something did — a reader who is told the
    // graph threw will go looking for an exception that was never recorded.
    assertThat(explanation).doesNotContain("threw")
  }

  @Test
  fun `a huge error message is truncated, because it lands on a report card`() {
    // A tool error can carry a whole view hierarchy. The explanation is rendered verbatim in the
    // session report, so an untruncated one buries the step it belongs to.
    val instrumentation = recorded(
      nodeFailures = listOf("executeTools" to IllegalStateException("x".repeat(10_000))),
    )

    val explanation = instrumentation.describeThrow(null)

    assertThat(explanation).contains("(truncated)")
    assertThat(explanation.length).isGreaterThan(KoogRunInstrumentation.MAX_ERROR_MESSAGE_CHARS)
    assertThat(explanation.length < 1_000).isEqualTo(true)
  }

  @Test
  fun `the completed call count lags the started one while a request is in flight`() {
    // This is the number that becomes `callCount` on the objective's status. A request that never
    // returned did not cost a completed round, and the failure report should not claim it did.
    val instrumentation = KoogRunInstrumentation()
    instrumentation.recordLlmCallStarting(100)
    instrumentation.recordLlmCallCompleted()
    instrumentation.recordLlmCallStarting(100)

    assertThat(instrumentation.llmCallsStarted).isEqualTo(2)
    assertThat(instrumentation.llmCallsCompleted).isEqualTo(1)
  }

  @Test
  fun `the peak prompt estimate is the largest seen, not the latest`() {
    // Compression and pruning both shrink the prompt, so the last request is routinely smaller than
    // the biggest one. The peak is the number a token-based compression gate would have to clear.
    val instrumentation = KoogRunInstrumentation()
    instrumentation.recordLlmCallStarting(1_200)
    instrumentation.recordLlmCallStarting(9_800)
    instrumentation.recordLlmCallStarting(400)

    assertThat(instrumentation.peakEstimatedPromptTokens).isEqualTo(9_800)
  }

  @Test
  fun `no estimate at all reads as unknown rather than as zero tokens`() {
    val instrumentation = KoogRunInstrumentation()
    instrumentation.recordLlmCallStarting(null)

    assertThat(instrumentation.peakEstimatedPromptTokens).isNull()
    assertThat(instrumentation.describeRun()).contains("peak estimated prompt size unknown")
  }

  @Test
  fun `the run readout carries the counts a comparison run needs`() {
    val instrumentation = recorded(
      nodeFailures = listOf("callLlm" to IllegalStateException("boom")),
      toolFailures = listOf("swipe" to "gesture rejected"),
      llmCallsCompleted = 7,
    )
    instrumentation.recordLlmCallStarting(5_000)

    val readout = instrumentation.describeRun()

    assertThat(readout).contains("7 of 8 LLM call(s) completed")
    assertThat(readout).contains("~5000 tokens")
    assertThat(readout).contains("1 node failure(s)")
    assertThat(readout).contains("1 recovered tool failure(s)")
    assertThat(readout).contains("0 retry(ies) after a FAILED report")
  }

  @Test
  fun `a retry shows up in the readout and in a throw's explanation, so a rescued pass is not mistaken for a first-try pass`() {
    val instrumentation = recorded(
      nodeFailures = listOf("callLlm" to IllegalStateException("boom")),
      llmCallsCompleted = 6,
    )
    instrumentation.recordObjectiveRetry()

    assertThat(instrumentation.objectiveRetries).isEqualTo(1)
    assertThat(instrumentation.describeRun()).contains("1 retry(ies) after a FAILED report")
    // The call count in the explanation spans both attempts; the reader has to be told that.
    assertThat(instrumentation.describeThrow(null)).contains("retried 1 time(s) after the model reported FAILED")
  }

  @Test
  fun `an objective that was never retried does not mention retries in its explanation`() {
    val instrumentation = recorded(nodeFailures = listOf("callLlm" to IllegalStateException("boom")))

    assertThat(instrumentation.describeThrow(null)).doesNotContain("retried")
  }

  @Test
  fun `a fresh recorder claims nothing`() {
    val instrumentation = KoogRunInstrumentation()

    assertThat(instrumentation.firstNodeFailure).isNull()
    assertThat(instrumentation.lastRecoveredToolFailure).isNull()
    assertThat(instrumentation.llmCallsCompleted).isEqualTo(0)
    assertThat(instrumentation.describeThrow(null)).isNotNull()
  }
}
