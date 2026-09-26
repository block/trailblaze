package xyz.block.trailblaze.host.devices

import device.SimctlIOSDevice
import ios.LocalIOSDevice
import ios.devicectl.DeviceControlIOSDevice
import ios.xctest.XCTestIOSDevice
import kotlinx.coroutines.runBlocking
import maestro.device.DeviceOrientation
import maestro.Driver
import maestro.Maestro
import maestro.device.Device
import maestro.drivers.IOSDriver
import maestro.orchestra.WorkspaceConfig
import maestro.utils.CliInsights
import util.IOSDeviceType
import util.XCRunnerCLIUtils
import xcuitest.XCTestClient
import xcuitest.XCTestDriverClient
import xcuitest.installer.Context
import xcuitest.installer.LocalXCTestInstaller
import xcuitest.installer.LocalXCTestInstaller.IOSDriverConfig
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import java.io.File
import java.nio.file.Paths
import kotlin.io.path.pathString
import xyz.block.trailblaze.util.Console

internal object HostIosDriverFactory {

  private val defaultXctestHost = "127.0.0.1"

  /** What a device's cached driver was built for. A connect that needs anything else replaces it. */
  private data class DriverVariant(
    val port: Int,
    val wrapperKey: String?,
  )

  /**
   * One owner's handle on the shared cached driver. Everything delegates; [close] releases this
   * owner's hold rather than tearing down a connection the other owners are still using.
   */
  private class LeasedIosDriver(
    delegate: Driver,
    private val hold: AutoCloseable,
  ) : Driver by delegate {
    override fun close() = hold.close()
  }

  private val portOwners = XcTestPortOwners()

  /**
   * Drivers are cached per device. Each cached driver is independently leased, so closing,
   * replacing, or reconnecting one simulator never tears down another simulator's live connection.
   *
   * A driver built for another port or target wrapper is replaced, and force-closed rather than
   * left to its owners: a wrapper change rebuilds on the very port it holds, and reusing it would
   * drive the app through the wrong wrapper.
   */
  private val drivers = KeyedSharedResourceCache<String, DriverVariant, Driver, Driver>(
    closeResource = { deviceId, variant, driver ->
      try {
        driver.close()
      } catch (e: Exception) {
        Console.log("Failed to close the iOS driver for device $deviceId (already closed?): ${e.message}")
      }
      portOwners.release(port = variant.port, deviceId = deviceId)
    },
    lease = ::LeasedIosDriver,
  )

  /**
   * Identifies the driver a given target would produce, so a cached one is only reused for a target
   * that would have built the same thing. A target with a custom iOS driver wraps the base
   * [IOSDriver] in its own subclass, and that wrapper is a property of the target, not of the
   * device - so device + port alone doesn't identify what is cached.
   *
   * Targets WITHOUT a custom driver all collapse to null, because they all produce the identical
   * base driver: switching between two of them is not a driver change and must not throw away a
   * live XCUITest connection. Same rule `TrailblazeMcpBridgeImpl.selectAppTarget` already applies
   * when it decides whether a target switch has to release the iOS connection.
   */
  fun driverWrapperKey(appTarget: TrailblazeHostAppTarget?): String? =
    appTarget?.takeIf { it.hasCustomIosDriver }?.id

  fun createIOS(
    deviceId: String,
    openDriver: Boolean,
    driverHostPort: Int,
    reinstallDriver: Boolean,
    platformConfiguration: WorkspaceConfig.PlatformConfiguration?,
    deviceType: Device.DeviceType,
    appTarget: TrailblazeHostAppTarget? = null,
  ): Driver {
    val targetPort = driverHostPort
    val wrapperKey = driverWrapperKey(appTarget)
    var created = false
    val driver = drivers.acquireOrCreate(
      key = deviceId,
      variant = DriverVariant(port = targetPort, wrapperKey = wrapperKey),
      isReusable = { driver ->
        // isShutdown() is an in-process flag and stays false when the XCTest runner is reaped
        // externally (SIGKILL, OS reap, crash), so confirm the port still accepts connections.
        val reusable = !driver.isShutdown() &&
          HostDriverPortUtils.isPortReachable(defaultXctestHost, targetPort, timeoutMs = 500)
        if (!reusable) {
          Console.log(
            "Discarding cached iOS driver for device $deviceId - port $targetPort is unreachable " +
              "or the driver is shut down; will create a fresh driver",
          )
        }
        reusable
      },
    ) { lastBuiltVariant ->
      created = true
      val firstInitialization = lastBuiltVariant == null
      // Before anything touches the port: ports are hashed from device ids, so two simulators can
      // share one, and clearing it would kill the other simulator's live runner.
      val staleOwner = portOwners.claim(port = targetPort, deviceId = deviceId) {
        HostDriverPortUtils.isPortReachable(defaultXctestHost, targetPort, timeoutMs = 500)
      }
      try {
        if (staleOwner != null) {
          // Its cached driver would pass isReusable against this device's runner on the shared
          // port and drive the wrong simulator.
          Console.log("Taking XCTest port $targetPort from device $staleOwner, whose runner is gone")
          drivers.evict(staleOwner) { it.port == targetPort }
        }
        Console.log(
          "Creating iOS driver for device $deviceId on port $targetPort with target wrapper " +
            "'${wrapperKey ?: "<none>"}'",
        )
        // A device moved to a new port may find a stale runner listening there, which a wait for
        // release would never clear.
        if (lastBuiltVariant?.port != targetPort) {
          Console.log(
            "Performing initial cleanup for device $deviceId on port $targetPort - killing stale processes",
          )
          HostDriverPortUtils.killProcessesUsingPort(targetPort)
          Thread.sleep(2000)
        } else {
          Console.log("Waiting for device $deviceId port $targetPort to be released before reconnecting")
          HostDriverPortUtils.waitForPortRelease(port = targetPort, timeoutMs = 5000)
        }

        createUncachedIosDriver(
          deviceId = deviceId,
          openDriver = openDriver,
          targetPort = targetPort,
          reinstallDriver = firstInitialization || reinstallDriver,
          platformConfiguration = platformConfiguration,
          deviceType = deviceType,
          appTarget = appTarget,
        ).also { portOwners.connected(port = targetPort, deviceId = deviceId) }
      } catch (e: Throwable) {
        portOwners.release(port = targetPort, deviceId = deviceId)
        throw e
      }
    }

    if (!created) Console.log("Reusing existing iOS driver for device $deviceId on port $targetPort")
    return driver
  }

  private fun createUncachedIosDriver(
    deviceId: String,
    openDriver: Boolean,
    targetPort: Int,
    reinstallDriver: Boolean,
    platformConfiguration: WorkspaceConfig.PlatformConfiguration?,
    deviceType: Device.DeviceType,
    appTarget: TrailblazeHostAppTarget?,
  ): Driver {
    val iOSDeviceType = when (deviceType) {
      Device.DeviceType.REAL -> IOSDeviceType.REAL
      Device.DeviceType.SIMULATOR -> IOSDeviceType.SIMULATOR
      else -> {
        throw UnsupportedOperationException("Unsupported device type $deviceType for iOS platform")
      }
    }
    val iOSDriverConfig = when (deviceType) {
      Device.DeviceType.REAL -> {
        val maestroDirectory = Paths.get(System.getProperty("user.home"), ".maestro")
        val driverPath = maestroDirectory.resolve("maestro-iphoneos-driver-build").resolve("driver-iphoneos")
          .resolve("Build").resolve("Products")
        IOSDriverConfig(
          prebuiltRunner = false,
          sourceDirectory = driverPath.pathString,
          context = Context.CLI,
          snapshotKeyHonorModalViews = platformConfiguration?.ios?.snapshotKeyHonorModalViews,
        )
      }

      Device.DeviceType.SIMULATOR -> {
        IOSDriverConfig(
          prebuiltRunner = false,
          sourceDirectory = "driver-iPhoneSimulator",
          context = Context.CLI,
          snapshotKeyHonorModalViews = platformConfiguration?.ios?.snapshotKeyHonorModalViews,
        )
      }

      else -> throw UnsupportedOperationException("Unsupported device type $deviceType for iOS platform")
    }

    val deviceController = when (deviceType) {
      Device.DeviceType.REAL -> {
        val device = util.LocalIOSDevice().listDeviceViaDeviceCtl(deviceId)
        val deviceCtlDevice = DeviceControlIOSDevice(deviceId = device.identifier)
        deviceCtlDevice
      }

      Device.DeviceType.SIMULATOR -> {
        val simctlIOSDevice = SimctlIOSDevice(
          deviceId = deviceId,
        )
        simctlIOSDevice
      }

      else -> throw UnsupportedOperationException("Unsupported device type $deviceType for iOS platform")
    }

    val xcTestInstaller = LocalXCTestInstaller(
      deviceId = deviceId,
      host = defaultXctestHost,
      defaultPort = targetPort,
      reinstallDriver = reinstallDriver,
      deviceType = iOSDeviceType,
      iOSDriverConfig = iOSDriverConfig,
      deviceController = deviceController,
      // Maestro 2.6.1 added a required logsDir for the XCUITest (xcodebuild) subprocess logs.
      // Trailblaze routes its own diagnostics through Console/Tracer, so point this at an
      // ephemeral temp dir rather than wiring in Maestro's DebugLogStore (which would also
      // stand up Maestro's file-logging machinery as a side effect). Scope it per device+port so
      // concurrent or back-to-back iOS sessions don't interleave logs into one growing directory.
      logsDir = File(System.getProperty("java.io.tmpdir"), "trailblaze-xctest-logs/$deviceId-$targetPort")
        .apply { mkdirs() },
    )

    val xcTestDriverClient = XCTestDriverClient(
      installer = xcTestInstaller,
      client = XCTestClient(defaultXctestHost, targetPort),
      reinstallDriver = reinstallDriver,
    )

    val xcTestDevice = XCTestIOSDevice(
      deviceId = deviceId,
      client = xcTestDriverClient,
      getInstalledApps = { XCRunnerCLIUtils().listApps(deviceId) },
    )

    val baseIosDriver = IOSDriver(
      LocalIOSDevice(
        deviceId = deviceId,
        xcTestDevice = xcTestDevice,
        deviceController = deviceController,
        insights = CliInsights,
      ),
      insights = CliInsights,
    )

    /**
     * Use custom driver from [TrailblazeHostAppTarget] if provided, otherwise use default driver
     */
    val customResult = appTarget?.getCustomIosDriverFactory(
      trailblazeDeviceId = TrailblazeDeviceId(
        instanceId = deviceId,
        trailblazeDevicePlatform = TrailblazeDevicePlatform.IOS,
      ),
      originalIosDriver = baseIosDriver
    )
    val iosDriver: Driver = customResult as? Driver ?: baseIosDriver
    if (appTarget != null) {
      val isCustom = customResult != null && customResult !== baseIosDriver
      Console.log("[iOS Driver] appTarget=${appTarget.id}, hasCustomIosDriver=${appTarget.hasCustomIosDriver}, " +
        "customResult=${customResult?.javaClass?.simpleName}, isCustomDriver=$isCustom, " +
        "driverClass=${iosDriver.javaClass.simpleName}")
    } else {
      Console.log("[iOS Driver] appTarget=null, using base IOSDriver")
    }

    val maestro = Maestro.ios(
      driver = iosDriver,
      openDriver = openDriver || xcTestDevice.isShutdown(),
    )

    // Wait for driver to be ready with retry logic
    // The first test often fails because the XCUITest driver needs time to fully start
    if (openDriver) {
      Console.log("Waiting for XCUITest driver to be ready on port $targetPort...")
      val driverReady = waitForDriverReady(defaultXctestHost, targetPort, maxRetries = 3, initialDelayMs = 1000)
      if (!driverReady) {
        Console.log("Warning: XCUITest driver may not be fully ready, but proceeding anyway")
      }
    }

    // Auto-rotate iPads to landscape. iPads are detected by their shortest screen dimension using the
    // same canonical threshold as TrailblazeHostDeviceClassifier (shared constant, not a magic number).
    // This must happen after driver init because creating a new XCTest session resets orientation.
    try {
      runBlocking {
        val info = maestro.deviceInfo()
        val minDimension = minOf(info.widthPixels, info.heightPixels)
        if (minDimension >= TrailblazeHostDeviceClassifier.TABLET_MIN_SHORTEST_SIDE_PX) {
          Console.log("iPad detected (${info.widthPixels}x${info.heightPixels}) — setting landscape orientation")
          maestro.setOrientation(DeviceOrientation.LANDSCAPE_LEFT)
        }
      }
    } catch (e: Exception) {
      Console.log("Warning: Failed to detect device type or set orientation: ${e.message}")
    }

    return IosLaunchReadinessDriver(maestro.driver)
  }

  private fun waitForDriverReady(
    host: String,
    port: Int,
    maxRetries: Int = 3,
    initialDelayMs: Long = 1000,
  ): Boolean {
    var currentDelay = initialDelayMs
    for (attempt in 1..maxRetries) {
      try {
        // Try to establish a connection to the XCUITest server
        java.net.Socket(host, port).use { socket ->
          Console.log("XCUITest driver is ready on port $port after $attempt attempt(s)")
          return true
        }
      } catch (e: Exception) {
        if (attempt < maxRetries) {
          Console.log(
            "XCUITest driver not ready yet on port $port (attempt $attempt/$maxRetries), " +
                "waiting ${currentDelay}ms before retry...",
          )
          Thread.sleep(currentDelay)
          // Exponential backoff with max delay of 3 seconds
          currentDelay = minOf(currentDelay * 2, 3000)
        } else {
          Console.log("XCUITest driver failed to respond after $maxRetries attempts: ${e.message}")
        }
      }
    }
    return false
  }

}

/**
 * Which device's driver holds each XCTest port in this JVM. A device's claim lasts from the start of
 * its driver's build until that driver is closed, or until another device finds it dead.
 */
internal class XcTestPortOwners {
  private data class Owner(val deviceId: String, val connected: Boolean)

  private val owners = HashMap<Int, Owner>()

  /**
   * Records [deviceId] as [port]'s owner. Throws if another device's driver is still building on
   * the port or is connected and [portIsLive]: building here would first clear the port, killing
   * that device's runner mid-trail.
   *
   * A connected owner whose port is dead lost its runner without ever closing its driver, and would
   * otherwise hold the port for the life of the daemon. Its claim is taken over and its id
   * returned, so the caller can discard the driver it left cached. A claim that is still building
   * is never taken over: its runner has not bound the port yet, so a dead port proves nothing.
   */
  @Synchronized
  fun claim(port: Int, deviceId: String, portIsLive: () -> Boolean): String? {
    val owner = owners[port]
    val staleOwner = owner?.deviceId?.takeIf { it != deviceId }
    check(staleOwner == null || (owner!!.connected && !portIsLive())) {
      "iOS devices $deviceId and $staleOwner both map to XCTest port $port, and $staleOwner's driver " +
        "is live on it. Refusing to connect $deviceId: clearing the port would kill $staleOwner's " +
        "runner. Use a different device for one of them, or disconnect $staleOwner first."
    }
    owners[port] = Owner(deviceId, connected = false)
    return staleOwner
  }

  /** Marks [deviceId]'s claim on [port] as a connected driver, whose liveness the port now shows. */
  @Synchronized
  fun connected(port: Int, deviceId: String) {
    if (owners[port]?.deviceId == deviceId) owners[port] = Owner(deviceId, connected = true)
  }

  /** Drops [deviceId]'s claim on [port], unless another device has since taken the port over. */
  @Synchronized
  fun release(port: Int, deviceId: String) {
    if (owners[port]?.deviceId == deviceId) owners.remove(port)
  }
}
