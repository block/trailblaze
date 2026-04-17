package xyz.block.trailblaze.cli

import kotlinx.coroutines.runBlocking
import picocli.CommandLine
import xyz.block.trailblaze.TrailblazeVersion
import xyz.block.trailblaze.config.AppTargetYamlLoader
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDevicePort
import xyz.block.trailblaze.util.Console

// ---------------------------------------------------------------------------
// Shared error messages
// ---------------------------------------------------------------------------

internal const val DAEMON_NOT_RUNNING_ERROR =
  "Error: Trailblaze daemon is not running. Start it with: trailblaze app"

// ---------------------------------------------------------------------------
// Setup checks (moved from TrailblazeCli.kt)
// ---------------------------------------------------------------------------

/**
 * Checks that initial setup is complete.
 * Config auto-creates with defaults — no mandatory setup step needed.
 * Target is optional (sticky), mode is per-session (--solo flag).
 */
fun checkSetupComplete(): String? {
  val config = CliConfigHelper.getOrCreateConfig()
  if (config.selectedTargetAppId == null) {
    val targets = discoverTargetSummaries()
    val targetList = if (targets.isNotEmpty()) {
      "\n\nAvailable targets:\n" +
        targets.joinToString("\n") { (id, name) -> "  [$id] $name" }
    } else {
      ""
    }
    return "No target configured. Set one with:\n" +
      "  trailblaze config target <name>" + targetList
  }
  return null
}

/**
 * Lightweight classpath scan for target IDs and display names — no full app init needed.
 */
internal fun discoverTargetSummaries(): List<Pair<String, String>> {
  return AppTargetYamlLoader.discoverConfigs()
    .map { it.id to it.displayName }
    .sortedBy { it.second }
}

// ---------------------------------------------------------------------------
// Daemon connection helpers (shared by both CLIs)
// ---------------------------------------------------------------------------

/**
 * Shared wrapper: connect to daemon, ensure device, run action.
 * Both `./blaze` and `./trailblaze` commands use this.
 */
fun cliWithDevice(
  verbose: Boolean,
  device: String?,
  action: suspend (CliMcpClient) -> Int,
): Int {
  val setupError = checkSetupComplete()
  if (setupError != null) {
    Console.error(setupError)
    return CommandLine.ExitCode.SOFTWARE
  }

  if (!verbose) Console.enableQuietMode()
  val config = CliConfigHelper.readConfig()
  val port = CliConfigHelper.resolveEffectiveHttpPort()
  val targetAppId = config?.selectedTargetAppId

  return runBlocking {
    val mcpClient = connectOrStartDaemon(port, targetAppId = targetAppId)
      ?: return@runBlocking CommandLine.ExitCode.SOFTWARE

    mcpClient.use { client ->
      val effectiveDevice = device ?: config?.cliDevicePlatform
      val deviceError = client.ensureDevice(effectiveDevice)
      if (deviceError != null) {
        Console.error(deviceError)
        return@runBlocking CommandLine.ExitCode.SOFTWARE
      }
      // Save the device platform to config so subsequent commands default to it
      if (device != null) {
        val platformStr = device.split("/", limit = 2)[0]
        if (TrailblazeDevicePlatform.fromString(platformStr) != null) {
          CliConfigHelper.updateConfig { it.copy(cliDevicePlatform = platformStr.uppercase()) }
        }
      }
      action(client)
    }
  }
}

/**
 * Shared wrapper: connect to daemon without device selection.
 */
fun cliWithDaemon(
  verbose: Boolean,
  action: suspend (CliMcpClient) -> Int,
): Int {
  val setupError = checkSetupComplete()
  if (setupError != null) {
    Console.error(setupError)
    return CommandLine.ExitCode.SOFTWARE
  }

  if (!verbose) Console.enableQuietMode()
  val port = CliConfigHelper.resolveEffectiveHttpPort()
  val targetAppId = CliConfigHelper.readConfig()?.selectedTargetAppId

  return runBlocking {
    val mcpClient = connectOrStartDaemon(port, targetAppId = targetAppId)
      ?: return@runBlocking CommandLine.ExitCode.SOFTWARE
    mcpClient.use { client -> action(client) }
  }
}

/**
 * Connect to an existing daemon, or auto-start one in headless mode.
 * If the daemon is running but has a different version, restart it.
 *
 * @param targetAppId Current target app from config. Passed to [CliMcpClient.connectToDaemon]
 *   so sessions are invalidated when the target changes (different targets use different
 *   drivers and custom tools).
 */
internal suspend fun connectOrStartDaemon(
  port: Int,
  targetAppId: String? = null,
): CliMcpClient? {
  // Check for version mismatch and restart stale daemon
  if (!checkAndRestartStaleDaemon(port)) {
    Console.error(
      "Error: Stale daemon (wrong version) did not stop. Kill it manually or wait and retry."
    )
    return null
  }

  return try {
    CliMcpClient.connectToDaemon(port, targetAppId = targetAppId)
  } catch (_: Exception) {
    // Clear the stale session file so the retry doesn't re-discover and re-log it
    CliMcpClient.clearSession(port)
    if (!cliTryStartDaemon(port)) {
      Console.error(DAEMON_NOT_RUNNING_ERROR)
      return null
    }
    try {
      CliMcpClient.connectToDaemon(port, targetAppId = targetAppId)
    } catch (_: Exception) {
      Console.error(
        "Error: Failed to connect to Trailblaze daemon after starting it." +
          " Try running: trailblaze app"
      )
      null
    }
  }
}

/**
 * Check if the running daemon has a different version than the CLI.
 * If so, stop it so it gets restarted with the current version.
 *
 * @return true if the caller may proceed (no stale daemon blocking the port),
 *         false if a stale daemon could not be stopped.
 */
private fun checkAndRestartStaleDaemon(port: Int): Boolean {
  val cliVersion = TrailblazeVersion.displayVersion
  if (cliVersion == "Developer Build") return true // Can't compare dev builds

  try {
    DaemonClient(port = port).use { daemon ->
      val status = daemon.getStatusBlocking() ?: return true
      val daemonVersion = status.version
      if (daemonVersion != null && daemonVersion != cliVersion) {
        Console.log(
          "Restarting daemon (version mismatch: daemon=$daemonVersion, cli=$cliVersion)..."
        )
        daemon.shutdownBlocking()
        // Wait for daemon to stop
        repeat(20) {
          if (!daemon.isRunningBlocking()) return true
          Thread.sleep(500)
        }
        // Timed out — stale daemon is still running
        return false
      }
    }
  } catch (_: Exception) {
    // Daemon not running or status check failed — will be handled by connectOrStartDaemon
  }
  return true
}

/**
 * Auto-start the Trailblaze daemon in headless mode.
 */
private fun cliTryStartDaemon(port: Int): Boolean {
  val launcher = findTrailblazeLauncher() ?: run {
    Console.error("Cannot auto-start daemon: trailblaze launcher not found.")
    return false
  }

  Console.log("Starting Trailblaze daemon...")
  try {
    val pb = ProcessBuilder(launcher.absolutePath, "app", "--foreground", "--headless")
    if (port != TrailblazeDevicePort.TRAILBLAZE_DEFAULT_HTTP_PORT) {
      pb.environment()["TRAILBLAZE_PORT"] = port.toString()
    }
    pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)
    pb.redirectError(ProcessBuilder.Redirect.DISCARD)
    pb.start()
  } catch (e: Exception) {
    Console.error("Failed to start daemon: ${e.message}")
    return false
  }

  Console.appendInfo("Waiting for Trailblaze daemon to be ready")
  val started = DaemonClient(port = port).use {
    it.waitForDaemon { Console.appendInfo(".") }
  }
  Console.info("") // newline after dots
  if (started) {
    Console.log("Trailblaze daemon started.")
  } else {
    Console.error("Daemon did not start within 30s. If a source build is in progress it may need more time.")
    Console.error("Run with --foreground to see startup output directly.")
  }
  return started
}

// ---------------------------------------------------------------------------
// Shared daemon shutdown helper
// ---------------------------------------------------------------------------

/**
 * Shut down the daemon, wait for it to stop, and clear the CLI session.
 *
 * @return [CommandLine.ExitCode.OK] on success, [CommandLine.ExitCode.SOFTWARE] on failure.
 */
fun shutdownDaemonAndWait(port: Int): Int {
  DaemonClient(port = port).use { daemon ->
    if (!daemon.isRunningBlocking()) {
      Console.log("Trailblaze daemon is not running.")
      return CommandLine.ExitCode.OK
    }

    Console.log("Stopping Trailblaze daemon...")
    val response = daemon.shutdownBlocking()
    if (!response.success) {
      Console.error("Failed to stop daemon: ${response.message}")
      return CommandLine.ExitCode.SOFTWARE
    }

    Console.appendLog("Waiting for daemon to stop")
    repeat(20) {
      if (!daemon.isRunningBlocking()) {
        Console.log("")
        Console.log("Trailblaze daemon stopped.")
        CliMcpClient.clearSession(port)
        return CommandLine.ExitCode.OK
      }
      Console.appendLog(".")
      Thread.sleep(500)
    }
    Console.log("")
    Console.error("Daemon did not stop gracefully.")
    return CommandLine.ExitCode.SOFTWARE
  }
}

// ---------------------------------------------------------------------------
// Target helper
// ---------------------------------------------------------------------------

/**
 * Apply --target to sticky config if provided.
 */
internal fun applyBlazeTarget(target: String?) {
  if (target != null) {
    CliConfigHelper.updateConfig { it.copy(selectedTargetAppId = target.lowercase()) }
  }
}
