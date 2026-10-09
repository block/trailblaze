package xyz.block.trailblaze.cli

import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import xyz.block.trailblaze.devices.TrailblazeDevicePort
import xyz.block.trailblaze.ui.TrailblazePortManager
import xyz.block.trailblaze.ui.TrailblazeDesktopUtil
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.isWindows
import java.io.File
import java.util.concurrent.Callable
import kotlin.time.Duration.Companion.seconds

internal fun trailRunnerLaunchProcessBuilder(launcher: File, port: Int): ProcessBuilder =
  ProcessBuilder(launcher.absolutePath, "trailrunner").apply {
    environment()[TrailblazePortManager.HTTP_PORT_ENV_VAR] = port.toString()
    inheritIO()
  }

/**
 * Open Trail Runner, start or stop the daemon, or check its status.
 *
 * Examples:
 *   trailblaze app                   - Open Trail Runner (starts the daemon if needed)
 *   trailblaze app start             - Start the daemon in the background
 *   trailblaze app --headless        - Same as `trailblaze app start`
 *   trailblaze app start --foreground - Run the daemon in this terminal
 *   trailblaze app --stop            - Stop the daemon
 *   trailblaze app --status          - Check if daemon is running
 */
@Command(
  name = "app",
  mixinStandardHelpOptions = true,
  subcommands = [AppStartCommand::class],
  description = [
    "Open Trailblaze App, starting the daemon if it is not running (use --headless to start only the daemon).",
    // `start` is deliberately unpublished as a subcommand (see [AppStartCommand]), so this is
    // the only place the reference docs learn that the spelling docs and error hints use is real.
    "`trailblaze app start` starts only the daemon, the same as `trailblaze app --headless`.",
  ]
)
open class AppCommand : Callable<Int> {

  @CommandLine.ParentCommand
  private lateinit var parent: TrailblazeCliCommand

  @Option(
    names = ["--headless"],
    description = ["Start only the daemon, without opening Trailblaze App"]
  )
  var headless: Boolean = false

  @Option(
    names = ["--stop"],
    description = ["Stop the running daemon"]
  )
  var stop: Boolean = false

  @Option(
    names = ["--status"],
    description = ["Check if the daemon is running"]
  )
  var status: Boolean = false

  @Option(
    names = ["--foreground"],
    description = ["Run the daemon in the foreground (blocks terminal). Use for debugging with an attached IDE."]
  )
  var foreground: Boolean = false

  /** The spelling that opened Trail Runner while it was opt-in. Kept so existing habits and links work. */
  @Option(names = ["--v2"], hidden = true)
  var v2: Boolean = false

  /** Where [launchTrailRunner] finds the launcher to re-enter. Swapped only by tests. */
  internal var launcherFinder: () -> File? = ::findTrailblazeLauncher

  override fun call(): Int {
    if (v2 && (stop || status || headless || foreground)) {
      Console.error("--v2 cannot be combined with daemon options.")
      return TrailblazeExitCode.MISUSE.code
    }
    return when {
      stop -> doStop()
      status -> doStatus()
      headless || foreground -> startDaemon()
      else -> launchTrailRunner()
    }
  }

  /**
   * Start the daemon without opening Trail Runner — shared by `trailblaze app --headless` and
   * `trailblaze app start`. Kept separate from [call] so the subcommand can reach it without
   * re-entering the `--stop` / `--status` dispatch.
   */
  internal fun startDaemon(): Int =
    if (foreground) parent.launchDaemonInForeground() else launchInBackground()

  /**
   * Shell launchers normally open Trail Runner for bare `app` before picocli starts. This
   * fallback keeps direct JVM and wrapper-dispatched invocations honest by re-entering the same
   * launcher's `trailrunner` alias rather than duplicating native-shell orchestration in Kotlin.
   */
  private fun launchTrailRunner(): Int {
    if (isWindows()) return openTrailRunnerInBrowser()
    val launcher = launcherFinder() ?: run {
      // IDE/direct-JVM runs have no launcher to open the native shell with. Serve Trail Runner
      // from this process instead, so the URL below is live for as long as the run lasts.
      Console.log("Trailblaze App: http://localhost:${parent.getEffectivePort()}/trailrunner/")
      return parent.launchDaemonInForeground()
    }
    return try {
      val launcherExitCode = trailRunnerLaunchProcessBuilder(launcher, parent.getEffectivePort())
        .start()
        .waitFor()
      if (launcherExitCode == 0) {
        TrailblazeExitCode.SUCCESS.code
      } else {
        Console.error("Failed to open Trailblaze App (launcher exit code $launcherExitCode).")
        TrailblazeExitCode.INFRA_FAILED.code
      }
    } catch (e: Exception) {
      Console.error("Failed to open Trailblaze App: ${e.message}")
      TrailblazeExitCode.INFRA_FAILED.code
    }
  }

  /**
   * Windows has no launcher to own a native Trail Runner window, so start the daemon here and hand
   * its URL to the default browser — the shape the launcher already falls back to on a Linux host
   * with no window shell.
   */
  private fun openTrailRunnerInBrowser(): Int {
    val port = parent.getEffectivePort()
    if (!ensureDaemonServerRunning(port, respectAutoStartDisable = false)) {
      return TrailblazeExitCode.INFRA_FAILED.code
    }
    val url = "http://localhost:$port/trailrunner/"
    Console.log("Trailblaze App: $url")
    if (!TrailblazeDesktopUtil.openInDefaultBrowser(url)) {
      Console.log("Open this URL in your browser: $url")
    }
    return TrailblazeExitCode.SUCCESS.code
  }

  /** Start the daemon as a background process and return control to the terminal. */
  private fun launchInBackground(): Int {
    val port = parent.getEffectivePort()
    // Before the probe below: a device's `adb forward` on the configured port answers /ping, and
    // "already running" for a port no daemon can bind would exit 0 on a configuration error.
    TrailblazeDevicePort.requireDaemonPortsOutsideDeviceAllocationRange(
      httpPort = port,
      httpsPort = parent.getEffectiveHttpsPort(),
    )
    // IDE/direct-JVM runs have no way to spawn a daemon (no launcher, and on Windows no JAR). Keep
    // their established in-process fallback instead of routing through the daemon spawn helper.
    if (daemonSpawnCommand() == null) return parent.launchDaemonInForeground()
    return if (ensureDaemonServerRunning(port, respectAutoStartDisable = false)) {
      TrailblazeExitCode.SUCCESS.code
    } else {
      TrailblazeExitCode.INFRA_FAILED.code
    }
  }

  // Both use the unchecked port: they act on a daemon that is already running, and one started
  // before the device-allocation-range check existed still has to be stoppable and reportable.
  private fun doStop(): Int {
    return shutdownDaemonAndWait(parent.getRunningDaemonPortUnchecked())
  }

  private fun doStatus(): Int {
    return DaemonClient(port = parent.getRunningDaemonPortUnchecked()).use { daemon ->
      if (!daemon.isRunningBlocking()) {
        Console.log("Trailblaze daemon is not running.")
        Console.log("")
        Console.log("Start the daemon with: trailblaze app start")
        return@use TrailblazeExitCode.SUCCESS.code
      }

      val status = daemon.getStatusBlocking()
      Console.log("Trailblaze daemon is running.")
      Console.log("")
      if (status != null) {
        Console.log("  Port:              ${status.port}")
        Console.log("  Connected devices: ${status.connectedDevices}")
        Console.log("  Uptime:            ${status.uptimeSeconds.seconds}")
        if (status.version != null) {
          Console.log("  Version:           ${status.version}")
        }
        if (status.activeSessionId != null) {
          Console.log("  Active session:    ${status.activeSessionId}")
        }
      }

      TrailblazeExitCode.SUCCESS.code
    }
  }
}

/**
 * `trailblaze app start` — start the daemon without opening Trail Runner.
 *
 * The rest of the codebase (docs, KDoc, and the `is the daemon running?` recovery hints in
 * [reportDaemonUnreachable]) spells daemon startup `app start`, and the daemon auto-start spawns
 * `app start --foreground --headless`. `--headless` and `--foreground` are re-declared here so
 * they work on either side of the verb — `app --foreground start` and `app start --foreground`
 * are equivalent. `--headless` is accepted for that spawn line and changes nothing: `start`
 * never opens a window.
 *
 * **`hidden` is load-bearing, not cosmetic — do not un-hide it.** `trailblaze
 * --describe-commands` ([describeCommands]) publishes the picocli tree for wrapper CLIs to build
 * their own command tree from. A wrapper that does so — and caches the result — can treat any
 * command with visible children as a *group*, and answer a group invoked with flags but no
 * subcommand token using its own usage text and exit 0, never reaching the JVM. An installed
 * CLI's `trailblaze` on PATH may well be such a wrapper, so the moment this subcommand became
 * visible, every flags-only `trailblaze app …` form — `--headless`, `--stop`, `--status` —
 * started answering with wrapper usage instead of doing anything, and daemon auto-start
 * silently spawned a child that wrote that usage text to `daemon.log` and died. Hiding the
 * subcommand keeps `app` a leaf in the published tree while leaving `app start` fully parseable
 * here.
 */
@Command(
  name = "start",
  mixinStandardHelpOptions = true,
  hidden = true,
  description = ["Start the Trailblaze daemon without opening Trailblaze App"]
)
class AppStartCommand : Callable<Int> {

  @CommandLine.ParentCommand
  private lateinit var app: AppCommand

  @Option(
    names = ["--headless"],
    description = ["Accepted for compatibility; `start` never opens a window"]
  )
  var headless: Boolean = false

  @Option(
    names = ["--foreground"],
    description = ["Run in foreground (blocks terminal). Use for debugging with an attached IDE."]
  )
  var foreground: Boolean = false

  override fun call(): Int {
    if (app.v2) {
      Console.error("--v2 must be used as `trailblaze app --v2`.")
      return TrailblazeExitCode.MISUSE.code
    }
    // OR rather than assign: the same flag may have been given before the verb, which picocli
    // binds to the parent instead.
    if (foreground) app.foreground = true
    return app.startDaemon()
  }
}
