package xyz.block.trailblaze.android.test

import android.os.SystemClock
import android.util.Log

/**
 * Per-phase timings to logcat, off unless asked for:
 * `adb shell setprop log.tag.TrailblazePerf DEBUG`, then `adb logcat -s TrailblazePerf`. Read once
 * per process, so set it before the instrumentation starts.
 */
internal val perfLoggingEnabled: Boolean by lazy { Log.isLoggable(PERF_TAG, Log.DEBUG) }

internal const val PERF_TAG = "TrailblazePerf"

internal inline fun <R> perf(label: String, block: () -> R): R {
  if (!perfLoggingEnabled) return block()
  val start = SystemClock.elapsedRealtimeNanos()
  try {
    return block()
  } finally {
    Log.d(PERF_TAG, "$label ${(SystemClock.elapsedRealtimeNanos() - start) / 1_000_000}ms")
  }
}
