package xyz.block.trailblaze.ui

import java.io.File
import kotlinx.coroutines.delay
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

/** How long a pinned session's directory must go unchanged before its trailing files count as written. */
internal const val SESSION_TRAILING_FILES_QUIET_MS: Long = 500L

/** Upper bound on the trailing-files wait; the fixed buffer it replaced always slept this long. */
internal const val SESSION_TRAILING_FILES_MAX_WAIT_MS: Long = 3_000L

/**
 * Returns once nothing under [sessionDir] has been added, resized, or rewritten for [quietMs], or
 * after [maxWaitMs] regardless.
 *
 * A session can reach `Ended` before its last files land: an on-device runner uploads screenshots
 * and logs after writing its own end status. `handleCliRunRequest` used to cover that with a flat
 * three-second sleep after every run. On the host-driven path the runner finalizes every artifact
 * before it reports completion, so the directory is already still and that sleep bought nothing.
 * Waiting for the directory to settle keeps the buffer for runs that are still writing and drops
 * it for runs that are not.
 */
internal suspend fun awaitSessionDirQuiet(
  sessionDir: File,
  quietMs: Long = SESSION_TRAILING_FILES_QUIET_MS,
  maxWaitMs: Long = SESSION_TRAILING_FILES_MAX_WAIT_MS,
  pollMs: Long = 100L,
  nowMs: () -> Long = System::currentTimeMillis,
  sleep: suspend (Long) -> Unit = { delay(it) },
) {
  val startMs = nowMs()
  var lastSeen = sessionDirFingerprint(sessionDir)
  var lastChangeMs = startMs
  while (true) {
    val now = nowMs()
    if (now - lastChangeMs >= quietMs || now - startMs >= maxWaitMs) return
    sleep(pollMs)
    val current = sessionDirFingerprint(sessionDir)
    if (current != lastSeen) {
      lastSeen = current
      lastChangeMs = nowMs()
    }
  }
}

private data class FileStamp(val path: String, val length: Long, val lastModified: Long)

private fun sessionDirFingerprint(dir: File): Set<FileStamp> =
  dir.walkTopDown()
    .filter { it.isFile }
    .map { FileStamp(it.path, it.length(), it.lastModified()) }
    .toSet()
