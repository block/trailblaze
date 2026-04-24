package xyz.block.trailblaze.util

import android.util.Log
import java.io.PrintStream

/**
 * Android implementation of [Console].
 *
 * All output goes to Logcat with the "Trailblaze" tag when running on a real
 * device or emulator. When running as a local JVM unit test (where `android.util.Log`
 * is a non-functional stub), this automatically falls back to [out] / [System.err]
 * so tests work without any special Gradle configuration.
 */
actual object Console {
  private const val TAG = "Trailblaze"

  /**
   * Stream used in the unit-test fallback path. Mirrors the field of the same
   * name in `Console.jvm.kt` so log-capture tests in shared (jvm+android) source
   * sets can swap it via reflection without a platform-specific code path.
   */
  @Volatile private var out: PrintStream = System.out

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
      out.println(message)
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
      out.print(message)
      out.flush()
    }
  }

  actual fun appendInfo(message: String) = appendLog(message)

  actual fun info(message: String) = log(message)

  actual fun useStdErr() {
    // No-op on Android — Logcat is always the output.
  }

  actual fun enableQuietMode() {
    // No-op on Android — Logcat is always the output.
  }

  actual fun disableQuietMode() {
    // No-op on Android — Logcat is always the output.
  }

  actual fun isQuietMode(): Boolean = false

  actual fun enableJsonMode() {
    // No-op on Android — Logcat is always the output.
  }
}
