package xyz.block.trailblaze.host.devices

import maestro.Maestro
import maestro.device.Device
import maestro.device.DeviceService
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.screenstate.toTrailblazeDevicePlatform

object TrailblazeDeviceService {

  fun getConnectedIosDevice(): TrailblazeConnectedDevice? {
    val connectedDevices: List<Device.Connected> = DeviceService.listConnectedDevices()
      .filter { it.platform.toTrailblazeDevicePlatform() == TrailblazeDevicePlatform.IOS }
    val connectedDevice = connectedDevices.firstOrNull() ?: return null
    val iosDriver: Maestro = HostIosDriverFactory.createIOS(
      deviceId = connectedDevice.instanceId,
      openDriver = true,
      reinstallDriver = false,
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
      .filter { it.platform.toTrailblazeDevicePlatform() == TrailblazeDevicePlatform.ANDROID }
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
