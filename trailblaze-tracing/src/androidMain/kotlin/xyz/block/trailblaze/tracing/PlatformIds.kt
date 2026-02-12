package xyz.block.trailblaze.tracing

import android.app.Application
import android.os.Process

actual object PlatformIds {
  actual fun pid(): Long = try {
    Process.myPid().toLong()
  } catch (_: Throwable) {
    // Fallback for Android local unit tests where android.os.Process is not mocked
    -1L
  }
  actual fun tid(): Long = try {
    Process.myTid().toLong()
  } catch (_: Throwable) {
    // Fallback for Android local unit tests where android.os.Process is not mocked
    Thread.currentThread().id
  }
  actual fun threadName(): String? = Thread.currentThread().name
  @Suppress("NewApi") // Wrapped in try-catch, safe to call
  actual fun processName(): String? = try {
    Application.getProcessName()
  } catch (_: Throwable) {
    null
  }
}
