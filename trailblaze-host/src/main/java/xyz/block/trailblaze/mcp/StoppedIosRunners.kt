package xyz.block.trailblaze.mcp

import xyz.block.trailblaze.devices.TrailblazeDeviceId
import java.util.concurrent.ConcurrentHashMap

/**
 * iOS devices whose XCTest runner was found dead under a persistent driver, keyed by device
 * instance id, from the moment they are found dead until the next connect.
 *
 * A persistent driver reads as ready for as long as it is cached, and its runner can be killed from
 * outside it (an orphaned `xcodebuild` relaunching its own runner, a crash, a simulator restart), so
 * every screen read then fails and the tools fall back to "No device connected". Dropping the driver
 * alone is not enough: with nothing cached, the next status read would find nothing wrong, and the
 * CLI would keep reusing the session without the reconnect that rebuilds the runner. So the status
 * stays until [clear].
 */
internal class StoppedIosRunners(
  private val isRunnerReachable: (port: Int) -> Boolean,
) {

  private val statuses = ConcurrentHashMap<String, String>()

  /**
   * Bumped by [clear], so a probe that was already running when a connect started cannot record
   * its stale "stopped" after the connect cleared it, or drop the driver that connect is building.
   */
  private val generations = ConcurrentHashMap<String, Long>()

  /**
   * The status to report for [deviceId], or null when there is nothing wrong. When
   * [persistentDriverPort] exists and nothing answers there, records the runner as stopped and
   * calls [forget] to drop the dead driver. One localhost connect, only to the port that driver
   * was actually built on; null means no persistent driver to vouch for.
   */
  fun check(
    deviceId: TrailblazeDeviceId,
    persistentDriverPort: Int?,
    forget: () -> Unit,
  ): String? {
    val key = deviceId.instanceId
    val generation = generations[key] ?: 0L
    if (persistentDriverPort != null && !isRunnerReachable(persistentDriverPort)) {
      recordAndForgetIfStill(key, generation, stoppedStatus(deviceId, persistentDriverPort), forget)
    }
    return statuses[key]
  }

  /**
   * Records [status] and runs [forget], unless a [clear] ran since [generation] was read. Both run
   * under the lock [clear] takes, so a connect that starts mid-teardown waits for it rather than
   * having the driver it builds torn down by a verdict about the old one.
   */
  private fun recordAndForgetIfStill(key: String, generation: Long, status: String, forget: () -> Unit) {
    synchronized(this) {
      if ((generations[key] ?: 0L) != generation) return
      statuses[key] = status
      forget()
    }
  }

  /** A connect has started, or the association is gone: its own outcome is the status from here. */
  fun clear(key: String): Unit = synchronized(this) {
    generations.merge(key, 1L, Long::plus)
    statuses.remove(key)
  }

  companion object {
    fun stoppedStatus(deviceId: TrailblazeDeviceId, port: Int): String =
      "The iOS XCTest runner on '${deviceId.instanceId}' stopped (nothing answers on port $port). " +
        "Reconnect the device to relaunch it and retry."
  }
}
