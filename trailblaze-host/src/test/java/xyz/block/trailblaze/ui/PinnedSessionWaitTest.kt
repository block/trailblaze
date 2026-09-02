package xyz.block.trailblaze.ui

import xyz.block.trailblaze.model.TrailExecutionResult
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins when `handleCliRunRequest` gives up waiting for a pinned session's logs.
 *
 * The wait exists because an on-device run's RPC returns before the trail finishes on the device.
 * It costs nothing when a session exists and everything when one never does: the handler sits for
 * ten minutes holding a failure it already has, and the CLI shows a silent prompt for all of it.
 *
 * Times are a fake clock in ms, starting at [POLL_START] to keep "since the poll started" and
 * "since the runner finished" visibly distinct.
 */
class PinnedSessionWaitTest {

  private val failedRun = TrailExecutionResult.Failed("Multi-device sessions can't run with self-heal enabled")

  @Test
  fun `a failed run that never opened a session stops waiting once the grace window passes`() {
    val guard = PinnedSessionWaitGuard()
    assertFalse(guard.shouldAbandon(failedRun, sessionSeen = false, nowMs = POLL_START))
    assertTrue(
      guard.shouldAbandon(failedRun, sessionSeen = false, nowMs = POLL_START + SESSION_APPEARANCE_GRACE_MS),
    )
  }

  @Test
  fun `a plain failure counts, not only one the runner marked misuse`() {
    // The misuse-marked case is already short-circuited before the poll. What reaches here is a
    // throw caught by the runner's generic handler, which leaves `misuse` unset — that was the
    // whole gap.
    assertFalse(failedRun.misuse, "fixture must be the un-marked failure this guard is for")
    assertTrue(abandonsAfterGrace(failedRun))
  }

  @Test
  fun `a cancelled run that never opened a session also stops waiting`() {
    assertTrue(abandonsAfterGrace(TrailExecutionResult.Cancelled))
  }

  @Test
  fun `a session-start log still being flushed is given the grace window`() {
    val guard = PinnedSessionWaitGuard()
    guard.shouldAbandon(failedRun, sessionSeen = false, nowMs = POLL_START)
    assertFalse(
      guard.shouldAbandon(failedRun, sessionSeen = false, nowMs = POLL_START + SESSION_APPEARANCE_GRACE_MS - 1),
    )
  }

  @Test
  fun `the grace window runs from the runner finishing, not from the poll starting`() {
    // The latch can be released by `onConnectionStatus` while the runner is still going, so
    // `onComplete` can land deep into the poll. Timed from the poll's start, such a result would
    // be abandoned on the first evaluation that sees it — no grace at all for the session-start
    // log the window exists to outlast.
    val guard = PinnedSessionWaitGuard()
    val runnerFinished = POLL_START + 5 * SESSION_APPEARANCE_GRACE_MS
    repeat(3) { guard.shouldAbandon(runnerResult = null, sessionSeen = false, nowMs = POLL_START + it * 1000L) }

    assertFalse(
      guard.shouldAbandon(failedRun, sessionSeen = false, nowMs = runnerFinished),
      "the result had just arrived; its window has not started running yet",
    )
    assertFalse(
      guard.shouldAbandon(failedRun, sessionSeen = false, nowMs = runnerFinished + SESSION_APPEARANCE_GRACE_MS - 1),
    )
    assertTrue(
      guard.shouldAbandon(failedRun, sessionSeen = false, nowMs = runnerFinished + SESSION_APPEARANCE_GRACE_MS),
    )
  }

  @Test
  fun `a session that exists but has not ended keeps its full window`() {
    // The reason to wait is unchanged when a session is actually there: it may still be writing
    // its terminal status, and that status is what the outcome is reconciled against.
    val guard = PinnedSessionWaitGuard()
    guard.shouldAbandon(failedRun, sessionSeen = true, nowMs = POLL_START)
    assertFalse(
      guard.shouldAbandon(failedRun, sessionSeen = true, nowMs = POLL_START + SESSION_APPEARANCE_GRACE_MS * 100),
    )
  }

  @Test
  fun `a successful run is never abandoned, however long the session takes to appear`() {
    // On-device completion legitimately precedes the session reaching Ended. Bailing here would
    // report a pass without ever reading the session that proves it.
    assertFalse(abandonsAfterGrace(TrailExecutionResult.Success()))
  }

  @Test
  fun `a run still in flight is never abandoned`() {
    // The runner's result is null until onComplete fires. The latch can be released earlier by a
    // connection-status error while the runner is still going, so "latch released" is not the
    // signal — "the runner reported a terminal result" is.
    assertFalse(abandonsAfterGrace(null))
  }

  /** Runs one poll evaluation at the start, then one a full grace window later. */
  private fun abandonsAfterGrace(runnerResult: TrailExecutionResult?): Boolean {
    val guard = PinnedSessionWaitGuard()
    guard.shouldAbandon(runnerResult, sessionSeen = false, nowMs = POLL_START)
    return guard.shouldAbandon(
      runnerResult,
      sessionSeen = false,
      nowMs = POLL_START + SESSION_APPEARANCE_GRACE_MS,
    )
  }

  companion object {
    private const val POLL_START = 1_000_000L
  }
}
