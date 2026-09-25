package xyz.block.trailblaze.capture.memory

/**
 * Where a [MemoryCapture] gets its readings. One implementation per way of reaching the app:
 * [AdbMemoryProbe] (`dumpsys` over adb — needs nothing installed), the host's on-device RPC probe
 * (asks the Trailblaze instrumentation runner, which reads the app's own `Runtime` when it shares
 * the process and needs no adb), and [IosSimulatorMemoryProbe] (`footprint` on the simulator's host
 * process).
 *
 * A probe returns a full [MemorySnapshot] minus the clock — the capture stamps the time and decides
 * whether the reading is worth an event.
 */
interface MemoryProbe {
  /**
   * Reads the app's memory once.
   *
   * @param deviceId the device the session runs on (adb serial, simulator UDID).
   * @param appId the app under test, or null to read device-wide memory only.
   * @param forceGc ask the app to collect garbage right before the reading when the probe can, so
   *   the heap figures are live objects rather than live objects plus uncollected garbage. Probes
   *   report what actually happened in [MemorySnapshot.gcForced].
   * @return the reading, or null when the device could not be read at all.
   */
  fun read(deviceId: String, appId: String?, forceGc: Boolean): MemorySnapshot?

  /** Releases anything the probe holds open (an RPC client, a cached limits lookup). */
  fun close() {}

  companion object {
    const val SOURCE_ADB = "adb"
    const val SOURCE_ONDEVICE = "ondevice"
    const val SOURCE_SIMULATOR = "simulator"
  }
}
