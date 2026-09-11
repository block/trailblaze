package xyz.block.trailblaze.host.android

import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.driver.DeviceListingVisibility
import xyz.block.trailblaze.host.driver.HostDriverDescriptor
import xyz.block.trailblaze.host.driver.HostDeviceInventory
import xyz.block.trailblaze.host.driver.HostScreenStateDeps

/**
 * Plugs the in-process driver into the host: trails run inside the target app's own
 * instrumentation test, and the host drives them over the RPC server that test hosts.
 *
 * [HostDriverDescriptor.OnDeviceTools]: tools execute on the device.
 */
class AndroidTestHostDriverDescriptor : HostDriverDescriptor.OnDeviceTools {

  override val driverTypes: Set<TrailblazeDriverType> = setOf(TrailblazeDriverType.ANDROID_TEST)

  override val listingVisibility = DeviceListingVisibility.LISTED

  /**
   * Every connected device, like the other two Android drivers — NOT only devices whose selected
   * target declares an in-process harness.
   *
   * Whether the target declares one (and whether its APK is installed) is checked at
   * dispatch/connect time, where the error can name the missing declaration. Filtering here
   * instead would drop the device from the list with nothing to explain why, and the answer would
   * change as the user switched targets without a new discovery pass.
   */
  override suspend fun discoverDevices(inventory: HostDeviceInventory): List<TrailblazeConnectedDeviceSummary> =
    inventory.adbConnectedDevices(TrailblazeDriverType.ANDROID_TEST)

  /**
   * Captures the same way as the other Android drivers: the in-process harness hosts the same RPC
   * server, and capture stays live even while a trail occupies its instrumentation test thread.
   * The transport differs — this driver is not `protoWireSafe` — and [onDeviceRpcScreenState]
   * derives that from the driver type.
   */
  override suspend fun screenState(
    driverType: TrailblazeDriverType,
    deviceId: TrailblazeDeviceId,
    deps: HostScreenStateDeps,
  ): ScreenState? = onDeviceRpcScreenState(deviceId, driverType)
}
