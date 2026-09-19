package xyz.block.trailblaze.android.accessibility

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * How long the end-of-trail gate waits on a launch confirmation that is still polling.
 *
 * Confirming an attach is detached from the launch so no launch pays for it, which leaves a window
 * at the end of a trail where the detector is already gone but nothing has recorded that yet. A
 * strict run that reads the verdict inside that window reports green having replayed at heuristic
 * speed — the silent green `turboRequired` exists to eliminate. The wait closes the window, and how
 * long it lasts is the whole of whether it does.
 */
class InProcessIdleConfirmationWaitTest {

  private val now = 10_000_000L

  @Test
  fun `a confirmation right at its deadline is still waited on for a full probe`() {
    // The poll checks the clock BEFORE each PING, so the last probe STARTS inside the budget and
    // finishes after it. Bounding the wait by the deadline alone returns in the moments before the
    // verdict lands — the gate would then read "no loss recorded" from a confirmation that was
    // about to record one.
    val budget = InProcessIdleLaunchReattacher.confirmationJoinBudgetMs(
      deadlineElapsedMs = now,
      nowElapsedMs = now,
    )

    // The whole probe, not just its connect: a listener that accepts and never replies holds the
    // PING for the read bound too, and that verdict is the one most worth waiting for.
    assertTrue(
      budget >= InProcessIdleLaunchReattacher.PING_MAX_MS,
      "a wait of ${budget}ms cannot outlast a probe that can take " +
        "${InProcessIdleLaunchReattacher.PING_MAX_MS}ms",
    )
  }

  @Test
  fun `a confirmation long past its deadline is not waited on`() {
    // It cannot still be polling: its own loop stopped at the deadline. Waiting anyway would park
    // teardown on a dead thread's budget.
    val budget = InProcessIdleLaunchReattacher.confirmationJoinBudgetMs(
      deadlineElapsedMs = now - 60_000L,
      nowElapsedMs = now,
    )

    assertTrue(budget <= 0, "expected no wait, got ${budget}ms")
  }

  @Test
  fun `a confirmation with most of its budget left is waited on for the rest of it`() {
    // Bounded by the confirmation's OWN deadline, not a fresh timeout: the poll is already
    // committed to stopping then, so the gate adds no wall clock beyond what the poll had left.
    val budget = InProcessIdleLaunchReattacher.confirmationJoinBudgetMs(
      deadlineElapsedMs = now + 25_000L,
      nowElapsedMs = now,
    )

    assertTrue(budget >= 25_000L, "expected the remaining budget to be honoured, got ${budget}ms")
    assertTrue(budget < 30_000L, "the wait should not outlast the poll by more than one probe")
  }
}
