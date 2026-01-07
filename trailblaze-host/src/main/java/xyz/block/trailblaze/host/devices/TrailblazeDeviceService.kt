package xyz.block.trailblaze.host.devices

import maestro.Maestro
import maestro.device.Device
import maestro.device.DeviceService
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDevicePort.getMaestroOnDeviceSpecificPort
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
    trailblazeDeviceId: TrailblazeDeviceId,
    appTarget: TrailblazeHostAppTarget? = null,
  ): TrailblazeConnectedDevice? {
    val connectedDevice: Device.Connected = DeviceService.listConnectedDevices().firstOrNull {
      TrailblazeDeviceId(
        instanceId = it.instanceId,
        trailblazeDevicePlatform = it.platform.toTrailblazeDevicePlatform(),
      ) == trailblazeDeviceId
    } ?: return null
    val iosDriver: Maestro = HostIosDriverFactory.createIOS(
      deviceId = connectedDevice.instanceId,
      openDriver = true,
      reinstallDriver = false,
      deviceType = connectedDevice.deviceType,
      driverHostPort = trailblazeDeviceId.getMaestroOnDeviceSpecificPort(),
      platformConfiguration = null,
      appTarget = appTarget,
    )
    return TrailblazeConnectedDevice(
      maestroDriver = iosDriver.driver,
      trailblazeDriverType = TrailblazeDriverType.IOS_HOST,
      instanceId = connectedDevice.instanceId,
    )
  }

  fun listConnectedTrailblazeDevices(): Set<TrailblazeDeviceId> {
    return DeviceService.listConnectedDevices().map {
      TrailblazeDeviceId(
        instanceId = it.instanceId,
        trailblazeDevicePlatform = it.platform.toTrailblazeDevicePlatform(),
      )
    }.toSet()
  }

  fun getConnectedHostAndroidDevice(trailblazeDeviceId: TrailblazeDeviceId): TrailblazeConnectedDevice? {
    val connectedDevice = listConnectedTrailblazeDevices().firstOrNull { connectedTrailblazeDeviceId ->
      trailblazeDeviceId == connectedTrailblazeDeviceId
    } ?: return null
    val androidDriver: Maestro = HostAndroidDriverFactory.createAndroid(
      instanceId = connectedDevice.instanceId,
      openDriver = true,
      driverHostPort = trailblazeDeviceId.getMaestroOnDeviceSpecificPort(),
    )
    return TrailblazeConnectedDevice(
      maestroDriver = androidDriver.driver,
      trailblazeDriverType = TrailblazeDriverType.ANDROID_HOST,
      instanceId = connectedDevice.instanceId,
    )
  }

  fun getConnectedDevice(
    trailblazeDeviceId: TrailblazeDeviceId,
    appTarget: TrailblazeHostAppTarget? = null,
  ): TrailblazeConnectedDevice? = when (trailblazeDeviceId.trailblazeDevicePlatform) {
    TrailblazeDevicePlatform.ANDROID -> getConnectedHostAndroidDevice(trailblazeDeviceId)
    TrailblazeDevicePlatform.IOS -> getConnectedIosDevice(
      trailblazeDeviceId = trailblazeDeviceId,
      appTarget = appTarget
    )

    TrailblazeDevicePlatform.WEB -> HostWebDriverFactory().createWeb()
  }
}
