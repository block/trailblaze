package xyz.block.trailblaze.host.ios

import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.driver.HostDeviceInventory

/**
 * Offers every booted simulator in [this] inventory under [driverType].
 *
 * Shared because the udid IS the address a `--device` flag resolves against: two descriptors
 * deriving it separately could drift, and then the same simulator would be addressable under one
 * iOS driver and not the other.
 */
internal fun HostDeviceInventory.iosSimulatorDevices(
  driverType: TrailblazeDriverType,
): List<TrailblazeConnectedDeviceSummary> = bootedIosSimulators.map { simulator ->
  TrailblazeConnectedDeviceSummary(
    trailblazeDriverType = driverType,
    instanceId = simulator.udid,
    description = simulator.name,
  )
}
