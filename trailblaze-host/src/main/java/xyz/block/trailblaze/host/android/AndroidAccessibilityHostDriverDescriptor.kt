package xyz.block.trailblaze.host.android

import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.driver.DeviceListingVisibility
import xyz.block.trailblaze.host.driver.HostDeviceInventory
import xyz.block.trailblaze.host.driver.HostDriverDescriptor
import xyz.block.trailblaze.host.driver.HostScreenStateDeps

/**
 * Plugs the on-device accessibility driver — the Android default — into the host.
 *
 * [HostDriverDescriptor.OnDeviceTools] because this driver executes its tools on the device, so
 * dispatch reaches it over the on-device RPC server and never through the host run path.
 */
class AndroidAccessibilityHostDriverDescriptor : HostDriverDescriptor.OnDeviceTools {

  override val driverTypes: Set<TrailblazeDriverType> =
    setOf(TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY)

  override val listingVisibility = DeviceListingVisibility.LISTED

  /**
   * Every connected device, unconditionally. The driver ships inside the on-device runtime the
   * host installs, so there is nothing to probe for on the host side.
   */
  override suspend fun discoverDevices(inventory: HostDeviceInventory): List<TrailblazeConnectedDeviceSummary> =
    inventory.adbConnectedDevices(TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY)

  override suspend fun screenState(
    driverType: TrailblazeDriverType,
    deviceId: TrailblazeDeviceId,
    deps: HostScreenStateDeps,
  ): ScreenState? = onDeviceRpcScreenState(deviceId, driverType)
}
