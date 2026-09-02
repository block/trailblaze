package xyz.block.trailblaze.android.test

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import xyz.block.trailblaze.mcp.android.ondevice.rpc.OnDeviceScreenStateNotReadyException

/**
 * Runs [block] on the UI thread and returns its result.
 *
 * Anything that reads live View or layout state belongs here. Those reads — `isShown`,
 * `getGlobalVisibleRect`, `isFocused`, `parent`, `getChildAt` — are all mutated by the UI thread
 * during a layout pass, and taking them from the instrumentation thread can interleave with one
 * and produce a half-updated answer with no error to show for it: a quiescent screen always reads
 * clean, a real app mid-recomposition does not.
 *
 * The result crosses back as a [Result] because an exception escaping `runOnMainSync` would be
 * raised on the UI thread and crash the app under test instead of failing the tool.
 *
 * This hop is UNBOUNDED, and that is the contract for tool and action paths: an Espresso action
 * legitimately holds the main thread for as long as the interaction takes, and a deadline here
 * would turn a slow-but-healthy step into a failure. Screen-state reads must NOT come through
 * here — they hop via [onMainThreadForCapture], because a capture is what the host's readiness
 * probe reads and a wedged main thread would otherwise park every probe forever.
 */
internal fun <R> onMainThread(block: () -> R): R {
  if (Looper.myLooper() == Looper.getMainLooper()) return block()
  var captured: Result<R>? = null
  InstrumentationRegistry.getInstrumentation().runOnMainSync { captured = runCatching(block) }
  return checkNotNull(captured) { "runOnMainSync returned without running the block" }.getOrThrow()
}

/** Hang containment for a capture's main-thread hop, not a performance budget. */
internal const val CAPTURE_HOP_DEADLINE_MS = 30_000L

/**
 * The deadline [onMainThreadForCapture] actually enforces. Production never writes it.
 *
 * It exists so the on-device wedge test can prove the full-deadline wait — the assertion that
 * separates hang containment from a hop that gives up early — without spending
 * [CAPTURE_HOP_DEADLINE_MS] of wall clock on every run of it. A writer restores it.
 */
@Volatile internal var captureHopDeadlineMs: Long = CAPTURE_HOP_DEADLINE_MS

/** A healthy hop is milliseconds; one this slow is worth a logcat trail before it becomes a hang. */
internal const val CAPTURE_HOP_WARN_MS = 1_000L

private const val TAG = "TrailblazeCapture"

/** Numbers concurrent hops' log lines so an interleaved wedge is readable in logcat. */
private val captureHopIds = AtomicLong(0)

/** One handler, not one per hop: capture is polled continuously for the life of a run. */
private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

/**
 * [onMainThread] for screen-state reads: same UI-thread contract, bounded wait.
 *
 * Capture is what the host's readiness probe reads, so a capture parked behind a wedged main
 * thread makes a healthy-looking server silently unreachable, with nothing on the device saying
 * the main thread never ran the block. This hop gives up after [captureHopDeadlineMs] (always
 * [CAPTURE_HOP_DEADLINE_MS] outside tests) and throws
 * [OnDeviceScreenStateNotReadyException] — the "ask me again shortly" classification the
 * RPC handler keeps off the stack-trace-logging path. The deadline is generous on purpose: it
 * exists to contain a hang, and a merely busy main thread (a long animation, a heavy frame) must
 * still win. Note the host's readiness probes carry their own much shorter per-probe cap, so on
 * that path the host gives up first and this expiry's value is the device-side logcat diagnosis
 * plus the un-parked worker; in-trail captures, which have no such cap, receive the
 * classification directly.
 *
 * Every SCREEN READ comes through here, including the ones the agent and the polling tools take
 * mid-trail — for those a wedge now surfaces as a diagnosed step failure instead of an eternal
 * park. Only tool and action DISPATCH keeps [onMainThread]'s unbounded semantics — see its doc
 * for why. `AppUnderTestLauncher.resumedActivityOrNullWithin` is this same post-and-await shape
 * with a different contract (caller-supplied budget, null on expiry) for the same underlying
 * reason; a change to one deserves a look at the other.
 *
 * An expired wait marks the posted block abandoned rather than letting it run late: the host
 * re-polls every 500ms during a wedge, so the queue holds one block per abandoned probe, and
 * running each as a full discarded tree walk on release would delay the recovery the poll exists
 * to notice. Interruption of the awaiting thread (a cancelled host request tearing down its
 * worker) is answered the same way — flag restored, not-ready thrown — since "this capture
 * produced no answer" is the only thing the caller can still hear.
 */
internal fun <R> onMainThreadForCapture(block: () -> R): R {
  if (Looper.myLooper() == Looper.getMainLooper()) return block()
  val hopId = captureHopIds.incrementAndGet()
  val captured = AtomicReference<Result<R>?>(null)
  val abandoned = AtomicBoolean(false)
  val ran = CountDownLatch(1)
  // Read once: a hop enforces the deadline that was in force when it started, so a test restoring
  // the default cannot change the bound out from under a hop already waiting on it.
  val deadlineMs = captureHopDeadlineMs
  val postedAt = SystemClock.uptimeMillis()
  val posted = mainHandler.post {
    if (abandoned.get()) {
      Log.w(
        TAG,
        "Capture hop #$hopId: the main thread became available " +
          "${SystemClock.uptimeMillis() - postedAt}ms after the post, after the capture was " +
          "abandoned — skipping the stale read",
      )
      return@post
    }
    captured.set(runCatching(block))
    ran.countDown()
    if (abandoned.get()) {
      Log.w(
        TAG,
        "Capture hop #$hopId: the read completed " +
          "${SystemClock.uptimeMillis() - postedAt}ms after the post, after the capture was " +
          "abandoned — result discarded",
      )
    }
  }
  if (!posted) {
    throw OnDeviceScreenStateNotReadyException(
      "The app's main looper is not accepting work (quit or quitting), so there is no UI thread " +
        "to snapshot from",
    )
  }
  val ranInTime =
    try {
      ran.await(deadlineMs, TimeUnit.MILLISECONDS)
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
      abandoned.set(true)
      throw OnDeviceScreenStateNotReadyException(
        "Interrupted after ${SystemClock.uptimeMillis() - postedAt}ms while waiting for the " +
          "app's main thread to run a screen-state capture",
      )
    }
  // A block that completed in the race window between the await expiring and this line is a good
  // read — take it. Only a block that has genuinely produced nothing is abandoned.
  if (!ranInTime && captured.get() == null) {
    abandoned.set(true)
    val elapsedMs = SystemClock.uptimeMillis() - postedAt
    Log.w(TAG, "Capture hop #$hopId: abandoned — the main thread has not run it after ${elapsedMs}ms")
    throw OnDeviceScreenStateNotReadyException(
      "The app's main thread did not run the screen-state capture within ${deadlineMs}ms — " +
        "it is likely blocked or busy-looping, so there is no consistent frame to snapshot",
    )
  }
  val elapsedMs = SystemClock.uptimeMillis() - postedAt
  if (elapsedMs >= CAPTURE_HOP_WARN_MS) {
    Log.w(TAG, "Capture hop #$hopId: waited ${elapsedMs}ms for the main thread")
  }
  return checkNotNull(captured.get()) { "The capture block signalled completion without a result" }
    .getOrThrow()
}
