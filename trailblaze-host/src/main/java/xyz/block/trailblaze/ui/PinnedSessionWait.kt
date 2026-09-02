package xyz.block.trailblaze.ui

import xyz.block.trailblaze.model.TrailExecutionResult

/** How long the guard keeps waiting for a session to APPEAR after the runner gave up on it. */
internal const val SESSION_APPEARANCE_GRACE_MS: Long = 15_000L

/**
 * Decides when `handleCliRunRequest` should stop waiting for a pinned session's logs.
 *
 * The handler polls for the pinned session to reach a terminal status, because an on-device
 * instrumentation run's RPC returns before the trail finishes executing on the device. That poll
 * assumes a session exists. When the runner fails before opening one — a rejected driver pin, a
 * device that went away, a trail the runner refuses at session start — nothing will ever be
 * written under that id, and the poll runs its whole window (ten minutes) before the handler
 * returns the failure it already had in hand.
 *
 * [cliRunRunnerRejectionResponse] already short-circuits this for a rejection the runner marked
 * `misuse`. It is not enough: a throw caught by the runner's generic handler becomes a plain
 * [TrailExecutionResult.Failed], `misuse` unset, and takes the full window.
 *
 * So the signal is not how the failure was classified — it is that the runner is DONE (a non-null
 * result means `onComplete` fired, not merely that the latch was released by a connection error)
 * and it did not succeed, and no session has appeared since. A run that is over and never opened
 * a session will not open one now; the grace window is only there to outlast a session-start log
 * still being flushed.
 *
 * A successful run keeps waiting exactly as before, however long it takes: on-device completion
 * legitimately precedes the session reaching `Ended`, and abandoning that wait would report a
 * pass without ever reading the session that proves it.
 *
 * Stateful rather than a pure predicate because the grace window runs from the moment the RUNNER
 * finished, not from the moment the poll started. `onConnectionStatus` can release the completion
 * latch while the runner is still going, so `onComplete` may land deep into the poll; timed from
 * the poll's start, such a result arrives with the window already spent and is abandoned on its
 * first evaluation, with no grace for the very flush the window exists for. The guard therefore
 * remembers when it first saw a result and measures from there. One instance per run.
 */
internal class PinnedSessionWaitGuard(
  private val graceMs: Long = SESSION_APPEARANCE_GRACE_MS,
) {
  private var runnerDoneAtMs: Long? = null

  /**
   * @param runnerResult the runner's terminal result, null while the run is still in flight.
   * @param sessionSeen whether the pinned session has appeared on disk at any point in this poll.
   * @param nowMs current time; also starts the grace window on the first call that carries a
   *   [runnerResult].
   */
  fun shouldAbandon(
    runnerResult: TrailExecutionResult?,
    sessionSeen: Boolean,
    nowMs: Long,
  ): Boolean {
    if (runnerResult == null) return false
    val doneAt = runnerDoneAtMs ?: nowMs.also { runnerDoneAtMs = it }
    return runnerResult !is TrailExecutionResult.Success &&
      !sessionSeen &&
      nowMs - doneAt >= graceMs
  }
}
