package xyz.block.trailblaze.host.android

import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.driver.HostDeviceInventory

/**
 * Every `adb`-connected device, offered under [driverType].
 *
 * Shared by the Android descriptors because each one offers itself on the same devices — the
 * question "which devices can this driver drive" has one answer for all of them, and the
 * enumeration behind it is paid for once per pass by the device manager.
 *
 * Keyed on the serial rather than the description: the serial is what `adb -s` addresses, and
 * `TrailblazeDeviceId` is built from it. The description is cosmetic, and two emulators can share
 * one.
 */
internal fun HostDeviceInventory.adbConnectedDevices(
  driverType: TrailblazeDriverType,
): List<TrailblazeConnectedDeviceSummary> = adbDevices.map { device ->
  TrailblazeConnectedDeviceSummary(
    trailblazeDriverType = driverType,
    instanceId = device.serial,
    description = device.description,
  )
}
