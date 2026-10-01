package xyz.block.trailblaze.ui

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runInterruptible

/**
 * Runs [block], which starts a trail, reports its job through `onTrailStarted`, and blocks until the
 * trail ends. Cancelling the caller also cancels that trail.
 *
 * The trail runs in the device's own scope, not the caller's, so cancelling a CLI run used to stop
 * only the daemon's bookkeeping: the trail kept the device and ran beside the CLI's retry, and a
 * late pass still landed in its session. Only this trail's job is cancelled, never the device's
 * scope, which parallel CLI runs on the same device share. A trail blocked in a synchronous driver
 * call sees the cancel when that call returns.
 */
internal suspend fun <T> blockUntilTrailEndsOrCancelIt(block: (onTrailStarted: (Job) -> Unit) -> T): T {
  val trail = AtomicReference<Job?>(null)
  try {
    // Interruptible so a cancel reaches the thread blocked waiting for the trail.
    return runInterruptible { block { trail.set(it) } }
  } catch (e: CancellationException) {
    trail.get()?.cancel()
    throw e
  }
}
