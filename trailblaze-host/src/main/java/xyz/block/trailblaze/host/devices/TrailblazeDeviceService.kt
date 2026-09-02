package xyz.block.trailblaze.host.devices

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import maestro.Driver
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
    // One owner's lease on the shared cached driver, not the driver itself: closing it releases this
    // connection's hold and the XCUITest connection survives for whoever else is still driving the
    // device - see [HostIosDriverFactory.Cached].
    val iosDriver: Driver = HostIosDriverFactory.createIOS(
      deviceId = connectedDevice.instanceId,
      openDriver = true,
      reinstallDriver = false,
      deviceType = connectedDevice.deviceType,
      driverHostPort = trailblazeDeviceId.getMaestroOnDeviceSpecificPort(),
      platformConfiguration = null,
      appTarget = appTarget,
    )
    return try {
      MaestroConnectedDevice(
        maestroDriver = iosDriver,
        trailblazeDriverType = TrailblazeDriverType.IOS_HOST,
        instanceId = connectedDevice.instanceId,
      )
    } catch (e: Throwable) {
      // MaestroConnectedDevice reads deviceInfo() in its constructor, which is a live XCUITest
      // call. If it throws, the lease above is held by a caller that never received it, and
      // nothing left could ever release it.
      iosDriver.close()
      throw e
    }
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

  /**
   * [getConnectedDevice] off the caller's thread, releasing the connection if the caller is
   * cancelled before the result reaches it.
   *
   * `withContext` guarantees prompt cancellation, so a device it finished building for a coroutine
   * that has since been cancelled is discarded rather than returned. On iOS that discarded value
   * carries this connection's hold on the shared driver, and no caller ever receives a handle to
   * release it - see [HostIosDriverFactory]. Assigning into a var the cancellation path can still
   * read is what keeps the hold reachable.
   */
  suspend fun connectDevice(
    trailblazeDeviceId: TrailblazeDeviceId,
    driverType: TrailblazeDriverType,
    appTarget: TrailblazeHostAppTarget? = null,
  ): TrailblazeConnectedDevice? {
    var connected: TrailblazeConnectedDevice? = null
    try {
      withContext(Dispatchers.IO) {
        connected = getConnectedDevice(
          trailblazeDeviceId = trailblazeDeviceId,
          driverType = driverType,
          appTarget = appTarget,
        )
      }
    } catch (e: CancellationException) {
      (connected as? MaestroConnectedDevice)?.getMaestroDriver()?.close()
      throw e
    }
    return connected
  }

  fun listConnectedTrailblazeDevices(): Set<TrailblazeDeviceId> {
    return cachedConnectedDevices.map {
      TrailblazeDeviceId(
        instanceId = it.instanceId,
        trailblazeDevicePlatform = it.platform.toTrailblazeDevicePlatform(),
      )
    }.toSet()
  }

  /**
   * The per-driver connection factory. A new host-native iOS driver (one declaring
   * [TrailblazeDriverType.hostNativeSimulatorDriver]) adds a branch here — probe
   * availability, resolve device dimensions, and return its [IosNativeConnectedDevice]
   * subclass — plus an availability-probe branch in the device-discovery pass
   * (`TrailblazeDeviceManager.loadDevicesSuspendImpl`), which decides whether the driver
   * is listed per simulator. Everything downstream is polymorphic over that device.
   */
  fun getConnectedDevice(
    trailblazeDeviceId: TrailblazeDeviceId,
    driverType: TrailblazeDriverType,
    appTarget: TrailblazeHostAppTarget? = null,
  ): TrailblazeConnectedDevice? = when (trailblazeDeviceId.trailblazeDevicePlatform) {
    TrailblazeDevicePlatform.ANDROID -> {
      // Android drivers (instrumentation + accessibility) communicate via the on-device RPC
      // server (`OnDeviceRpcClient` over a dadb-bridged HTTP port), not via a host-side
      // Maestro driver. There's no `MaestroConnectedDevice` to construct here — the
      // recording tab's Android path takes a different shape that doesn't go through this
      // method. Returning null is the correct contract for "no host-side Maestro device";
      // the recording tab interprets that and routes through the on-device path instead.
      null
    }
    TrailblazeDevicePlatform.IOS -> when {
      driverType == TrailblazeDriverType.IOS_AXE -> getConnectedIosAxeDevice(trailblazeDeviceId)
      // Fail closed: a host-native driver with no branch above would otherwise fall through to
      // the Maestro device and cast-fail far from the real mistake.
      driverType.hostNativeSimulatorDriver -> error(
        "No connection factory for host-native iOS driver $driverType — " +
          "add a branch in TrailblazeDeviceService.getConnectedDevice",
      )
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
