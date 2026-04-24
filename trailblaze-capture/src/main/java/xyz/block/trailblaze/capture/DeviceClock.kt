package xyz.block.trailblaze.capture

import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.util.AndroidHostAdbUtils
import xyz.block.trailblaze.util.Console

/**
 * Queries the device clock via `adb shell date` so capture timestamps align with on-device session
 * logs.
 *
 * Android emulators and physical devices may have clocks that differ from the host by several
 * seconds. Session logs use the device clock, so capture metadata must too.
 */
object DeviceClock {
  /**
   * Returns the device's current epoch time in milliseconds, falling back to the host clock if the
   * adb query fails.
   */
  fun nowMs(deviceId: String): Long {
    return try {
      val p =
        AndroidHostAdbUtils.createAdbCommandProcessBuilder(
            args = listOf("shell", "date", "+%s%3N"),
            deviceId = TrailblazeDeviceId(deviceId, TrailblazeDevicePlatform.ANDROID),
          )
          .start()
      val output = p.inputStream.bufferedReader().readText().trim()
      p.waitFor()
      output.toLongOrNull() ?: System.currentTimeMillis()
    } catch (e: Exception) {
      Console.log("DeviceClock: failed to query device time, using host clock: ${e.message}")
      System.currentTimeMillis()
    }
  }
}
