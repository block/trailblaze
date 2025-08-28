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
import java.nio.file.Paths
import kotlin.io.path.pathString

internal object HostIosDriverFactory {

  private val defaultXctestHost = "127.0.0.1"
  private val defaultXcTestPort = 22087

  fun createIOS(
    deviceId: String,
    openDriver: Boolean,
    driverHostPort: Int?,
    reinstallDriver: Boolean,
    platformConfiguration: WorkspaceConfig.PlatformConfiguration?,
    deviceType: Device.DeviceType,
  ): Maestro {
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
      reinstallDriver = reinstallDriver,
      deviceType = iOSDeviceType,
      iOSDriverConfig = iOSDriverConfig,
      deviceController = deviceController,
    )

    val xcTestDriverClient = XCTestDriverClient(
      installer = xcTestInstaller,
      client = XCTestClient(defaultXctestHost, driverHostPort ?: defaultXcTestPort),
      reinstallDriver = reinstallDriver,
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

    return Maestro.ios(
      driver = iosDriver,
      openDriver = openDriver || xcTestDevice.isShutdown(),
    )
  }
}
