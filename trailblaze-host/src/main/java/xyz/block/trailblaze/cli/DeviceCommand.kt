package xyz.block.trailblaze.cli

import kotlinx.coroutines.runBlocking
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Parameters
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.util.Console
import java.util.concurrent.Callable
import kotlin.system.exitProcess

/**
 * Manage device connections — list available devices and connect to a session.
 *
 * Examples:
 *   trailblaze device                       - List available devices (default)
 *   trailblaze device list                  - List available devices
 *   trailblaze device connect ANDROID       - Connect an Android device to your session
 *   trailblaze device connect IOS           - Connect an iOS device to your session
 *   trailblaze device connect WEB           - Connect a web browser to your session
 */
@Command(
  name = "device",
  mixinStandardHelpOptions = true,
  description = ["List and connect devices (Android, iOS, Web)"],
  subcommands = [
    DeviceListCommand::class,
    DeviceConnectCommand::class,
  ],
)
class DeviceCommand : Callable<Int> {

  @CommandLine.ParentCommand
  internal lateinit var parent: TrailblazeCliCommand

  override fun call(): Int {
    // Default action: list devices
    return DeviceListCommand.listDevices(parent)
  }
}

/**
 * List all available devices.
 */
@Command(
  name = "list",
  mixinStandardHelpOptions = true,
  description = ["List available devices"],
)
class DeviceListCommand : Callable<Int> {

  @CommandLine.ParentCommand
  private lateinit var parent: DeviceCommand

  override fun call(): Int {
    return listDevices(parent.parent)
  }

  companion object {
    fun listDevices(parent: TrailblazeCliCommand): Int {
      Console.enableQuietMode()
      val app = parent.appProvider()

      Console.info("Scanning for connected devices...")
      val allDevices = runBlocking {
        app.deviceManager.loadDevicesSuspend(applyDriverFilter = true)
      }

      // Filter out Revyl cloud devices — they require the revyl CLI which
      // may not be installed, and they clutter the output for local usage.
      // Always include playwright-native (web) — it's always available and
      // the driver filter may hide it when not in web testing mode.
      val devices = allDevices.filter {
        it.trailblazeDriverType != TrailblazeDriverType.REVYL_ANDROID &&
          it.trailblazeDriverType != TrailblazeDriverType.REVYL_IOS
      }.let { filtered ->
        if (filtered.none { it.trailblazeDriverType == TrailblazeDriverType.PLAYWRIGHT_NATIVE }) {
          filtered + TrailblazeConnectedDeviceSummary(
            trailblazeDriverType = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
            instanceId = "playwright-native",
            description = "Playwright Browser (Native)",
          )
        } else {
          filtered
        }
      }

      if (devices.isEmpty()) {
        Console.info("No devices found.")
        Console.info("")
        Console.info("To connect devices:")
        Console.info("  Android: Connect via USB or start an emulator")
        Console.info("  iOS:     Start an iOS simulator via Xcode")
        Console.info("  Web:     Always available (uses Playwright)")
        exitProcess(CommandLine.ExitCode.OK)
      }

      Console.info("")
      val byPlatform = devices.groupBy { it.platform }
      Console.info("${devices.size} device(s) available. Start a session (--device only needed on first blaze):")
      Console.info("")
      byPlatform.forEach { (_, platformDevices) ->
        platformDevices.forEach { device ->
          val platformName = device.platform.name.lowercase()
          Console.info("  trailblaze blaze --device $platformName/${device.instanceId} \"<objective>\"")
        }
      }

      // Force exit to terminate background services started by app initialization
      exitProcess(CommandLine.ExitCode.OK)
    }
  }
}

/**
 * Connect a device to the current CLI session.
 *
 * Once connected, all subsequent commands (blaze, ask, etc.)
 * use this device automatically — no need to pass -d on every call.
 *
 * Examples:
 *   trailblaze device connect ANDROID
 *   trailblaze device connect IOS
 *   trailblaze device connect WEB
 */
@Command(
  name = "connect",
  mixinStandardHelpOptions = true,
  description = ["Connect a device to your session (ANDROID, IOS, or WEB)"],
)
class DeviceConnectCommand : Callable<Int> {

  @CommandLine.ParentCommand
  private lateinit var parent: DeviceCommand

  @Parameters(
    index = "0",
    description = ["Device platform: ANDROID, IOS, or WEB"],
  )
  lateinit var platform: String

  override fun call(): Int {
    return cliWithDaemon(verbose = false) { client ->
      val deviceError = client.ensureDevice(platform)
      if (deviceError != null) {
        Console.error(deviceError)
        return@cliWithDaemon CommandLine.ExitCode.SOFTWARE
      }

      // Save device to config so subsequent commands default to it
      val platformStr = platform.split("/", limit = 2)[0]
      if (TrailblazeDevicePlatform.fromString(platformStr) != null) {
        CliConfigHelper.updateConfig { cfg -> cfg.copy(cliDevicePlatform = platformStr.uppercase()) }
      }

      Console.info("Device connected. You can now run:")
      Console.info("  trailblaze blaze \"<objective>\"")
      Console.info("  trailblaze ask \"<question>\"")
      CommandLine.ExitCode.OK
    }
  }
}
