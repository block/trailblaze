package xyz.block.trailblaze.tracing

import java.lang.management.ManagementFactory

actual object PlatformIds {
  // Parse "12345@hostname" from RuntimeMXBean.name (works on Java 8+)
  private val pidCached: Long by lazy {
    val name = try {
      ManagementFactory.getRuntimeMXBean().name
    } catch (_: Throwable) {
      null
    }
    val pidPart = name?.substringBefore('@')
    pidPart?.toLongOrNull() ?: -1L
  }

  // A friendlier process name if available; otherwise fallback to MXBean name.
  private val processNameCached: String? by lazy {
    // sun.java.command is common on HotSpot/OpenJ9; e.g., "com.myapp.Main arg1 arg2"
    val sunCmd = runCatching { System.getProperty("sun.java.command") }.getOrNull()
    sunCmd?.takeIf { it.isNotBlank() }
      ?: runCatching { ManagementFactory.getRuntimeMXBean().name }.getOrNull()
  }

  actual fun pid(): Long = pidCached
  actual fun tid(): Long = Thread.currentThread().id
  actual fun threadName(): String? = Thread.currentThread().name
  actual fun processName(): String? = processNameCached
}
