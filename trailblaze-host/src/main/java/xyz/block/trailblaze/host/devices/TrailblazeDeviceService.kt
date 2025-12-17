package xyz.block.trailblaze.host.devices

import maestro.Maestro
import maestro.device.Device
import maestro.device.DeviceService
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.screenstate.toTrailblazeDevicePlatform
import xyz.block.trailblaze.model.TrailblazeHostAppTarget

object TrailblazeDeviceService {

  /**
   * Gets the first connected iOS Device.
   *
   * @param appTarget Optional - Configuration for the target application under test
   */
  fun getConnectedIosDevice(
    appTarget: TrailblazeHostAppTarget? = null,
  ): TrailblazeConnectedDevice? {
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
      appTarget = appTarget,
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

  fun getConnectedDevice(
    platform: TrailblazeDevicePlatform,
    appTarget: TrailblazeHostAppTarget? = null,
  ): TrailblazeConnectedDevice? = when (platform) {
    TrailblazeDevicePlatform.ANDROID -> getConnectedHostAndroidDevice()
    TrailblazeDevicePlatform.IOS -> getConnectedIosDevice(appTarget)
    TrailblazeDevicePlatform.WEB -> HostWebDriverFactory().createWeb()
  }
}
