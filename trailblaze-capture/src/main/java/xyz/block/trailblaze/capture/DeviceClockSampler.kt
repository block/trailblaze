package xyz.block.trailblaze.capture

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import xyz.block.trailblaze.logs.model.DeviceClockReadFailure
import xyz.block.trailblaze.logs.model.DeviceClockSyncFile
import xyz.block.trailblaze.util.Console

/**
 * Re-measures a device's clock offset through a host-driven session and records every result in
 * the session's `session_device_clock.json` ([DeviceClockSyncFile]).
 *
 * One measurement at the start is not enough: a device clock drifts against the host's over a long
 * run, and NTP or the user can step it. Measuring at start, every [intervalMs], and once more at
 * [stop] brackets the whole run, so a reader can interpolate the offset for any device timestamp
 * instead of applying one stale number. A failed measurement is recorded as a failure, never as an
 * offset of 0.
 */
class DeviceClockSampler(
  private val sessionDir: File,
  private val deviceId: String,
  private val intervalMs: Long = DEFAULT_INTERVAL_MS,
  private val measure: () -> DeviceClock.Measurement = { DeviceClock.measure(deviceId) },
) {
  private var executor: ScheduledExecutorService? = null

  @Synchronized
  fun start() {
    if (executor != null) return
    executor =
      Executors.newSingleThreadScheduledExecutor { runnable ->
          Thread(runnable, "device-clock-sampler-$deviceId").apply { isDaemon = true }
        }
        .also {
          // One-shot, so a stop() before the thread gets to it still runs it (shutdown cancels
          // only the periodic task).
          it.execute(::sampleOnce)
          it.scheduleWithFixedDelay(::sampleOnce, intervalMs, intervalMs, TimeUnit.MILLISECONDS)
        }
  }

  /**
   * Stops sampling, after one final measurement so the samples bracket the end of the run. Bounded
   * by [STOP_TIMEOUT_MS] plus one [DeviceClock.measure]. An interrupt (a shutdown hook giving up on
   * this session) skips the final read and records why instead.
   */
  @Synchronized
  fun stop() {
    val running = executor ?: return
    executor = null
    running.shutdown()
    try {
      if (!running.awaitTermination(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
        // A read still in flight past the wait: it brackets the run itself if it lands, and a
        // second read queued behind a wedged transport would only add to the stop.
        running.shutdownNow()
        recordFailure("final clock read skipped: a read was still running after ${STOP_TIMEOUT_MS}ms")
        return
      }
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
      running.shutdownNow()
      recordFailure("final clock read skipped: interrupted while stopping")
      return
    }
    sampleOnce()
  }

  internal fun sampleOnce() {
    try {
      when (val measurement = measure()) {
        is DeviceClock.Measurement.Measured ->
          DeviceClockSyncFile.appendSample(sessionDir, deviceId, measurement.best)
        is DeviceClock.Measurement.Failed -> recordFailure(measurement.error)
      }
    } catch (e: Exception) {
      // A sampler must never take the session down with it.
      Console.log("[device-clock] $deviceId: could not record a clock sample: ${e.message}")
    }
  }

  private fun recordFailure(error: String) {
    Console.log("[device-clock] $deviceId: $error")
    runCatching {
        DeviceClockSyncFile.appendFailure(
          sessionDir,
          deviceId,
          DeviceClockReadFailure(System.currentTimeMillis(), error),
        )
      }
      .onFailure { Console.log("[device-clock] $deviceId: could not record a clock failure: ${it.message}") }
  }

  companion object {
    const val DEFAULT_INTERVAL_MS: Long = 60_000L
    private const val STOP_TIMEOUT_MS: Long = 5_000L
  }
}
