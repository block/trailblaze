package xyz.block.trailblaze.host.devices

import maestro.Maestro
import maestro.device.Device
import maestro.device.DeviceService
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDevicePort.getMaestroOnDeviceSpecificPort
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.toTrailblazeDevicePlatform
import xyz.block.trailblaze.model.TrailblazeHostAppTarget

object TrailblazeDeviceService {

  /**
   * Cached connected devices list with time-bounded staleness.
   * Device discovery (`xcrun simctl list`) is expensive (~300-500ms) and serializes
   * on the CoreSimulator database lock, so we cache results for [CACHE_TTL_MS].
   */
  private const val CACHE_TTL_MS = 30_000L

  private data class DeviceCache(
    val devices: List<Device.Connected>,
    val timestamp: Long,
  )

  @Volatile private var cache: DeviceCache? = null

  private val cachedConnectedDevices: List<Device.Connected>
    get() {
      val now = System.currentTimeMillis()
      val current = cache
      if (current == null || now - current.timestamp > CACHE_TTL_MS) {
        return DeviceService.listConnectedDevices().also {
          cache = DeviceCache(it, now)
        }
      }
      return current.devices
    }

  /**
   * Gets the first connected iOS Device.
   *
   * @param appTarget Optional - Configuration for the target application under test
   */
  fun getConnectedIosDevice(
    trailblazeDeviceId: TrailblazeDeviceId,
    appTarget: TrailblazeHostAppTarget? = null,
  ): TrailblazeConnectedDevice? {
    val connectedDevice: Device.Connected = cachedConnectedDevices.firstOrNull {
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
    return cachedConnectedDevices.map {
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
    driverType: TrailblazeDriverType,
    appTarget: TrailblazeHostAppTarget? = null,
  ): TrailblazeConnectedDevice? = when (trailblazeDeviceId.trailblazeDevicePlatform) {
    TrailblazeDevicePlatform.ANDROID -> {
      // On-device Android drivers (ACCESSIBILITY, INSTRUMENTATION) communicate via RPC and
      // do not need a Maestro host driver. Creating one would be wasteful and can interfere
      // with the running on-device service.
      if (driverType in TrailblazeDriverType.ANDROID_ON_DEVICE_DRIVER_TYPES) {
        null
      } else {
        getConnectedHostAndroidDevice(trailblazeDeviceId)
      }
    }
    TrailblazeDevicePlatform.IOS -> getConnectedIosDevice(
      trailblazeDeviceId = trailblazeDeviceId,
      appTarget = appTarget
    )

    TrailblazeDevicePlatform.WEB -> error(
      "Web tests use PLAYWRIGHT_NATIVE path (BasePlaywrightNativeTest), not TrailblazeDeviceService"
    )
  }
}
