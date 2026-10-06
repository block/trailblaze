package xyz.block.trailblaze.host.devices

import kotlin.test.Test
import kotlin.test.assertEquals
import maestro.device.Device
import maestro.device.DeviceSpec
import maestro.device.Platform
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform

class TrailblazeDeviceServiceSupportedDevicesTest {

  @Test
  fun `physical iPhones are left out of the device listing`() {
    val simulator = device("SIM-1", Platform.IOS, Device.DeviceType.SIMULATOR)
    val iphone = device("IPHONE-1", Platform.IOS, Device.DeviceType.REAL)
    val phone = device("ANDROID-1", Platform.ANDROID, Device.DeviceType.REAL)
    val emulator = device("emulator-5554", Platform.ANDROID, Device.DeviceType.EMULATOR)

    val supported = TrailblazeDeviceService.supportedDevices(listOf(simulator, iphone, phone, emulator))

    assertEquals(listOf(simulator, phone, emulator), supported)
  }

  // Android discovery comes from adb, not Maestro, so these pin that both sources reach the listing
  // and neither drops the other.
  private val simulator = device("SIM-1", Platform.IOS, Device.DeviceType.SIMULATOR)
  private val iphone = device("IPHONE-1", Platform.IOS, Device.DeviceType.REAL)
  private val emulator = TrailblazeDeviceId("emulator-5554", TrailblazeDevicePlatform.ANDROID)
  private val androidPhone = TrailblazeDeviceId("R5CT1234", TrailblazeDevicePlatform.ANDROID)
  private val simulatorId = TrailblazeDeviceId("SIM-1", TrailblazeDevicePlatform.IOS)

  @Test
  fun `the listing combines iOS simulators with every adb device`() {
    val listed = TrailblazeDeviceService.combineConnectedDevices(
      iosDevices = listOf(simulator, iphone),
      androidDevices = listOf(emulator, androidPhone),
    )

    assertEquals(setOf(simulatorId, emulator, androidPhone), listed)
  }

  @Test
  fun `Android devices are listed when no iOS device is connected`() {
    val listed = TrailblazeDeviceService.combineConnectedDevices(
      iosDevices = emptyList(),
      androidDevices = listOf(emulator, androidPhone),
    )

    assertEquals(setOf(emulator, androidPhone), listed)
  }

  @Test
  fun `iOS simulators are listed when adb reports nothing`() {
    val listed = TrailblazeDeviceService.combineConnectedDevices(
      iosDevices = listOf(simulator, iphone),
      androidDevices = emptyList(),
    )

    assertEquals(setOf(simulatorId), listed)
  }

  @Test
  fun `nothing connected lists nothing`() {
    assertEquals(
      emptySet(),
      TrailblazeDeviceService.combineConnectedDevices(iosDevices = emptyList(), androidDevices = emptyList()),
    )
  }

  // The filter reads only platform and device type, so one placeholder spec serves every fixture.
  private fun device(id: String, platform: Platform, type: Device.DeviceType) = Device.Connected(
    instanceId = id,
    deviceSpec = DeviceSpec.Ios(model = "iPhone 17 Pro", os = "iOS-26-0"),
    description = id,
    platform = platform,
    deviceType = type,
  )
}
