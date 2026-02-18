package xyz.block.trailblaze.util

import android.util.Log

/**
 * Android implementation of [Console].
 *
 * All output goes to Logcat with the "Trailblaze" tag when running on a real
 * device or emulator. When running as a local JVM unit test (where `android.util.Log`
 * is a non-functional stub), this automatically falls back to [println] / [System.err]
 * so tests work without any special Gradle configuration.
 */
actual object Console {
  private const val TAG = "Trailblaze"

  /**
   * `true` when `android.util.Log` is usable (real device / emulator).
   * `false` in local JVM unit tests where the Android SDK is stubbed out.
   */
  private val isLogAvailable: Boolean = try {
    Log.i(TAG, "Console initialized")
    true
  } catch (_: Throwable) {
    false
  }

  actual fun log(message: String) {
    if (isLogAvailable) {
      Log.i(TAG, message)
    } else {
      println(message)
    }
  }

  actual fun error(message: String) {
    if (isLogAvailable) {
      Log.e(TAG, message)
    } else {
      System.err.println(message)
    }
  }

  actual fun appendLog(message: String) {
    if (isLogAvailable) {
      Log.i(TAG, message)
    } else {
      print(message)
      System.out.flush()
    }
  }

  actual fun useStdErr() {
    // No-op on Android — Logcat is always the output.
  }
}
