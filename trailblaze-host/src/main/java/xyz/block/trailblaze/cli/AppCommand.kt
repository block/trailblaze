package xyz.block.trailblaze.cli

import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import xyz.block.trailblaze.devices.TrailblazeDevicePort
import xyz.block.trailblaze.ui.TrailblazeDesktopUtil
import xyz.block.trailblaze.ui.TrailblazePortManager
import xyz.block.trailblaze.util.Console
import java.util.concurrent.Callable
import kotlin.time.Duration.Companion.seconds

/**
 * Launch, stop, or check the status of the Trailblaze application.
 *
 * Examples:
 *   trailblaze app                 - Launch desktop GUI
 *   trailblaze app --headless      - Start headless daemon (no GUI)
 *   trailblaze app --stop          - Stop the daemon
 *   trailblaze app --status        - Check if daemon is running
 */
@Command(
  name = "app",
  mixinStandardHelpOptions = true,
  description = ["Start or stop the Trailblaze daemon (background service that drives devices)"]
)
open class AppCommand : Callable<Int> {

  @CommandLine.ParentCommand
  private lateinit var parent: TrailblazeCliCommand

  @Option(
    names = ["--headless"],
    description = ["Start in headless mode (daemon only, no GUI)"]
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
    description = ["Run in foreground (blocks terminal). Use for debugging with an attached IDE."]
  )
  var foreground: Boolean = false

  override fun call(): Int {
    return when {
      stop -> doStop()
      status -> doStatus()
      foreground -> parent.launchDesktop(headless)
      else -> launchInBackground()
    }
  }

  /**
   * Spawn the app as a background process and return control to the terminal.
   * The spawned process uses `--foreground` to run in-process.
   */
  private fun launchInBackground(): Int {
    val port = parent.getEffectivePort()

    // Single DaemonClient instance for all checks in this method.
    return DaemonClient(port = port).use { daemon ->
      // If already running, show window or report status
      if (daemon.isRunningBlocking()) {
        if (!headless) {
          daemon.showWindowBlocking()
        }
        Console.log("Trailblaze is already running on port $port.")
        return@use CommandLine.ExitCode.OK
      }

      // Find the launcher script to spawn as a background process
      val launcher = findTrailblazeLauncher() ?: run {
        // Can't find launcher (e.g., running from IDE without a shell script) — fall back to
        // in-process mode so the app still starts.
        return@use parent.launchDesktop(headless)
      }

      // Route the child's stdout/stderr to ~/.trailblaze/daemon.log instead of
      // discarding. The daemon runs detached so we can't wait on its pipes, and
      // without a log file the parent has no way to surface startup failures —
      // `waitForDaemon` just times out with zero context. Appended so repeated
      // launches in a CI agent retain history. Path centralized in
      // TrailblazeDesktopUtil to keep daemon, MCP proxy, and tooling agreeing
      // on a single canonical location.
      val daemonLogFile = TrailblazeDesktopUtil.getDaemonLogFile()

      Console.log("Starting Trailblaze${if (headless) " daemon" else ""}...")
      Console.log("Daemon log: ${daemonLogFile.absolutePath}")
      try {
        val command = mutableListOf(launcher.absolutePath, "app", "--foreground")
        if (headless) command.add("--headless")

        val pb = ProcessBuilder(command)
        if (port != TrailblazeDevicePort.TRAILBLAZE_DEFAULT_HTTP_PORT) {
          pb.environment()[TrailblazePortManager.HTTP_PORT_ENV_VAR] = port.toString()
        }
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(daemonLogFile))
        pb.redirectError(ProcessBuilder.Redirect.appendTo(daemonLogFile))
        pb.start()
      } catch (e: Exception) {
        Console.error("Failed to start: ${e.message}")
        return@use CommandLine.ExitCode.SOFTWARE
      }

      // Wait for daemon to be ready (progress dots so the user knows it's working)
      Console.appendInfo("Waiting for Trailblaze daemon to be ready")
      val started = daemon.waitForDaemon { Console.appendInfo(".") }
      Console.info("") // newline after dots
      if (started) {
        Console.log("Trailblaze${if (headless) " daemon" else ""} started on port $port.")
      } else {
        // Re-check: another process may have raced us and already started the daemon,
        // but our spawn failed silently. If the daemon is now running, treat it as
        // success rather than reporting a confusing error.
        if (daemon.isRunningBlocking()) {
          Console.log("Trailblaze${if (headless) " daemon" else ""} started on port $port.")
        } else {
          Console.error("Trailblaze did not start within 30s. If a source build is in progress it may need more time.")
          Console.error("Daemon log: ${daemonLogFile.absolutePath}")
          Console.error("Run with --foreground to see startup output directly.")
          return@use CommandLine.ExitCode.SOFTWARE
        }
      }
      CommandLine.ExitCode.OK
    }
  }

  private fun doStop(): Int {
    return shutdownDaemonAndWait(parent.getEffectivePort())
  }

  private fun doStatus(): Int {
    return DaemonClient(port = parent.getEffectivePort()).use { daemon ->
      if (!daemon.isRunningBlocking()) {
        Console.log("Trailblaze daemon is not running.")
        Console.log("")
        Console.log("Start the daemon with: trailblaze app")
        return@use CommandLine.ExitCode.OK
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

      CommandLine.ExitCode.OK
    }
  }
}
