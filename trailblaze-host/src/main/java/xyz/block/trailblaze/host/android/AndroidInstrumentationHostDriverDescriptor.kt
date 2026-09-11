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
 * Plugs the on-device instrumentation driver into the host — Maestro running ON the device rather
 * than from the host.
 *
 * A separate descriptor from [AndroidAccessibilityHostDriverDescriptor] rather than a second entry
 * in one `driverTypes` set: they are two execution engines offered on the same transport, and the
 * enumeration they would have shared is already shared through [HostDeviceInventory]. Bundling
 * them would make removing one surgery inside a class the other depends on.
 *
 * [HostDriverDescriptor.OnDeviceTools]: tools execute on the device.
 */
class AndroidInstrumentationHostDriverDescriptor : HostDriverDescriptor.OnDeviceTools {

  override val driverTypes: Set<TrailblazeDriverType> =
    setOf(TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION)

  override val listingVisibility = DeviceListingVisibility.LISTED

  override suspend fun discoverDevices(inventory: HostDeviceInventory): List<TrailblazeConnectedDeviceSummary> =
    inventory.adbConnectedDevices(TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION)

  override suspend fun screenState(
    driverType: TrailblazeDriverType,
    deviceId: TrailblazeDeviceId,
    deps: HostScreenStateDeps,
  ): ScreenState? = onDeviceRpcScreenState(deviceId, driverType)
}
