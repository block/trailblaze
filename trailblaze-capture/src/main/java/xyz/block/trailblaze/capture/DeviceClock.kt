package xyz.block.trailblaze.capture

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.logs.model.DeviceClockSample
import xyz.block.trailblaze.util.AndroidHostAdbUtils
import xyz.block.trailblaze.util.Console

/**
 * Measures an Android device's wall clock against the host's over adb, NTP-style.
 *
 * Emulators and physical devices drift from the host by whole seconds, and anything the device
 * stamps (on-device logs, view-hierarchy captures, an app's own emit times) has to be shifted by that
 * offset to sit on the host timeline. One `adb shell date` read is a poor measurement: the adb round
 * trip is tens of milliseconds and varies, and the read lands somewhere inside it. So [measure]
 * brackets each read with host clock reads, takes a burst of them, and keeps the one with the
 * shortest round trip — its midpoint is the tightest estimate of when the device answered.
 */
object DeviceClock {

  /** Reads per [measure] call. Five is enough for one of them to dodge an adb hiccup. */
  const val DEFAULT_BURST: Int = 5

  /** The outcome of [measure]: a measured offset, or why there isn't one. Never a silent 0. */
  sealed interface Measurement {
    data class Measured(val best: DeviceClockSample, val reads: List<DeviceClockSample>) :
      Measurement

    data class Failed(val error: String) : Measurement
  }

  /**
   * The device's current epoch millis, from one read. Falls back to the host clock, loudly, when
   * adb fails — callers that need to know use [measure].
   */
  fun nowMs(deviceId: String): Long = deviceNowMs(deviceId) ?: System.currentTimeMillis()

  /** The device's current epoch time in milliseconds, or null when adb did not answer with one. */
  fun deviceNowMs(deviceId: String): Long? =
    try {
      read(deviceId).deviceMs
    } catch (e: Exception) {
      Console.log("DeviceClock: failed to query device time, using host clock: ${e.message}")
      null
    }

  /**
   * One read of the device clock, bracketed by host clock reads. Throws when adb fails or takes
   * longer than [timeoutMs]: a wedged transport must cost a clock read seconds, never the
   * minutes-long host-shell bound, because capture stop waits on the final read. The read never
   * evicts the shared adb client, since that would tear down the transport a running trail uses.
   */
  fun read(deviceId: String, timeoutMs: Long = READ_TIMEOUT_MS): DeviceClockSample {
    val hostBeforeMs = System.currentTimeMillis()
    val output =
      abandonedReads.guard(deviceId) { onFinished ->
        AndroidHostAdbUtils.execAdbShellCommandWithTimeout(
          TrailblazeDeviceId(deviceId, TrailblazeDevicePlatform.ANDROID),
          listOf("date", "+%s%3N"),
          timeoutMs = timeoutMs,
          quiet = true,
          evictClientOnTimeout = false,
          onAttemptFinished = onFinished,
        )
      } ?: throw IllegalStateException("`adb shell date` failed or took over ${timeoutMs}ms")
    val hostAfterMs = System.currentTimeMillis()
    val deviceMs =
      output.trim().toLongOrNull()
        ?: throw IllegalStateException("`adb shell date +%s%3N` returned '${output.trim()}', not epoch millis")
    return DeviceClockSample(hostBeforeMs, hostAfterMs, deviceMs)
  }

  /**
   * Takes up to [burst] reads and keeps the one with the shortest round trip. The burst ends at the
   * first failed read, once [budgetMs] has passed, or when the thread is interrupted, so one
   * measurement is bounded by about one read timeout plus [budgetMs]. The measurement fails only
   * when no read succeeded.
   */
  fun measure(
    deviceId: String,
    burst: Int = DEFAULT_BURST,
    budgetMs: Long = BURST_BUDGET_MS,
    nowMs: () -> Long = System::currentTimeMillis,
    read: () -> DeviceClockSample = { read(deviceId) },
  ): Measurement {
    require(burst > 0) { "burst must be positive, was $burst" }
    val deadlineMs = nowMs() + budgetMs
    val reads = mutableListOf<DeviceClockSample>()
    var error: String? = null
    while (reads.size < burst) {
      if (Thread.currentThread().isInterrupted) {
        error = "interrupted"
        break
      }
      try {
        reads += read()
      } catch (e: Exception) {
        error = e.message ?: e.javaClass.simpleName
        break
      }
      if (nowMs() >= deadlineMs) break
    }
    val best =
      DeviceClockSample.tightest(reads)
        ?: return Measurement.Failed("device clock read failed for $deviceId: ${error ?: "unknown error"}")
    return Measurement.Measured(best, reads)
  }

  private val abandonedReads = AbandonedReads()

  /**
   * Refuses a read on a device while an earlier one is still stuck. A timed-out read leaves its
   * worker parked in a socket read that ignores interrupts; without this, a stalled transport
   * would gain one parked worker per sample for as long as it stays stalled. Reads that merely
   * overlap (logcat's start stamp beside the sampler's first burst) still run together.
   */
  internal class AbandonedReads {
    private val stuck = ConcurrentHashMap<String, AtomicInteger>()

    /** [call] gets a callback to run when its worker ends; a null result may leave it running. */
    fun <T : Any> guard(deviceId: String, call: (onFinished: () -> Unit) -> T?): T? {
      check((stuck[deviceId]?.get() ?: 0) == 0) {
        "an earlier clock read on $deviceId is still waiting on adb"
      }
      val finished = AtomicBoolean(false)
      val counter = stuck.computeIfAbsent(deviceId) { AtomicInteger() }
      val result = call { if (!finished.compareAndSet(false, true)) counter.decrementAndGet() }
      if (result == null) {
        // Counted first, so a worker ending right now always finds something to uncount.
        counter.incrementAndGet()
        if (!finished.compareAndSet(false, true)) counter.decrementAndGet()
      }
      return result
    }
  }

  /** One clock read's bound. A healthy `adb shell date` answers in tens of milliseconds. */
  const val READ_TIMEOUT_MS: Long = 3_000L

  /** How long one [measure] keeps taking reads after the first. */
  const val BURST_BUDGET_MS: Long = 2_000L
}
