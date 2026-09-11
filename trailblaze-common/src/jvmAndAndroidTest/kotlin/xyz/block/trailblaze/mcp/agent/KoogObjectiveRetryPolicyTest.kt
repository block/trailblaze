package xyz.block.trailblaze.mcp.agent

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isLessThan
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * The accept / retry / give-up rule on its own, with no graph. [KoogObjectiveRetryGraphTest] proves
 * the same rule steers the real Koog subgraph.
 */
class KoogObjectiveRetryPolicyTest {

  @Test
  fun `an attempt with nothing to feed back is accepted and consumes no retry`() {
    val policy = KoogObjectiveRetryPolicy(feedbackForRetry = { null })

    assertThat(policy.judgeAttempt()).isEqualTo(KoogObjectiveRetryPolicy.Verdict.Accept)
    assertThat(policy.attempts).isEqualTo(1)
    assertThat(policy.retries).isEqualTo(0)
  }

  @Test
  fun `a rejected attempt is retried while the budget lasts, then stands`() {
    val retried = mutableListOf<Pair<Int, String>>()
    val policy = KoogObjectiveRetryPolicy(
      maxRetries = 1,
      feedbackForRetry = { "try scrolling" },
      onRetry = { attempt, feedback -> retried += attempt to feedback },
    )

    val first = policy.judgeAttempt()
    assertThat(first).isEqualTo(KoogObjectiveRetryPolicy.Verdict.Reject(feedback = "try scrolling", willRetry = true))

    val second = policy.judgeAttempt()
    // Still a reject — the attempt was not good — but the graph must finish with it, not loop.
    assertThat(second).isEqualTo(KoogObjectiveRetryPolicy.Verdict.Reject(feedback = "try scrolling", willRetry = false))

    assertThat(policy.attempts).isEqualTo(2)
    assertThat(policy.retries).isEqualTo(1)
    // The listener fires only for the retry that happens, never for the exhausted reject.
    assertThat(retried).isEqualTo(listOf(1 to "try scrolling"))
  }

  @Test
  fun `a recovered second attempt is accepted after one retry`() {
    var outcome = "FAILED"
    val policy = KoogObjectiveRetryPolicy(feedbackForRetry = { if (outcome == "FAILED") "look again" else null })

    assertThat(policy.judgeAttempt()).isInstanceOf(KoogObjectiveRetryPolicy.Verdict.Reject::class)
    outcome = "COMPLETED"
    assertThat(policy.judgeAttempt()).isEqualTo(KoogObjectiveRetryPolicy.Verdict.Accept)
    assertThat(policy.retries).isEqualTo(1)
  }

  @Test
  fun `zero retries judges but never retries`() {
    var listenerCalls = 0
    val policy = KoogObjectiveRetryPolicy(maxRetries = 0, feedbackForRetry = { "nope" }, onRetry = { _, _ -> listenerCalls++ })

    val verdict = policy.judgeAttempt() as KoogObjectiveRetryPolicy.Verdict.Reject
    assertThat(verdict.willRetry).isEqualTo(false)
    assertThat(policy.retries).isEqualTo(0)
    assertThat(listenerCalls).isEqualTo(0)
  }

  @Test
  fun `a negative retry budget is refused up front`() {
    assertFailsWith<IllegalArgumentException> { KoogObjectiveRetryPolicy(maxRetries = -1, feedbackForRetry = { null }) }
  }

  @Test
  fun `withRetryListener keeps the original listener and the same budget`() {
    val calls = mutableListOf<String>()
    val original = KoogObjectiveRetryPolicy(maxRetries = 2, feedbackForRetry = { "fb" }, onRetry = { _, _ -> calls += "original" })
    val extended = original.withRetryListener { _, _ -> calls += "added" }

    extended.judgeAttempt()

    assertThat(calls).isEqualTo(listOf("original", "added"))
    assertThat(extended.maxRetries).isEqualTo(2)
  }

  @Test
  fun `withRetryListener refuses a policy that has already judged an attempt`() {
    // The copy starts with fresh counters; decorating mid-run would grant a second retry budget.
    val policy = KoogObjectiveRetryPolicy(maxRetries = 1, feedbackForRetry = { "fb" })
    policy.judgeAttempt()

    assertFailsWith<IllegalStateException> { policy.withRetryListener { _, _ -> } }
  }

  @Test
  fun `the feedback carries the model's own reason and says the device did not rewind`() {
    val feedback = KoogObjectiveRetryPolicy.feedbackForFailedObjective("no 'Open in settings' control on this build")

    assertThat(feedback).contains("objectiveStatus=FAILED")
    assertThat(feedback).contains("\"no 'Open in settings' control on this build\"")
    // The one thing a rewound conversation would otherwise get wrong.
    assertThat(feedback).contains("device has NOT been reset")
    assertThat(feedback).contains("Look at the current screen before")
    // ...and its consequence: an effect that already landed must not be redone.
    assertThat(feedback).contains("if the screen already shows the goal reached, report objectiveStatus=COMPLETED")
    assertThat(feedback.indexOf("objectiveStatus=COMPLETED")).isLessThan(feedback.indexOf("different approach"))
  }

  @Test
  fun `a blank reason is left out rather than quoted as empty`() {
    val feedback = KoogObjectiveRetryPolicy.feedbackForFailedObjective("   ")

    assertThat(feedback).doesNotContain("\"\"")
    assertThat(feedback).contains("objectiveStatus=FAILED. That attempt's conversation")
  }
}
