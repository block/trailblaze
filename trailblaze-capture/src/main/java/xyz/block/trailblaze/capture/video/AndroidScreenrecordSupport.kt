package xyz.block.trailblaze.capture.video

import java.util.concurrent.ConcurrentHashMap
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.util.AndroidHostAdbUtils
import xyz.block.trailblaze.util.Console

/**
 * Whether a device can record its own screen — i.e. whether its firmware ships `screenrecord`.
 *
 * Most Android builds do. Some OEM images do not: measured on a vendor image
 * that carries `screencap` and nothing else. Asking such a device to record is silent rather than
 * loud, which is why this probe exists at all:
 *  - the shell's `screenrecord: inaccessible or not found` goes to **stdout**, so it lands inside
 *    the H.264 stream instead of anywhere a reader would look;
 *  - the command still exits **0**;
 *  - ffmpeg creates its output file when it is spawned, so the session ends holding a recording
 *    that is zero bytes long.
 *
 * The answer is cached per serial for the life of the process — a device's firmware does not grow
 * a binary mid-session — so the round trip is paid once per device, not once per session.
 */
internal object AndroidScreenrecordSupport {

  private val cache = ConcurrentHashMap<String, Boolean>()

  /**
   * True when [deviceId] has a `screenrecord` binary, so [AndroidVideoCapture] can use it.
   *
   * **Biased toward yes.** A probe that throws, times out, or answers something unrecognized
   * reports available, because the cost of being wrong is asymmetric: a false "missing" downgrades
   * every healthy device to the far more expensive screencap fallback, while a false "present"
   * just reproduces today's behavior on the handful of images that lack it.
   */
  fun isAvailable(
    deviceId: TrailblazeDeviceId,
    probe: (TrailblazeDeviceId) -> String? = ::probeDevice,
  ): Boolean = cache.getOrPut(deviceId.instanceId) {
    interpret(probe(deviceId)).also { available ->
      if (!available) {
        Console.log(
          "[AndroidScreenrecordSupport] ${deviceId.instanceId} has no screenrecord binary; " +
            "video will be recorded by polling screencap instead",
        )
      }
    }
  }

  /**
   * Reads the probe's output: available when some line *is* a path to the binary.
   *
   * Matching on the whole line rather than a substring is what separates the two answers — a
   * device that has it prints `/system/bin/screenrecord`, and a device that does not prints
   * `ls: /system/bin/screenrecord: No such file or directory`, which mentions the same path.
   */
  internal fun interpret(probeOutput: String?): Boolean {
    if (probeOutput == null) return true
    return probeOutput.lineSequence().any { line ->
      val trimmed = line.trim()
      trimmed.endsWith("/screenrecord") || trimmed == "screenrecord"
    }
  }

  /**
   * Asks the device where its `screenrecord` is.
   *
   * `command -v` is the portable question and answers from `PATH`, so an image that keeps the
   * binary somewhere other than `/system/bin` still reports it. The `ls` is there for a shell
   * whose `command` builtin is missing or restricted; between them a false negative needs both to
   * fail on a device that does have the binary. Returns null when the round trip itself failed,
   * which [interpret] reads as "assume it is there".
   */
  private fun probeDevice(deviceId: TrailblazeDeviceId): String? = try {
    AndroidHostAdbUtils.execAdbShellCommandWithTimeout(
      deviceId = deviceId,
      args = listOf("command", "-v", "screenrecord", "||", "ls", "/system/bin/screenrecord"),
      timeoutMs = PROBE_TIMEOUT_MS,
    )
  } catch (_: Exception) {
    null
  }

  /** Test seam: forget what every device answered, so a test starts clean. */
  internal fun resetCacheForTests() = cache.clear()

  private const val PROBE_TIMEOUT_MS = 5_000L
}
