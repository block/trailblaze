package xyz.block.trailblaze.host.devices

import maestro.Maestro
import maestro.device.Device
import maestro.device.DeviceService
import maestro.device.Platform
import xyz.block.trailblaze.devices.TrailblazeDeviceType

object TrailblazeDeviceService {

  private fun Platform.toTrailblazeDeviceType(): TrailblazeDeviceType = when (this) {
    Platform.ANDROID -> TrailblazeDeviceType.ANDROID
    Platform.IOS -> TrailblazeDeviceType.IOS
    Platform.WEB -> TrailblazeDeviceType.WEB
  }

  fun getConnectedIosDevice(): TrailblazeConnectedDevice? {
    val connectedDevices: List<Device.Connected> = DeviceService.listConnectedDevices()
      .filter { it.platform.toTrailblazeDeviceType() == TrailblazeDeviceType.IOS }
    val connectedDevice = connectedDevices.firstOrNull() ?: return null
    val iosDriver: Maestro = HostIosDriverFactory.createIOS(
      deviceId = connectedDevice.instanceId,
      openDriver = true,
      reinstallDriver = true,
      deviceType = connectedDevice.deviceType,
      driverHostPort = null,
      platformConfiguration = null,
    )
    return TrailblazeConnectedDevice(iosDriver.driver)
  }

  fun getConnectedHostAndroidDevice(): TrailblazeConnectedDevice? {
    val connectedDevices: List<Device.Connected> = DeviceService.listConnectedDevices()
      .filter { it.platform.toTrailblazeDeviceType() == TrailblazeDeviceType.ANDROID }
    val connectedDevice = connectedDevices.firstOrNull() ?: return null
    val androidDriver: Maestro = HostAndroidDriverFactory.createAndroid(
      instanceId = connectedDevice.instanceId,
      openDriver = true,
      driverHostPort = null,
    )
    return TrailblazeConnectedDevice(androidDriver.driver)
  }

  fun getFirstConnectedDevice(): TrailblazeConnectedDevice = getConnectedDevice(TrailblazeDeviceType.ANDROID)
    ?: getConnectedDevice(TrailblazeDeviceType.IOS)
    ?: HostWebDriverFactory().createWeb()

  fun getConnectedDevice(platform: TrailblazeDeviceType): TrailblazeConnectedDevice? = when (platform) {
    TrailblazeDeviceType.ANDROID -> getConnectedHostAndroidDevice()
    TrailblazeDeviceType.ANDROID_ONDEVICE -> error("Android on-device is not supported yet in ${this::class.simpleName}.")
    TrailblazeDeviceType.IOS -> getConnectedIosDevice()
    TrailblazeDeviceType.WEB -> HostWebDriverFactory().createWeb()
  }
}
