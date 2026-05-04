package xyz.block.trailblaze.cli

import kotlinx.coroutines.runBlocking
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Parameters
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.devices.WebInstanceIds
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

  @CommandLine.Option(
    names = ["--all"],
    description = ["Include hidden platforms (e.g. the Compose desktop driver — `desktop/self`)."],
  )
  internal var showAll: Boolean = false

  override fun call(): Int {
    return listDevices(parent.parent, showAll = showAll)
  }

  companion object {
    fun listDevices(parent: TrailblazeCliCommand, showAll: Boolean = false): Int {
      Console.enableQuietMode()

      // Prefer the running daemon as the source of truth — it's the process that
      // actually owns the WebBrowserManager state across CLI invocations, so any
      // browser slots provisioned via `--device web/<id>` only show up there.
      // When no daemon is running, fall through to in-process discovery so the
      // command still works offline.
      val port = CliConfigHelper.resolveEffectiveHttpPort()
      val daemonEntries = fetchDevicesFromDaemonIfRunning(port)
      if (daemonEntries != null) {
        printEntries(daemonEntries)
        exitProcess(CommandLine.ExitCode.OK)
      }

      val app = parent.appProvider()

      Console.info("Scanning for connected devices...")
      val allDevices = runBlocking {
        app.deviceManager.loadDevicesSuspend(applyDriverFilter = true)
      }

      // Filter out Revyl cloud devices — they require the revyl CLI which
      // may not be installed, and they clutter the output for local usage.
      // Filter platforms with `hidden = true` (Compose desktop) unless the user
      // passed `--all`, mirroring the top-level `trailblaze --help --all` escape hatch.
      val devices = allDevices.filter {
        it.trailblazeDriverType != TrailblazeDriverType.REVYL_ANDROID &&
          it.trailblazeDriverType != TrailblazeDriverType.REVYL_IOS &&
          (showAll || !it.platform.hidden)
      }.let { filtered ->
        // Re-include named web browser instances that the UI-level filter strips when
        // web mode is off. They're real, running browsers — users need them visible
        // here so they can reuse the same `--device web/<id>` across commands.
        val running = app.deviceManager.webBrowserManager.getAllRunningBrowserSummaries()
        val seen = filtered.map { it.instanceId to it.platform }.toMutableSet()
        val withRunning = filtered + running.filter { (it.instanceId to it.platform) !in seen }
          .also { added -> added.forEach { seen += it.instanceId to it.platform } }

        // Always include the playwright-native singleton — it's the always-available
        // virtual default that maps to bare `--device web`. Check by instanceId here
        // (not driver type), because named web instances also use PLAYWRIGHT_NATIVE
        // and would otherwise suppress the canonical default from the listing.
        if (withRunning.none { it.instanceId == WebInstanceIds.PLAYWRIGHT_NATIVE }) {
          withRunning + TrailblazeConnectedDeviceSummary(
            trailblazeDriverType = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
            instanceId = WebInstanceIds.PLAYWRIGHT_NATIVE,
            description = "Playwright Browser (Native)",
          )
        } else {
          withRunning
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
      Console.info("${devices.size} device(s) available. Pass --device on each device command:")
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

    /**
     * Returns the daemon's view of available devices, or null if no daemon is
     * running on [port] (in which case the caller should fall back to in-process
     * discovery). Never throws — connection failures and tool errors both yield
     * null so the user always gets *some* output.
     */
    private fun fetchDevicesFromDaemonIfRunning(port: Int): List<CliMcpClient.DeviceListEntry>? {
      return try {
        runBlocking {
          val running = DaemonClient(port = port).use { it.isRunning() }
          if (!running) return@runBlocking null
          CliMcpClient.connectOneShot(port = port).use { client ->
            val result = client.callTool("device", mapOf("action" to "LIST"))
            if (result.isError) null
            else CliMcpClient.parseDeviceList(result.content)
          }
        }
      } catch (_: Exception) {
        null
      }
    }

    private fun printEntries(entries: List<CliMcpClient.DeviceListEntry>) {
      if (entries.isEmpty()) {
        Console.info("No devices found.")
        Console.info("")
        Console.info("To connect devices:")
        Console.info("  Android: Connect via USB or start an emulator")
        Console.info("  iOS:     Start an iOS simulator via Xcode")
        Console.info("  Web:     Always available (uses Playwright)")
        return
      }
      Console.info("${entries.size} device(s) available. Pass --device on each device command:")
      Console.info("")
      entries.forEach { entry ->
        val platformName = entry.platform.name.lowercase()
        val descSuffix = entry.description?.let { " — $it" } ?: ""
        Console.info("  trailblaze blaze --device $platformName/${entry.instanceId} \"<objective>\"$descSuffix")
      }
    }
  }
}

/**
 * Connect a device to the current CLI session.
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

  @CommandLine.Mixin
  val headlessOption: HeadlessOption = HeadlessOption()

  override fun call(): Int {
    return cliWithDaemon(verbose = false) { client ->
      val deviceError = client.ensureDevice(
        platform,
        webHeadless = headlessOption.resolve(),
      )
      if (deviceError != null) {
        Console.error(deviceError)
        return@cliWithDaemon CommandLine.ExitCode.SOFTWARE
      }

      // Save device to config so subsequent commands default to it
      val platformStr = platform.split("/", limit = 2)[0]
      if (TrailblazeDevicePlatform.fromString(platformStr) != null) {
        CliConfigHelper.updateConfig { cfg -> cfg.copy(cliDevicePlatform = platformStr.uppercase()) }
      }

      Console.info("Device connected for the current session workflow.")
      Console.info("Interactive device commands still require --device on each call.")
      CommandLine.ExitCode.OK
    }
  }
}
