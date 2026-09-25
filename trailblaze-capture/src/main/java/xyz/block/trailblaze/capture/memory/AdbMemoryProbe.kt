package xyz.block.trailblaze.capture.memory

import xyz.block.trailblaze.device.androidPackageNameViolation
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.mcp.android.ondevice.rpc.AndroidMemoryReadCommands
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.util.AndroidHostAdbUtils
import xyz.block.trailblaze.util.Console

/**
 * Reads an Android app's memory over plain adb: `pidof`, `/proc/meminfo`, `dumpsys meminfo <pid>`,
 * and once per app the heap-limit properties. Works against any installed app — nothing has to be
 * running on the device. The whole reading is one shell round trip, because it runs around every
 * tool call and adb's per-command latency would otherwise show up in the trail's own timings.
 *
 * Forcing a GC needs a debuggable app: ART collects on `SIGUSR1`, but the adb shell user may only
 * signal a process it can `run-as`. A release build reports `gcForced = false` and the heap figures
 * then include garbage not yet collected.
 *
 * [shell] is injectable so the probe is unit-tested without a device.
 */
class AdbMemoryProbe(
  private val shell: (deviceId: String, command: String) -> String? = ::adbShell,
) : MemoryProbe {

  /**
   * The heap limits read once per app per device. Keyed by both because one probe can serve
   * several devices and a limit is a property of the pair, not of the package name.
   *
   * A null VALUE is a cached answer of "this device would not tell us", which is exactly the case
   * worth remembering: `getOrPut` treats a stored null as absent, so a device whose properties do
   * not parse would pay an extra `getprop` and `dumpsys package` round trip on every single
   * reading, forever, against the class's own "once per app".
   */
  private val limitsByApp = mutableMapOf<String, AppMemoryLimits?>()

  /** App ids already reported as unusable, so a sampler on a timer says it once and not per reading. */
  private val rejectedAppIds = mutableSetOf<String>()

  override fun read(deviceId: String, appId: String?, forceGc: Boolean): MemorySnapshot? {
    val target = usableAppId(appId)
    val output = shell(deviceId, readCommand(target, forceGc)) ?: return null
    if (target == null) {
      return MemorySnapshot(
        timeMs = 0,
        appId = null,
        pid = null,
        app = null,
        device = DumpsysMeminfoParser.parseDeviceSample(output),
        source = MemoryProbe.SOURCE_ADB,
      )
    }
    // Sections: pid, /proc/meminfo, then — only while the app runs — the GC marker and the dump.
    val parts = output.split(SEPARATOR)
    val pid = DumpsysMeminfoParser.parsePid(parts.getOrNull(0))
    val device = DumpsysMeminfoParser.parseDeviceSample(parts.getOrNull(1) ?: "")
    val appSample = parts.getOrNull(3)?.let(DumpsysMeminfoParser::parseAppSample)
    val gcForced = if (appSample != null && forceGc) parts.getOrNull(2)?.contains(GC_OK) == true else null
    return MemorySnapshot(
      timeMs = 0,
      appId = target,
      // `pidof` is the liveness answer, so it is the one that decides the pid. A dump that did not
      // parse must NOT null it: [DumpsysMeminfoParser.parseAppSample] answers null both for a
      // process that is gone and for a dump whose shape it does not recognise, and nulling the pid
      // on the second case reports a healthy app as `process_died`. A process that exits between
      // the two reads is instead reported by the next reading, whose `pidof` finds nothing.
      pid = pid,
      app = appSample,
      device = device,
      limits = if (appSample != null) limitsFor(deviceId, target) else null,
      gcForced = if (appSample != null && !forceGc) false else gcForced,
      source = MemoryProbe.SOURCE_ADB,
    )
  }

  /**
   * [appId] if it can be interpolated into these scripts, or null to read the device only.
   *
   * Every command below puts the id straight into a device shell — `pidof`, `dumpsys package`, and
   * the `run-as` GC signal — so an id outside the Android package-name grammar could smuggle shell
   * metacharacters into a sampler that runs on its own timer, unprompted by any tool call. The
   * grammar is the canonical rule ([androidPackageNameViolation]) rather than a second escaping
   * scheme of this file's own.
   *
   * An unusable id degrades to the no-app reading instead of throwing: memory capture is a
   * diagnostic side-channel that must never be what fails a trail, and the device figures are
   * still worth having. It is logged once so the gap is visible rather than silent.
   */
  private fun usableAppId(appId: String?): String? {
    if (appId == null) return null
    val violation = androidPackageNameViolation(appId) ?: return appId
    if (rejectedAppIds.add(appId)) {
      Console.log("[memory-capture] not sampling an app on this device: $violation")
    }
    return null
  }

  private fun limitsFor(deviceId: String, appId: String): AppMemoryLimits? {
    val key = "$deviceId/$appId"
    if (limitsByApp.containsKey(key)) return limitsByApp[key]
    val out = shell(deviceId, limitsCommand(appId))
    val limits = out?.split(SEPARATOR)?.let { parts ->
      DumpsysMeminfoParser.parseLimits(
        heapGrowthLimit = parts.getOrNull(0),
        heapSize = parts.getOrNull(1),
        packageFlagsLine = parts.getOrNull(2),
      )
    }
    limitsByApp[key] = limits
    return limits
  }

  companion object {
    private const val SHELL_TIMEOUT_MS: Long = 5_000
    internal const val SEPARATOR = "__TBZ_MEM__"
    internal const val GC_OK = "__TBZ_GC_OK__"

    /**
     * One shell script per reading. With an app: its pid, `/proc/meminfo`, and — if it is running —
     * the GC signal (`run-as` first for a debuggable app, then a direct signal for a rooted device),
     * a short settle so ART finishes collecting, and the dump. Without an app: `/proc/meminfo` only.
     */
    internal fun readCommand(appId: String?, forceGc: Boolean): String {
      if (appId == null) return "cat /proc/meminfo"
      // `run-as` for a debuggable app, then a bare signal for a rooted device — same signal and
      // same settle as the on-device runner uses, so `gcForced` means one thing either way.
      val signal = AndroidMemoryReadCommands.gcCommand(appId, "\$pid")
      val gc = if (forceGc) {
        "($signal || kill ${AndroidMemoryReadCommands.GC_SIGNAL} \$pid) >/dev/null 2>&1 && echo $GC_OK; " +
          "sleep ${AndroidMemoryReadCommands.GC_SETTLE_SECONDS}; "
      } else {
        ""
      }
      return "pid=\$(pidof $appId 2>/dev/null); pid=\${pid%% *}; echo \"\$pid\"; echo $SEPARATOR; cat /proc/meminfo; " +
        "if [ -n \"\$pid\" ]; then echo $SEPARATOR; ${gc}echo $SEPARATOR; dumpsys meminfo \$pid; fi"
    }

    internal fun limitsCommand(appId: String): String =
      "getprop dalvik.vm.heapgrowthlimit; echo $SEPARATOR; getprop dalvik.vm.heapsize; echo $SEPARATOR; " +
        "dumpsys package $appId | grep -m1 ' flags='"

    private fun adbShell(deviceId: String, command: String): String? =
      AndroidHostAdbUtils.execAdbShellCommandWithTimeout(
        deviceId = TrailblazeDeviceId(deviceId, TrailblazeDevicePlatform.ANDROID),
        args = listOf(command),
        timeoutMs = SHELL_TIMEOUT_MS,
        quiet = true,
        // The cached adb client is shared per device. Evicting it on this timeout would tear down
        // the transport the running trail is using mid-step, to recover a background sample nobody
        // is waiting for — so a slow reading is simply dropped instead.
        evictClientOnTimeout = false,
      )
  }
}
