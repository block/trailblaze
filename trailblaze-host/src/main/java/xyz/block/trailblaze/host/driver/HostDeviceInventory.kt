package xyz.block.trailblaze.host.driver

import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary

/**
 * The shared transports the host enumerates once per discovery pass, handed to every
 * [HostDriverDescriptor.discoverDevices] call.
 *
 * Several drivers can offer themselves on the same physical device — one connected Android device
 * is offered under three different execution engines. If each of their descriptors ran its own
 * `adb devices`, discovery would pay for the same enumeration once per driver, so the device
 * manager enumerates each transport exactly once and descriptors map from the result.
 *
 * A descriptor whose devices don't live on any of these transports (a cloud farm reached through
 * its own CLI, say) ignores the inventory and probes on its own — bounded, see `HostProbe`.
 */
class HostDeviceInventory(
  val adbDevices: List<ConnectedAdbDevice> = emptyList(),
  val bootedIosSimulators: List<BootedIosSimulator> = emptyList(),
  /**
   * The web browsers the host's own `WebBrowserManager` is running this instant. Not a transport
   * enumeration like the fields above — it is live host state — but it belongs here for the same
   * reason: the manager already knows it, and a descriptor re-deriving it would need a reference
   * back into the manager. Already summary-shaped because the browser slot IS the device (there
   * is no rawer row to hand over).
   */
  val runningWebBrowsers: List<TrailblazeConnectedDeviceSummary> = emptyList(),
) {
  companion object {
    /** Nothing enumerated — what a descriptor that owns its transport effectively sees anyway. */
    val EMPTY = HostDeviceInventory()
  }
}

/** One `device`-state serial from `adb devices`, with its cosmetic human-readable name. */
class ConnectedAdbDevice(
  val serial: String,
  val description: String,
)

/** One booted simulator from `simctl list devices booted`. */
class BootedIosSimulator(
  val udid: String,
  val name: String,
)
