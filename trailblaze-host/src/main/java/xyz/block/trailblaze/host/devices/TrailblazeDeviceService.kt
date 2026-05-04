package xyz.block.trailblaze.host.devices

import maestro.Maestro
import maestro.device.Device
import maestro.device.DeviceService
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDevicePort.getMaestroOnDeviceSpecificPort
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.axe.AxeCli
import xyz.block.trailblaze.host.axe.AxeJsonMapper
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
   * Gets the first connected iOS Device backed by the Maestro/XCUITest driver.
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
    return MaestroConnectedDevice(
      maestroDriver = iosDriver.driver,
      trailblazeDriverType = TrailblazeDriverType.IOS_HOST,
      instanceId = connectedDevice.instanceId,
    )
  }

  /**
   * Gets a connected iOS Simulator via the AXe CLI. Simulator-only by design — AXe uses
   * Apple's private Accessibility APIs which are not available on real devices.
   */
  fun getConnectedIosAxeDevice(trailblazeDeviceId: TrailblazeDeviceId): TrailblazeConnectedDevice? {
    if (!AxeCli.isAvailable()) {
      System.err.println("axe binary not found. Install it with: brew install cameroncooke/axe/axe")
      return null
    }
    val udid = trailblazeDeviceId.instanceId

    // Bounds come from the AXe root `AXApplication` frame — the `application` element is
    // sized to the screen. Cheaper than calling `xcrun simctl` and doesn't require an
    // extra subprocess.
    val describe = AxeCli.describeUi(udid)
    if (!describe.success) {
      System.err.println("[IOS_AXE] axe describe-ui failed for $udid: ${describe.stderr.trim()}")
      return null
    }
    val tree = try {
      AxeJsonMapper.parse(describe.stdout)
    } catch (e: Exception) {
      System.err.println("[IOS_AXE] axe describe-ui produced unparseable JSON for $udid: ${e.message}")
      return null
    }
    val bounds = tree.bounds ?: run {
      System.err.println("[IOS_AXE] axe describe-ui returned no bounds for root — can't resolve device size")
      return null
    }

    return AxeConnectedDevice(
      udid = udid,
      deviceWidth = bounds.width,
      deviceHeight = bounds.height,
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

  fun getConnectedDevice(
    trailblazeDeviceId: TrailblazeDeviceId,
    driverType: TrailblazeDriverType,
    appTarget: TrailblazeHostAppTarget? = null,
  ): TrailblazeConnectedDevice? = when (trailblazeDeviceId.trailblazeDevicePlatform) {
    TrailblazeDevicePlatform.ANDROID -> {
      // Android drivers communicate via RPC and do not need a Maestro host driver.
      null
    }
    TrailblazeDevicePlatform.IOS -> when (driverType) {
      TrailblazeDriverType.IOS_AXE -> getConnectedIosAxeDevice(trailblazeDeviceId)
      else -> getConnectedIosDevice(
        trailblazeDeviceId = trailblazeDeviceId,
        appTarget = appTarget,
      )
    }

    TrailblazeDevicePlatform.WEB -> error(
      "Web tests use PLAYWRIGHT_NATIVE path (BasePlaywrightNativeTest), not TrailblazeDeviceService"
    )

    // Compose desktop driver communicates via ComposeRpcClient, not via the
    // Maestro/host-driver fan-out below; same shape as ANDROID (null = no Maestro
    // device backing this CLI invocation, the RPC path takes over).
    TrailblazeDevicePlatform.DESKTOP -> null
  }
}
