package xyz.block.trailblaze.host.devices

import maestro.Maestro
import maestro.device.Device
import maestro.device.DeviceService
import maestro.device.Platform
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType

object TrailblazeDeviceService {

  private fun Platform.toTrailblazeDeviceType(): TrailblazeDevicePlatform = when (this) {
    Platform.ANDROID -> TrailblazeDevicePlatform.ANDROID
    Platform.IOS -> TrailblazeDevicePlatform.IOS
    Platform.WEB -> TrailblazeDevicePlatform.WEB
  }

  fun getConnectedIosDevice(): TrailblazeConnectedDevice? {
    val connectedDevices: List<Device.Connected> = DeviceService.listConnectedDevices()
      .filter { it.platform.toTrailblazeDeviceType() == TrailblazeDevicePlatform.IOS }
    val connectedDevice = connectedDevices.firstOrNull() ?: return null
    val iosDriver: Maestro = HostIosDriverFactory.createIOS(
      deviceId = connectedDevice.instanceId,
      openDriver = true,
      reinstallDriver = true,
      deviceType = connectedDevice.deviceType,
      driverHostPort = null,
      platformConfiguration = null,
    )
    return TrailblazeConnectedDevice(
      maestroDriver = iosDriver.driver,
      trailblazeDriverType = TrailblazeDriverType.IOS_HOST,
    )
  }

  fun getConnectedHostAndroidDevice(): TrailblazeConnectedDevice? {
    val connectedDevices: List<Device.Connected> = DeviceService.listConnectedDevices()
      .filter { it.platform.toTrailblazeDeviceType() == TrailblazeDevicePlatform.ANDROID }
    val connectedDevice = connectedDevices.firstOrNull() ?: return null
    val androidDriver: Maestro = HostAndroidDriverFactory.createAndroid(
      instanceId = connectedDevice.instanceId,
      openDriver = true,
      driverHostPort = null,
    )
    return TrailblazeConnectedDevice(
      maestroDriver = androidDriver.driver,
      trailblazeDriverType = TrailblazeDriverType.ANDROID_HOST,
    )
  }

  fun getFirstConnectedDevice(): TrailblazeConnectedDevice = getConnectedDevice(TrailblazeDevicePlatform.ANDROID)
    ?: getConnectedDevice(TrailblazeDevicePlatform.IOS)
    ?: HostWebDriverFactory().createWeb()

  fun getConnectedDevice(platform: TrailblazeDevicePlatform): TrailblazeConnectedDevice? = when (platform) {
    TrailblazeDevicePlatform.ANDROID -> getConnectedHostAndroidDevice()
    TrailblazeDevicePlatform.IOS -> getConnectedIosDevice()
    TrailblazeDevicePlatform.WEB -> HostWebDriverFactory().createWeb()
  }
}
