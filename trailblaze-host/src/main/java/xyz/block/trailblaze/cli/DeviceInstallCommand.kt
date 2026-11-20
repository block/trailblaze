package xyz.block.trailblaze.cli

import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Parameters
import xyz.block.trailblaze.host.install.DeviceInstallResponse
import xyz.block.trailblaze.util.Console
import java.io.File
import java.util.concurrent.Callable

/**
 * Installs an app on a device: this machine's own, or one borrowed from a paired daemon, whose
 * machine then installs it. The daemon does the install, so it runs one if none is.
 */
@Command(
  name = "install",
  mixinStandardHelpOptions = true,
  description = ["Install an app on a device, including one borrowed from another machine"],
  footer = [
    "",
    "Android takes an .apk; an iOS simulator takes an .app bundle or a .zip",
    "holding one. A borrowed device's app is sent to the machine that lends it.",
    "",
    "Example:",
    "  trailblaze device install app-debug.apk --device android/emulator-5554",
  ],
)
class DeviceInstallCommand : Callable<Int> {

  @Parameters(arity = "1", paramLabel = "APP", description = ["The .apk, .app or .zip to install"])
  lateinit var app: File

  @CommandLine.Option(
    names = ["-d", "--device"],
    description = [
      "Device to install on, as `trailblaze device list` shows it. Defaults to " +
        "`\$TRAILBLAZE_DEVICE` if set, otherwise this terminal's pin.",
    ],
  )
  var device: String? = null

  internal var startDaemon: (port: Int) -> Boolean = { ensureDaemonServerRunning(it) }
  internal var installOnDaemon: (port: Int, device: String, path: String) -> DeviceInstallResponse =
    { port, device, path -> DaemonClient(port = port).use { it.installAppBlocking(device, path) } }

  override fun call(): Int {
    val port = CliConfigHelper.resolveEffectiveHttpPort()
    val target = resolveCliDevice(device) ?: readShellPinDevice(port) ?: run {
      Console.error(
        "device install needs a device. Pass `--device <platform>/<id>`; " +
          "`trailblaze device list` shows the devices you can use.",
      )
      return TrailblazeExitCode.MISUSE.code
    }
    // The daemon may run this command, from its own cwd: a relative path is the caller's.
    val appFile = callerFile(app).absoluteFile
    if (!appFile.exists()) {
      Console.error("No such file: ${app.path}")
      return TrailblazeExitCode.MISUSE.code
    }
    if (!startDaemon(port)) {
      Console.error("Could not start the Trailblaze daemon, which does the install.")
      return TrailblazeExitCode.INFRA_FAILED.code
    }
    Console.error("Installing ${appFile.name} on $target...")
    val result = installOnDaemon(port, target, appFile.path)
    if (!result.success) {
      Console.error(result.message)
      return TrailblazeExitCode.INFRA_FAILED.code
    }
    Console.info(result.message)
    return TrailblazeExitCode.SUCCESS.code
  }
}
