package xyz.block.trailblaze.host.devices

import device.SimctlIOSDevice
import ios.LocalIOSDevice
import ios.devicectl.DeviceControlIOSDevice
import ios.xctest.XCTestIOSDevice
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
import java.net.ServerSocket
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.io.path.pathString

internal object HostIosDriverFactory {

  private val defaultXctestHost = "127.0.0.1"
  private val defaultXcTestPort = 22087

  // Singleton driver reuse within the same JVM session
  private var cachedMaestro: Maestro? = null
  private var cachedDeviceId: String? = null
  private var cachedDriverHostPort: Int? = null

  @Volatile
  private var hasPerformedInitialCleanup = false

  private fun waitForPortRelease(
    port: Int,
    timeoutMs: Long,
  ): Boolean {
    val startTime = System.currentTimeMillis()
    var attempts = 0
    while (System.currentTimeMillis() - startTime < timeoutMs) {
      try {
        ServerSocket(port).close()
        println("Port $port successfully released after ${System.currentTimeMillis() - startTime}ms")
        return true
      } catch (e: Exception) {
        attempts++
        if (attempts % 10 == 0) {
          println(
            "Still waiting for port $port to be released... " +
              "(${System.currentTimeMillis() - startTime}ms elapsed)",
          )
        }
        Thread.sleep(100)
      }
    }
    println("Warning: Port $port may still be in use after ${timeoutMs}ms timeout")
    return false
  }

  fun createIOS(
    deviceId: String,
    openDriver: Boolean,
    driverHostPort: Int?,
    reinstallDriver: Boolean,
    platformConfiguration: WorkspaceConfig.PlatformConfiguration?,
    deviceType: Device.DeviceType,
  ): Maestro {
    val targetPort = driverHostPort ?: defaultXcTestPort

    // Check if we can reuse existing driver
    if (cachedMaestro != null &&
      cachedDeviceId == deviceId &&
      cachedDriverHostPort == targetPort &&
      !cachedMaestro!!.driver.isShutdown()
    ) {
      println("Reusing existing iOS driver for device $deviceId on port $targetPort")
      return cachedMaestro!!
    }

    // Only perform cleanup on first creation in this JVM (handles stale processes from previous runs)
    if (!hasPerformedInitialCleanup) {
      println("Performing initial cleanup for fresh JVM - killing stale processes on port $targetPort")
      killProcessesUsingPort(targetPort)
      // Give the system a moment to fully release the port after killing processes
      Thread.sleep(1000)
      hasPerformedInitialCleanup = true
    } else {
      println("Skipping process cleanup - reusing connection within same JVM session")
      waitForPortRelease(port = targetPort, timeoutMs = 5000)
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
    )

    val xcTestDriverClient = XCTestDriverClient(
      installer = xcTestInstaller,
      client = XCTestClient(defaultXctestHost, driverHostPort ?: defaultXcTestPort),
      reinstallDriver = !hasPerformedInitialCleanup || reinstallDriver, // Only reinstall on first run or if explicitly requested
    )

    val xcTestDevice = XCTestIOSDevice(
      deviceId = deviceId,
      client = xcTestDriverClient,
      getInstalledApps = { XCRunnerCLIUtils.listApps(deviceId) },
    )

    val iosDriver = IOSDriver(
      LocalIOSDevice(
        deviceId = deviceId,
        xcTestDevice = xcTestDevice,
        deviceController = deviceController,
        insights = CliInsights,
      ),
      insights = CliInsights,
    )

    val maestro = Maestro.ios(
      driver = iosDriver,
      openDriver = openDriver || xcTestDevice.isShutdown(),
    )

    // Cache the driver for reuse
    cachedMaestro = maestro
    cachedDeviceId = deviceId
    cachedDriverHostPort = targetPort

    println("Created new iOS driver for device $deviceId on port $targetPort")
    return maestro
  }

  private fun killProcessesUsingPort(port: Int) {
    try {
      // Use lsof to find processes using the port
      val lsofProcess = ProcessBuilder(listOf("lsof", "-ti:$port"))
        .redirectErrorStream(true)
        .start()

      val lsofCompleted = lsofProcess.waitFor(5, TimeUnit.SECONDS)
      if (!lsofCompleted) {
        lsofProcess.destroyForcibly()
        return
      }

      val pids = lsofProcess.inputStream.bufferedReader().readText().trim()

      if (pids.isNotEmpty()) {
        pids.split("\n").filter { it.isNotBlank() }.forEach { pid ->
          try {
            val pidTrimmed = pid.trim()
            // Force kill the process
            ProcessBuilder(listOf("kill", "-9", pidTrimmed))
              .start()
              .waitFor(2, TimeUnit.SECONDS)
          } catch (e: Exception) {
            // Ignore individual process kill failures
          }
        }
      }
    } catch (e: Exception) {
      // Ignore cleanup failures - don't prevent new connections
    }
  }
}
