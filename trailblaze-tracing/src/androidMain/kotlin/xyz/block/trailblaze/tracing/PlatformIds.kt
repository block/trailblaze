package xyz.block.trailblaze.tracing

import android.os.Process

actual object PlatformIds {
  actual fun pid(): Long = Process.myPid().toLong()
  actual fun tid(): Long = Process.myTid().toLong()
  actual fun threadName(): String? = Thread.currentThread().name
  actual fun processName(): String? = try {
    android.app.Application.getProcessName()
  } catch (_: Throwable) {
    null
  }
}
