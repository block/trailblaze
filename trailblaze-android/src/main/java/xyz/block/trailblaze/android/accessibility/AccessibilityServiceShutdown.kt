package xyz.block.trailblaze.android.accessibility

/** Removes only this service and waits for it to stop within one shared shutdown budget. */
internal fun shutdownAccessibilityService(
  serviceComponent: String,
  timeoutMs: Long,
  execShell: (String, Long) -> String,
  isRunning: () -> Boolean,
  nowMs: () -> Long,
  sleepMs: (Long) -> Unit,
) {
  require(timeoutMs > 0) { "Accessibility service shutdown timeout must be positive" }
  val startedMs = nowMs()

  fun remainingMs(): Long {
    val remaining = timeoutMs - (nowMs() - startedMs)
    check(remaining > 0) { "Timed out waiting for TrailblazeAccessibilityService to stop" }
    return remaining
  }

  fun shell(command: String): String = execShell(command, remainingMs())

  val services =
    shell("settings get secure enabled_accessibility_services")
      .trim()
      .split(":")
      .filter { it.isNotBlank() && it != "null" }
  if (serviceComponent in services) {
    val remainingServices = services.filter { it != serviceComponent }
    if (remainingServices.isEmpty()) {
      shell("settings delete secure enabled_accessibility_services")
      shell("settings put secure accessibility_enabled 0")
    } else {
      shell("settings put secure enabled_accessibility_services ${remainingServices.joinToString(":")}")
    }
  }

  while (true) {
    remainingMs()
    if (!isRunning()) return
    sleepMs(minOf(100, remainingMs()))
  }
}
