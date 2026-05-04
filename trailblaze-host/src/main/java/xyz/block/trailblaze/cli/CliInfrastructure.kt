package xyz.block.trailblaze.cli

import kotlinx.coroutines.runBlocking
import picocli.CommandLine
import xyz.block.trailblaze.TrailblazeVersion
import xyz.block.trailblaze.config.AppTargetYamlLoader
import xyz.block.trailblaze.config.project.TargetEntry
import xyz.block.trailblaze.config.project.TrailblazeProjectConfigLoader
import xyz.block.trailblaze.config.project.TrailblazeWorkspaceConfigResolver
import xyz.block.trailblaze.config.project.WorkspaceContentHasher
import xyz.block.trailblaze.devices.TrailblazeDevicePort
import xyz.block.trailblaze.util.Console
import java.io.File
import java.nio.file.Paths

// ---------------------------------------------------------------------------
// Shared error messages
// ---------------------------------------------------------------------------

internal const val DAEMON_NOT_RUNNING_ERROR =
  "Error: Trailblaze daemon is not running. Start it with: trailblaze app"


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
 * Per-device session scope shared by every CLI command that drives a device
 * (`blaze`, `tool`, `snapshot`, `ask`, `verify`). All five funnel into the same
 * persisted MCP session for a given device, so a sequence like
 * `blaze "Open Settings"` → `tool tap ref=p386 -o "Tap toggle"` → `ask "Is it
 * on?"` rolls up as ONE Trailblaze recording instead of N fragmented ones.
 *
 * The scope is keyed on the resolved device string passed to `--device`,
 * lowercased so `Android` and `android` collapse to the same scope. Different
 * devices stay isolated (e.g. `cli-android/emulator-5554` vs `cli-ios/SIM-X`).
 */
fun cliDeviceSessionScope(device: String): String = "cli-${device.lowercase()}"

/**
 * Tracks the last CLI session scope used on this daemon port, so that
 * `blaze --save` can locate the session that recorded the most recent CLI
 * activity even when the save invocation omits `--device`. Any CLI command
 * that drives a device should call [writeLastCliSessionScope] after a
 * successful run; [readLastCliSessionScope] is the consumer side used by
 * `blaze --save`.
 */
fun lastCliSessionScopeFile(port: Int): File =
  CliMcpClient.scopedStateFile(prefix = "trailblaze-last-cli-scope", port = port)

fun readLastCliSessionScope(port: Int): String? {
  return try {
    lastCliSessionScopeFile(port).takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null }
  } catch (_: Exception) {
    null
  }
}

fun writeLastCliSessionScope(port: Int, scope: String) {
  try {
    lastCliSessionScopeFile(port).writeText(scope)
  } catch (_: Exception) {
    // Best-effort only — `blaze --save` falls back to requiring --device.
  }
}

/**
 * Wrapper for **stateless one-shot** CLI device commands.
 *
 * Currently unused by device-driving commands — `tool`, `snapshot`, `ask`,
 * `verify`, `blaze` all use [cliReusableWithDevice] so their steps roll up
 * into one recorded session per device. Kept for future commands that
 * genuinely need an isolated, non-recorded MCP session (e.g. read-only
 * diagnostics that should not appear in the user's session list).
 *
 * Each invocation:
 *  - opens a fresh MCP session (no session file I/O),
 *  - binds the explicitly requested `--device`,
 *  - runs [action],
 *  - tears the MCP session down.
 */
fun cliOneShotWithDevice(
  verbose: Boolean,
  device: String?,
  webHeadless: Boolean = true,
  action: suspend (CliMcpClient) -> Int,
): Int {
  if (!verbose) Console.enableQuietMode()
  if (device.isNullOrBlank()) {
    Console.error("Error: --device is required for this command.")
    return CommandLine.ExitCode.USAGE
  }
  val port = CliConfigHelper.resolveEffectiveHttpPort()

  return runBlocking {
    val mcpClient = connectOrStartDaemonOneShot(port)
      ?: return@runBlocking CommandLine.ExitCode.SOFTWARE

    mcpClient.use { client ->
      val deviceError = client.ensureDevice(device, webHeadless = webHeadless)
      if (deviceError != null) {
        Console.error(deviceError)
        return@runBlocking CommandLine.ExitCode.SOFTWARE
      }
      action(client)
    }
  }
}

/**
 * Wrapper for **stateful/reusable** CLI device commands (`blaze`).
 *
 * Each invocation reattaches to the persisted MCP session under [sessionScope]
 * (creating a fresh one if there is none, or recovering from daemon restart),
 * so follow-up commands like `blaze --save` can reach the same recorded steps.
 * Device-claim conflicts follow the daemon's yield-unless-busy policy.
 */
fun cliReusableWithDevice(
  verbose: Boolean,
  device: String?,
  sessionScope: String,
  webHeadless: Boolean = true,
  action: suspend (CliMcpClient) -> Int,
): Int {
  if (!verbose) Console.enableQuietMode()
  if (device.isNullOrBlank()) {
    Console.error("Error: --device is required for this command.")
    return CommandLine.ExitCode.USAGE
  }
  val config = CliConfigHelper.getOrCreateConfig()
  val port = CliConfigHelper.resolveEffectiveHttpPort()
  val targetAppId = config.selectedTargetAppId

  return runBlocking {
    val mcpClient = connectOrStartDaemonReusable(
      port,
      targetAppId = targetAppId,
      sessionScope = sessionScope,
    ) ?: return@runBlocking CommandLine.ExitCode.SOFTWARE

    mcpClient.use { client ->
      val deviceError = client.ensureDevice(device, webHeadless = webHeadless)
      if (deviceError != null) {
        Console.error(deviceError)
        return@runBlocking CommandLine.ExitCode.SOFTWARE
      }
      // Track the last CLI scope used against this port so `blaze --save`
      // can locate the recording even when called without `--device`. We
      // write before the action so the scope is recoverable even if the
      // action fails partway — the recorded steps still belong to this
      // session.
      writeLastCliSessionScope(port, sessionScope)
      action(client)
    }
  }
}

/**
 * Shared wrapper: connect to daemon without device selection.
 *
 * Used by read-only / device-listing commands (`device list`, `toolbox`) that
 * just attach to whatever shared CLI session exists on the daemon.
 */
fun cliWithDaemon(
  verbose: Boolean,
  action: suspend (CliMcpClient) -> Int,
): Int {
  if (!verbose) Console.enableQuietMode()
  val port = CliConfigHelper.resolveEffectiveHttpPort()
  val targetAppId = CliConfigHelper.getOrCreateConfig().selectedTargetAppId

  return runBlocking {
    val mcpClient = connectOrStartDaemonReusable(port, targetAppId = targetAppId)
      ?: return@runBlocking CommandLine.ExitCode.SOFTWARE
    mcpClient.use { client -> action(client) }
  }
}

/**
 * Connect to the daemon for a one-shot command, auto-starting it if missing.
 * Never reads or writes the persisted session file.
 */
internal suspend fun connectOrStartDaemonOneShot(port: Int): CliMcpClient? {
  if (!checkAndRestartStaleDaemon(port)) {
    Console.error(
      "Error: Stale daemon (wrong version) did not stop. Kill it manually or wait and retry."
    )
    return null
  }
  warnIfWorkspaceMismatch(port)

  return try {
    CliMcpClient.connectOneShot(port)
  } catch (_: Exception) {
    if (!cliTryStartDaemon(port)) {
      Console.error(DAEMON_NOT_RUNNING_ERROR)
      return null
    }
    try {
      CliMcpClient.connectOneShot(port = port)
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
 * Connect to the daemon for a reusable workflow, auto-starting it if missing
 * and clearing the (now-stale) persisted session on first failure so the
 * retry doesn't re-discover and re-log it.
 */
internal suspend fun connectOrStartDaemonReusable(
  port: Int,
  targetAppId: String? = null,
  sessionScope: String? = null,
): CliMcpClient? {
  if (!checkAndRestartStaleDaemon(port)) {
    Console.error(
      "Error: Stale daemon (wrong version) did not stop. Kill it manually or wait and retry."
    )
    return null
  }
  warnIfWorkspaceMismatch(port)

  return try {
    CliMcpClient.connectReusable(
      port = port,
      targetAppId = targetAppId,
      sessionScope = sessionScope,
    )
  } catch (_: Exception) {
    CliMcpClient.clearSession(port, sessionScope = sessionScope)
    if (!cliTryStartDaemon(port)) {
      Console.error(DAEMON_NOT_RUNNING_ERROR)
      return null
    }
    try {
      CliMcpClient.connectReusable(
        port = port,
        targetAppId = targetAppId,
        sessionScope = sessionScope,
      )
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
 * Warn if the running daemon's workspace anchor differs from the cwd-resolved one.
 *
 * The daemon resolves its workspace once at startup based on the cwd it was launched
 * from. Subsequent CLI invocations connect to that same daemon regardless of where
 * the user `cd`'d to, so a `trailblaze toolbox` from project B silently returns
 * project A's targets if the daemon was started in project A. This produces stale
 * results that look correct but reference a different workspace.
 *
 * Auto-restarting on mismatch would be worse — it'd kill any in-flight runs another
 * shell started in project A. So we surface the mismatch as a prominent banner and
 * let the user decide. The diff (targets only-in-cwd vs only-in-daemon) tells them
 * exactly what they'd gain or lose by restarting.
 *
 * Silently no-ops in two scenarios:
 *  - Daemon isn't running: caller is about to auto-start it with the current cwd, so
 *    by definition no mismatch can exist.
 *  - Either side is in scratch mode (no `trails/config/trailblaze.yaml` discovered):
 *    workspace mismatch is undefined when there's no workspace.
 */
private fun warnIfWorkspaceMismatch(port: Int) {
  val status = try {
    DaemonClient(port = port).use { it.getStatusBlocking() }
  } catch (_: Exception) {
    return
  } ?: return
  val daemonAnchor = status.workspaceAnchor ?: return // daemon scratch mode

  val cwdAnchor = try {
    TrailblazeWorkspaceConfigResolver.resolveConfigFile(Paths.get(""))?.absolutePath
  } catch (_: Exception) {
    return
  } ?: return // cwd scratch mode

  // Canonicalize both sides so symlinked clones don't trigger spurious warnings.
  val daemonReal = canonicalize(daemonAnchor)
  val cwdReal = canonicalize(cwdAnchor)

  if (daemonReal != cwdReal) {
    warnAnchorMismatch(daemonAnchor, cwdAnchor)
    return
  }

  // Same anchor — check for content drift (user edited a pack.yaml since the daemon
  // started, daemon still running on stale dist output).
  val daemonHash = status.workspaceContentHash ?: return
  val cwdHash = computeCwdContentHash(File(cwdAnchor)) ?: return
  if (daemonHash != cwdHash) {
    warnContentDrift(cwdAnchor)
  }
}

private fun warnAnchorMismatch(daemonAnchor: String, cwdAnchor: String) {
  if (Console.isQuietMode()) return // Scripted/--quiet callers opt out of advisory banners.
  val daemonTargets = loadTargetIds(File(daemonAnchor))
  val cwdTargets = loadTargetIds(File(cwdAnchor))
  emitWarningBanner(buildAnchorMismatchBanner(daemonAnchor, cwdAnchor, daemonTargets, cwdTargets))
}

private fun warnContentDrift(anchor: String) {
  if (Console.isQuietMode()) return // Scripted/--quiet callers opt out of advisory banners.
  emitWarningBanner(buildContentDriftBanner(anchor))
}

/**
 * Stderr so the banner is visible even when stdout is being piped (e.g.
 * `trailblaze toolbox ... | grep`). Repeated equals-banners make this hard to miss in
 * a terminal. Pure emission of an already-built banner — call sites build the lines via
 * [buildAnchorMismatchBanner] / [buildContentDriftBanner] so the formatting is unit-
 * testable without redirecting Console.
 */
private fun emitWarningBanner(lines: List<String>) {
  for (line in lines) Console.error(line)
}

/**
 * Builds the banner emitted by [warnAnchorMismatch] as an in-memory list of lines so
 * tests can assert on its content without redirecting Console. Production callers feed
 * the result into [emitWarningBanner].
 *
 * Visible for testing.
 */
internal fun buildAnchorMismatchBanner(
  daemonAnchor: String,
  cwdAnchor: String,
  daemonTargets: Set<String>,
  cwdTargets: Set<String>,
): List<String> {
  val onlyInCwd = (cwdTargets - daemonTargets).sorted()
  val onlyInDaemon = (daemonTargets - cwdTargets).sorted()
  val bar = "═".repeat(72)
  val out = mutableListOf<String>()
  out += ""
  out += bar
  out += "  ⚠️  WORKSPACE MISMATCH — daemon and your cwd resolved to different anchors"
  out += bar
  out += "  Daemon was started against:  $daemonAnchor"
  out += "  Your cwd resolves to:        $cwdAnchor"
  out += ""
  if (onlyInCwd.isNotEmpty()) {
    out += "  Targets you would gain by restarting (only in cwd workspace):"
    onlyInCwd.forEach { out += "    + $it" }
  }
  if (onlyInDaemon.isNotEmpty()) {
    if (onlyInCwd.isNotEmpty()) out += ""
    out += "  Targets the daemon currently shows (only in daemon's workspace):"
    onlyInDaemon.forEach { out += "    - $it" }
  }
  if (onlyInCwd.isEmpty() && onlyInDaemon.isEmpty()) {
    out += "  Target lists are identical, but workspace anchors differ — packs,"
    out += "  tools, and toolsets may still resolve from different files."
  }
  out += ""
  out += "  To switch to the cwd workspace:"
  out += "    trailblaze app --stop"
  out += "    <re-run your command>"
  out += ""
  out += "  Restart is not automatic because it would kill any in-flight runs"
  out += "  another shell started against the daemon's current workspace."
  out += bar
  out += ""
  return out
}

/**
 * Builds the banner emitted by [warnContentDrift] as an in-memory list of lines so tests
 * can assert on it without redirecting Console.
 *
 * Visible for testing.
 */
internal fun buildContentDriftBanner(anchor: String): List<String> {
  val bar = "═".repeat(72)
  val out = mutableListOf<String>()
  out += ""
  out += bar
  out += "  ⚠️  WORKSPACE CONTENT DRIFT — files changed since daemon started"
  out += bar
  out += "  Workspace: $anchor"
  out += ""
  out += "  One or more files under `trails/config/` have been edited since the"
  out += "  daemon loaded this workspace. The daemon is still serving the OLD"
  out += "  state — your edits to packs, tool YAMLs, scripts, toolsets, providers,"
  out += "  or `trailblaze.yaml` itself are not visible until it restarts."
  out += ""
  out += "  To pick up the changes:"
  out += "    trailblaze app --stop"
  out += "    <re-run your command>"
  out += ""
  out += "  Restart is not automatic because it would kill any in-flight runs."
  out += bar
  out += ""
  return out
}

/**
 * Compute the cwd workspace's content hash using the same algorithm the daemon
 * captures at startup. Walks every non-excluded file under `<configDir>/` —
 * `pack.yaml`, tool YAMLs, scripts, the workspace anchor itself — so any edit
 * the daemon would have to be restarted to pick up shows up as a different hash
 * here. Returns null when the configDir is unreadable; we skip the drift check
 * rather than fire a noisy warning.
 */
private fun computeCwdContentHash(anchorFile: File): String? = try {
  val configDir = anchorFile.parentFile ?: return null
  WorkspaceContentHasher.compute(configDir, TrailblazeVersion.version)
} catch (_: Exception) {
  null
}

private fun canonicalize(path: String): String = try {
  File(path).canonicalPath
} catch (_: Exception) {
  path
}

private fun loadTargetIds(anchorFile: File): Set<String> = try {
  TrailblazeProjectConfigLoader.loadResolved(anchorFile)
    ?.targets
    ?.mapNotNull { (it as? TargetEntry.Inline)?.config?.id }
    ?.toSet()
    .orEmpty()
} catch (_: Exception) {
  emptySet()
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
    // Daemon not running or status check failed — will be handled by the connect-or-start helpers
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
