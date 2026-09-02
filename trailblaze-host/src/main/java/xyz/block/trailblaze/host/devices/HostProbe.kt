package xyz.block.trailblaze.host.devices

import xyz.block.trailblaze.util.Console
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Bounded blocking probes for device discovery.
 *
 * Discovery fans out to `adb`, `xcrun simctl`, and driver CLIs, any of which can wedge. Every such
 * call goes through [withTimeout] so one unresponsive tool costs a bounded wait instead of hanging
 * the device list.
 */
internal object HostProbe {

  /**
   * Runs a blocking operation with a timeout. Returns null if it times out or fails.
   *
   * Null means "this probe did not answer" — timeout and failure alike. Callers that treat null as
   * an empty result turn a wedged tool into "nothing is connected", so decide which one you mean.
   */
  fun <T> withTimeout(
    timeoutSeconds: Long,
    deviceId: String,
    label: String,
    logPrefix: String = "[loadDevices]",
    block: () -> T,
  ): T? {
    val executor = Executors.newSingleThreadExecutor { r ->
      Thread(r, "device-query-$deviceId").apply { isDaemon = true }
    }
    return try {
      executor.submit(Callable { block() })
        .get(timeoutSeconds, TimeUnit.SECONDS)
    } catch (e: TimeoutException) {
      Console.log("$logPrefix $label for $deviceId TIMED OUT after ${timeoutSeconds}s")
      null
    } catch (e: Exception) {
      Console.log("$logPrefix $label for $deviceId FAILED: ${e.message}")
      null
    } finally {
      executor.shutdownNow()
    }
  }
}
