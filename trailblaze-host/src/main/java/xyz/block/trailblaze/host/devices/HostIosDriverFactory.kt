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
  private val defaultXcTestPort = 22087

  /**
   * The one iOS driver this JVM keeps open, everything that identifies it, and the owners currently
   * holding it.
   *
   * Owners are counted because they genuinely coexist: an agent session driving the device through
   * the MCP bridge and a Trail Runner viewer streaming its screen are handed this same driver
   * whenever their target wrappers agree, and each tears down on its own schedule. Every caller gets a
   * [SharedLease.acquire] handle wrapped as a [Driver], so closing what it was given releases only
   * its own hold, and the XCUITest connection goes away when the last owner lets go rather than the
   * first.
   *
   * Every hold has to be released by somebody, or the count never returns to zero and the driver
   * outlives the last owner - which would cost a cancel its whole point, killing the XCUITest child
   * processes. A run takes two, because the device its classifiers need can't come from `hostRunner`
   * without a cycle; `BaseHostTrailblazeTest.releaseConnectedDeviceIfOpened` releases that one and
   * `TrailblazeDeviceManager.setActiveDriverForDevice` releases the driver it displaces.
   */
  private class Cached(
    val driver: Driver,
    val deviceId: String,
    val port: Int,
    val wrapperKey: String?,
  ) {
    val lease = SharedLease {
      try {
        driver.close()
      } catch (e: Exception) {
        Console.log("Failed to close the iOS driver for device $deviceId (already closed?): ${e.message}")
      }
    }

    /**
     * This driver as one owner's handle on it - see [LeasedIosDriver] - or null once the last owner
     * has let go and closed it, which callers read as a cache miss.
     */
    fun leased(): Driver? = lease.acquire()?.let { LeasedIosDriver(driver, it) }
  }

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

  private var cached: Cached? = null

  @Volatile
  private var hasPerformedInitialCleanup = false

  /**
   * Closes the cached driver, however many owners still hold it, and forgets it so the next
   * [createIOS] call builds a fresh one. Call this when the driver has been superseded (e.g., a
   * force-reconnect, or a target whose iOS driver wrapper changed).
   *
   * Force-closing rather than waiting for the last owner to let go is the same trade the
   * mismatched-wrapper discard in [createIOS] makes, for the same reason: the replacement needs the
   * very port this driver is holding. Leaving it open and merely forgetting it would be the worse
   * end - nothing points at the old driver any more, so nothing can supersede it, and the
   * replacement binds against a runner built for the wrong wrapper.
   */
  @Synchronized
  fun clearCachedDriver() {
    cached?.lease?.closeNow()
    cached = null
  }

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

  @Synchronized
  fun createIOS(
    deviceId: String,
    openDriver: Boolean,
    driverHostPort: Int?,
    reinstallDriver: Boolean,
    platformConfiguration: WorkspaceConfig.PlatformConfiguration?,
    deviceType: Device.DeviceType,
    appTarget: TrailblazeHostAppTarget? = null,
  ): Driver {
    val targetPort = driverHostPort ?: defaultXcTestPort
    val wrapperKey = driverWrapperKey(appTarget)

    // A driver built for another target's wrapper is the wrong driver, however healthy it is.
    // Without this the FIRST caller to connect a device won its wrapper for the whole JVM: a
    // recording connect (which passes no target at all) caches the plain base driver, and a later
    // run for a custom-driver target is handed it and drives the app unwrapped.
    //
    // Closed here rather than left for the reuse check to skip past, because ports are per-device:
    // a device or port mismatch builds its replacement somewhere else, but this rebuild lands on
    // the very port the superseded driver is still holding.
    //
    // Anything still holding the superseded driver (an MCP persistent device, say) fails on its
    // next call, and a lease can't prevent that: the replacement needs the very port the old driver
    // is holding, so waiting for its owners to let go would mean never building it. That is the
    // right end of the trade - the old driver is built for another target and reusing it silently
    // drives the app through the wrong wrapper, which is the bug this exists to stop - and it's what
    // `selectAppTarget` already does deliberately when the daemon-wide selection changes. It is also
    // what `HostDeviceSessionManager` refuses a conflicting connect to keep anyone from reaching.
    cached?.let { current ->
      if (current.deviceId == deviceId && current.port == targetPort && current.wrapperKey != wrapperKey) {
        Console.log(
          "Discarding cached iOS driver for device $deviceId - it was built for target wrapper " +
            "'${current.wrapperKey ?: "<none>"}' and this connect needs " +
            "'${wrapperKey ?: "<none>"}'; closing it so the replacement can take port $targetPort",
        )
        clearCachedDriver()
      }
    }

    // Check if we can reuse existing driver
    cached?.let { current ->
      if (current.deviceId == deviceId && current.port == targetPort && !current.driver.isShutdown()) {
        // isShutdown() is an in-process flag and stays false when the XCTest runner is
        // reaped externally (SIGKILL, OS reap, crash). Confirm the port is still
        // accepting connections before handing the cached driver back.
        if (HostDriverPortUtils.isPortReachable(defaultXctestHost, targetPort, timeoutMs = 500)) {
          // A refused lease is a cache miss like any other: the last owner let go and closed this
          // driver, so a healthy-looking port says nothing about it.
          current.leased()?.let { leased ->
            Console.log("Reusing existing iOS driver for device $deviceId on port $targetPort")
            return leased
          }
          Console.log(
            "Discarding cached iOS driver for device $deviceId - its last owner let go and closed " +
              "it; will create a fresh driver",
          )
        } else {
          Console.log(
            "Discarding cached iOS driver for device $deviceId — port $targetPort is unreachable " +
              "(subprocess likely reaped externally); will create a fresh driver",
          )
        }
        cached = null
      }
    }

    // Only perform cleanup on first creation in this JVM (handles stale processes from previous runs)
    if (!hasPerformedInitialCleanup) {
      Console.log("Performing initial cleanup for fresh JVM - killing stale processes on port $targetPort")
      HostDriverPortUtils.killProcessesUsingPort(targetPort)
      // Give the system more time to fully release the port after killing processes
      Thread.sleep(2000)
      hasPerformedInitialCleanup = true
    } else {
      Console.log("Skipping process cleanup - reusing connection within same JVM session")
      HostDriverPortUtils.waitForPortRelease(port = targetPort, timeoutMs = 5000)
    }

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
      defaultPort = driverHostPort ?: defaultXcTestPort,
      reinstallDriver = !hasPerformedInitialCleanup || reinstallDriver, // Only reinstall on first run or if explicitly requested
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
      client = XCTestClient(defaultXctestHost, driverHostPort ?: defaultXcTestPort),
      reinstallDriver = !hasPerformedInitialCleanup || reinstallDriver, // Only reinstall on first run or if explicitly requested
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

    // Cache the driver for reuse
    val entry = Cached(
      driver = maestro.driver,
      deviceId = deviceId,
      port = targetPort,
      wrapperKey = wrapperKey,
    )
    cached = entry

    Console.log("Created new iOS driver for device $deviceId on port $targetPort")
    // A lease built a line ago has had no owner to let go of it, so it cannot refuse this one.
    return checkNotNull(entry.leased()) { "A freshly cached iOS driver refused its first lease" }
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
