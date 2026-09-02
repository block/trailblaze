package xyz.block.trailblaze.android.test

import kotlin.time.TimeSource

/** Millisecond-level timings emitted by the Android test driver for benchmark attribution. */
data class AndroidTestTiming(
  val toolName: String,
  /** Trailblaze work before invoking the native Espresso/Compose backend. */
  val orchestrationMs: Double,
  /** Native action/assertion plus the backend's settle work. */
  val nativeExecutionMs: Double,
  /** Logging performed after the native result returns. */
  val loggingMs: Double,
) {
  val totalMs: Double
    get() = orchestrationMs + nativeExecutionMs + loggingMs
}

fun interface AndroidTestMetricsSink {
  fun record(timing: AndroidTestTiming)

  companion object {
    val NONE = AndroidTestMetricsSink {}
  }
}

internal class AndroidTestStopwatch {
  private val started = TimeSource.Monotonic.markNow()

  fun elapsedMs(): Double = started.elapsedNow().inWholeNanoseconds / 1_000_000.0
}

/**
 * Lets a tool report the part of its own execution that was Trailblaze orchestration — walking the
 * view tree to build a snapshot and resolving a selector against it — rather than native
 * Espresso/Compose work.
 *
 * Without this, [AndroidTestTiming.nativeExecutionMs] overstates the native cost: a tool resolves
 * its own selector, so a full in-process hierarchy walk happens inside the measured call. The
 * benchmark numbers this driver exists to produce would attribute that walk to Espresso.
 *
 * Thread-confined rather than synchronized: one instrumentation test thread runs one tool at a time.
 */
internal object AndroidTestPhaseAttribution {
  private val orchestrationMs = ThreadLocal.withInitial { 0.0 }

  fun reset() = orchestrationMs.set(0.0)

  fun addOrchestration(ms: Double) = orchestrationMs.set(orchestrationMs.get() + ms)

  fun takeOrchestrationMs(): Double = orchestrationMs.get().also { orchestrationMs.set(0.0) }
}
