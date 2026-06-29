package xyz.block.trailblaze.ui

import xyz.block.trailblaze.logs.model.SessionStatus

/** Final pass/fail decision for one daemon-delegated trail run. */
data class ReconciledRunOutcome(
  val success: Boolean,
  val error: String?,
)

/**
 * Reconciles the in-memory run outcome (latch result + any connect/teardown error) against the
 * pinned session's on-disk [SessionStatus], which is the source of truth for pass/fail.
 *
 * Symmetric with the in-process cross-check in `TrailCommand.runSingleTrailFile`:
 * - A session that ended Succeeded(WithSelfHeal) is a pass even when a post-run connect/teardown
 *   error set [latchError] — on the V1 on-device-RPC path a dead instrumentation server can emit a
 *   terminal code that a later connect reads as a ConnectionFailure AFTER the trail already passed.
 * - A session that ended in any other terminal status stays/falls to failure; one that never
 *   reached Ended keeps the in-memory outcome.
 *
 * @param latchSuccess the run's success flag from the completion latch (`onComplete` Success).
 * @param latchError any error recorded by the latch (`onConnectionStatus` ConnectionFailure or
 *   `onComplete` Failed/Cancelled), null when none.
 * @param diskStatus the pinned session's latest on-disk status, null when unavailable.
 * @param sessionDescription identifies the pinned session in a demotion error message.
 */
internal fun reconcileRunOutcome(
  latchSuccess: Boolean,
  latchError: String?,
  diskStatus: SessionStatus?,
  sessionDescription: String,
): ReconciledRunOutcome {
  val endedSucceeded = diskStatus is SessionStatus.Ended.Succeeded ||
    diskStatus is SessionStatus.Ended.SucceededWithSelfHeal
  if (endedSucceeded) {
    return ReconciledRunOutcome(success = true, error = null)
  }

  if (latchSuccess && diskStatus is SessionStatus.Ended) {
    return ReconciledRunOutcome(
      success = false,
      error = "Session $sessionDescription ended with status: ${diskStatus::class.simpleName}",
    )
  }

  return ReconciledRunOutcome(success = latchSuccess && latchError == null, error = latchError)
}
