package xyz.block.trailblaze.host.devices

import java.util.concurrent.ConcurrentHashMap
import maestro.device.Device

/**
 * Finds a connected device for a host-driven run without listing every device on every call.
 *
 * A full listing ([listAll]) runs `simctl list` over every runtime and asks `devicectl` for physical
 * devices, about 1.5–2s, serialized on the CoreSimulator lock. It is reused for [ttlMs]. Past that,
 * a simulator an earlier listing found is confirmed with [bootedSimulatorIds] (`simctl list devices
 * booted`) instead: a UDID never changes type, so a remembered simulator only has to still be
 * booted. An agent usually thinks for longer than [ttlMs] between tool calls, so without this nearly
 * every call paid for a full listing.
 */
internal class ConnectedDeviceLookup(
  private val ttlMs: Long,
  private val listAll: () -> List<Device.Connected>,
  private val bootedSimulatorIds: () -> Collection<String>,
  private val nowMs: () -> Long = System::currentTimeMillis,
) {
  private class Listing(val devices: List<Device.Connected>, val atMs: Long)

  @Volatile private var listing: Listing? = null

  private val knownSimulators = ConcurrentHashMap<String, Device.Connected>()

  private fun freshListing(): List<Device.Connected>? =
    listing?.takeIf { nowMs() - it.atMs <= ttlMs }?.devices

  /** Every connected device, from a listing no older than [ttlMs]. */
  fun all(): List<Device.Connected> = freshListing() ?: listAll().also { devices ->
    listing = Listing(devices, nowMs())
    devices.filter { it.deviceType == Device.DeviceType.SIMULATOR }
      .forEach { knownSimulators[it.instanceId] = it }
  }

  /** The connected device with [instanceId] that satisfies [matches], or null. */
  fun find(instanceId: String, matches: (Device.Connected) -> Boolean): Device.Connected? {
    if (freshListing() == null) {
      val known = knownSimulators[instanceId]?.takeIf(matches)
      // A failed probe falls through to the full listing, which answers the same question.
      if (known != null && runCatching { instanceId in bootedSimulatorIds() }.getOrDefault(false)) {
        return known
      }
    }
    return all().firstOrNull { it.instanceId == instanceId && matches(it) }
  }
}
