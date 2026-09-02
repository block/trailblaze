package xyz.block.trailblaze.host.driver

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeoutOrNull
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.util.Console
import kotlin.time.TimeSource

/**
 * Runs every plugged-in driver's device discovery for one pass.
 *
 * Descriptors run concurrently — the host's own transports are enumerated in parallel with
 * per-transport budgets, and plugged-in drivers get the same treatment rather than queuing behind
 * each other. And they are contained: descriptors are the extension point, so one that throws or
 * hangs costs the user that driver's devices, never the whole device list. This mirrors how the
 * built-in transports already degrade (a failed `adb devices` logs and contributes nothing).
 */
object DescriptorDeviceDiscovery {

  /**
   * Hang containment, not a performance budget: generous enough that any sane probe (Revyl's
   * catalog probe self-bounds at 10s) finishes with room to spare, and matching the largest
   * budget the manager grants a built-in transport (the 60s `simctl` enumeration).
   */
  const val DISCOVERY_TIMEOUT_MS: Long = 60_000

  suspend fun discoverAll(
    descriptors: Set<HostDriverDescriptor>,
    inventory: HostDeviceInventory,
    timeoutMs: Long = DISCOVERY_TIMEOUT_MS,
    log: (String) -> Unit = { Console.log(it) },
  ): List<TrailblazeConnectedDeviceSummary> {
    // The workers deliberately escape structured concurrency. A timeout wrapped around the
    // descriptor itself is cooperative — a plug-in stuck in a blocking call (a subprocess wait, a
    // `Future.get`) never reaches a suspension point, so that timeout could only fire when the
    // call finally returned. Bounding the AWAIT instead means a worker that blows the budget is
    // abandoned to finish (or stay blocked) on its own IO thread, and the pass moves on.
    val workers = CoroutineScope(Dispatchers.IO + SupervisorJob())
    try {
      val results = descriptors.map { descriptor ->
        val name = descriptor::class.simpleName ?: "descriptor"
        name to workers.async {
          try {
            descriptor.discoverDevices(inventory)
          } catch (e: Exception) {
            log("[loadDevices] $name discovery failed: ${e.message}; skipping its devices")
            emptyList()
          }
        }
      }

      // One deadline for the pass. Every worker started at ~t0, so each effectively gets the full
      // budget — but awaiting sequentially against the shared deadline means N hung plug-ins cost
      // one budget, not N stacked ones.
      val start = TimeSource.Monotonic.markNow()
      return results.flatMap { (name, deferred) ->
        val remainingMs = (timeoutMs - start.elapsedNow().inWholeMilliseconds).coerceAtLeast(1)
        withTimeoutOrNull(remainingMs) { deferred.await() } ?: run {
          log("[loadDevices] $name discovery timed out after ${timeoutMs}ms; skipping its devices")
          emptyList()
        }
      }
    } finally {
      // No-op when everything finished; tells cooperative stragglers to stop. Non-cooperative
      // ones are already abandoned — that's the containment, not a leak to fix here.
      workers.cancel()
    }
  }
}
