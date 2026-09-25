package xyz.block.trailblaze.capture.memory

import xyz.block.trailblaze.capture.video.runSubprocessWithTimeout
import xyz.block.trailblaze.util.isMacOs

/**
 * Reads an iOS Simulator app's memory. A simulator app is an ordinary process on the Mac, so the
 * probe resolves its pid through the simulator's `launchctl` and reads macOS's own `footprint -p`
 * (the physical footprint iOS enforces limits against, with a per-category breakdown). No
 * device-wide reading: the host's RAM is not the simulated device's.
 *
 * `xcrun simctl spawn … launchctl list` is the expensive part (about half a second against a
 * booted simulator) where `footprint` is about a tenth of that, and this runs twice per tool call.
 * So the pid is remembered per app once found and only `footprint` runs while that process still
 * answers; a dead pid (the trail force-restarted the app) or an app not yet running goes back to
 * `launchctl`. RSS is not read: it is not part of the event, and a `ps` per reading is not free.
 *
 * There is no garbage collector to force under ARC, so [MemorySnapshot.gcForced] is always null.
 *
 * [run] is injectable so the parsing and pid resolution are unit-tested without a simulator.
 */
class IosSimulatorMemoryProbe(
  private val run: (command: List<String>) -> String? = ::runHostCommand,
) : MemoryProbe {

  private val knownPidByApp = mutableMapOf<String, Int>()

  override fun read(deviceId: String, appId: String?, forceGc: Boolean): MemorySnapshot? {
    if (appId == null) {
      // Nothing device-wide to read on a simulator: an event stream of nothing is not worth writing.
      return null
    }
    val key = "$deviceId/$appId"
    var pid = knownPidByApp[key]
    var sample = pid?.let(::footprintOf)
    if (sample == null) {
      // No remembered pid, or the remembered process is gone: resolve afresh.
      knownPidByApp.remove(key)
      val launchctl = run(listOf("xcrun", "simctl", "spawn", deviceId, "launchctl", "list")) ?: return null
      pid = parseLaunchctlPid(launchctl, appId)
      sample = pid?.let(::footprintOf)
      if (pid != null && sample != null) knownPidByApp[key] = pid
    }
    return MemorySnapshot(
      timeMs = 0,
      appId = appId,
      // `launchctl` is the liveness answer; a `footprint` that refused or did not parse must NOT
      // null it, or a healthy app reads as `process_died` and then `process_started` on the next
      // pass, publishing a restart that never happened. An unreadable footprint is a missing
      // reading, not a missing process. Same reasoning as the adb probe.
      pid = pid,
      app = null,
      device = null,
      iosApp = sample,
      source = MemoryProbe.SOURCE_SIMULATOR,
    )
  }

  private fun footprintOf(pid: Int): IosAppMemorySample? {
    val footprint = run(listOf("footprint", "-p", pid.toString())) ?: return null
    return parseFootprint(footprint)
  }

  companion object {
    private const val COMMAND_TIMEOUT_SECONDS: Long = 5

    /**
     * `launchctl list` prints `<pid>\t<status>\tUIKitApplication:<bundleId>[<hash>][…]` for each
     * running app. A `-` pid is a registered-but-not-running job.
     */
    fun parseLaunchctlPid(output: String, bundleId: String): Int? {
      val marker = "UIKitApplication:$bundleId["
      return output.lineSequence()
        .filter { it.contains(marker) }
        .mapNotNull { it.trim().split(Regex("\\s+")).firstOrNull()?.toIntOrNull() }
        .firstOrNull()
    }

    /**
     * Parses `footprint -p <pid>`: the `Footprint: 63 MB` headline, then one row per category with
     * dirty / clean / reclaimable columns; only the dirty column counts toward the footprint.
     * Returns null when the headline is missing (no such pid, or `footprint` refused).
     */
    fun parseFootprint(output: String): IosAppMemorySample? {
      val headline = FOOTPRINT_LINE.find(output) ?: return null
      val footprintKb = toKb(headline.groupValues[1], headline.groupValues[2]) ?: return null
      val categories = linkedMapOf<String, Long>()
      var inTable = false
      for (raw in output.lineSequence()) {
        val line = raw.trim()
        if (line.startsWith("---")) { inTable = true; continue }
        if (!inTable || line.isEmpty()) continue
        val m = CATEGORY_ROW.find(line) ?: continue
        val dirtyKb = toKb(m.groupValues[1], m.groupValues[2]) ?: continue
        categories[m.groupValues[3].trim()] = dirtyKb
      }
      val malloc = categories.filterKeys { it.startsWith("MALLOC") }.values.takeIf { it.isNotEmpty() }?.sum()
      return IosAppMemorySample(footprintKb = footprintKb, rssKb = null, mallocKb = malloc, categories = categories)
    }

    private fun toKb(number: String, unit: String): Long? {
      val n = number.toDoubleOrNull() ?: return null
      return when (unit.uppercase()) {
        "B" -> (n / 1024).toLong()
        "KB" -> n.toLong()
        "MB" -> (n * 1024).toLong()
        "GB" -> (n * 1024 * 1024).toLong()
        else -> null
      }
    }

    private val FOOTPRINT_LINE = Regex("""Footprint:\s+([\d.]+)\s*(B|KB|MB|GB)\b""")
    // "5600 KB        0 B      2624 KB          4    MALLOC_SMALL" — dirty, clean, reclaimable, regions, name.
    private val CATEGORY_ROW = Regex("""^([\d.]+)\s*(B|KB|MB|GB)\s+[\d.]+\s*(?:B|KB|MB|GB)\s+[\d.]+\s*(?:B|KB|MB|GB)\s+\d+\s+(.+)$""")

    private fun runHostCommand(command: List<String>): String? {
      if (!isMacOs()) return null
      val result = runSubprocessWithTimeout(command, COMMAND_TIMEOUT_SECONDS) ?: return null
      return if (result.exitCode == 0) result.output else null
    }
  }
}
