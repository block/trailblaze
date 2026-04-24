package xyz.block.trailblaze.cli

import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.IVersionProvider
import xyz.block.trailblaze.TrailblazeVersion
import xyz.block.trailblaze.desktop.TrailblazeDesktopAppConfig
import xyz.block.trailblaze.devices.TrailblazeDevicePort
import xyz.block.trailblaze.logs.server.endpoints.CliExecRequest
import xyz.block.trailblaze.logs.server.endpoints.CliExecResponse
import xyz.block.trailblaze.ui.TrailblazeDesktopApp
import xyz.block.trailblaze.ui.TrailblazePortManager
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.canRunDesktopGui
import kotlinx.coroutines.CancellationException
import java.io.ByteArrayOutputStream
import java.util.concurrent.Callable
import kotlin.system.exitProcess

/** CLI output divider width. */
private const val DIVIDER_WIDTH = 60

/** Section divider for major boundaries (start/end of a trail run). */
internal val SECTION_DIVIDER = "=".repeat(DIVIDER_WIDTH)

/** Item divider for minor boundaries (between trail files in a batch). */
internal val ITEM_DIVIDER = "-".repeat(DIVIDER_WIDTH)

/** Max time to wait for all selector-based tool logs to arrive before generating a recording. */
internal const val RECORDING_LOG_STABILITY_MAX_WAIT_MS = 120_000L

/** Poll interval when waiting for selector-based tool logs. */
internal const val RECORDING_LOG_STABILITY_POLL_MS = 2_000L

/**
 * Shared CLI infrastructure for Trailblaze desktop applications.
 *
 * Both open source and internal versions use this shared CLI with their
 * specific [TrailblazeDesktopApp] implementation.
 *
 * Usage:
 *   trailblaze                     - Show help
 *   trailblaze app                 - Launch desktop GUI
 *   trailblaze app --headless      - Start headless daemon
 *   trailblaze config target myapp                                 - Set target app
 *   trailblaze app --stop          - Stop the daemon
 *   trailblaze app --status        - Check daemon status
 *   trailblaze blaze "objective"   - Take the next step toward an objective on a connected device
 *   trailblaze ask "question"      - Ask about what's visible on screen
 *   trailblaze trail <file>         - Run a .trail.yaml file
 *   trailblaze session end         - End the CLI session
 *   trailblaze mcp                 - Start MCP server (STDIO transport + tray icon)
 *   trailblaze report              - Generate HTML report for all sessions
 *   trailblaze device               - List connected devices
 *   TRAILBLAZE_PORT=52526 trailblaze - Launch on a custom port (allows multiple instances)
 *   trailblaze --help              - Show all commands and options
 */
object TrailblazeCli {

  /**
   * Main entry point for the CLI.
   *
   * @param args Command line arguments
   * @param appProvider Factory function to create the app instance
   * @param configProvider Factory function to get the config (for CLI commands that need it before app creation)
   */
  /**
   * Providers captured by [run] and reused by [executeForDaemon] when the daemon
   * is asked to run a CLI subcommand in-process via the `/cli/exec` fast path.
   */
  @Volatile private var appProviderRef: (() -> TrailblazeDesktopApp)? = null
  @Volatile private var configProviderRef: (() -> TrailblazeDesktopAppConfig)? = null

  fun run(
    args: Array<String>,
    appProvider: () -> TrailblazeDesktopApp,
    configProvider: () -> TrailblazeDesktopAppConfig,
  ) {
    // Install thread-local stdout/stderr capture BEFORE any code references
    // `System.out` — Console.jvm.kt caches it into a field at class init time,
    // so the capture has to be in place before the first Console.log call.
    CliOutCapture.install()

    appProviderRef = appProvider
    configProviderRef = configProvider

    // Suppress SLF4J "multiple providers" warnings on stderr.
    // Must be set before any SLF4J class is loaded.
    System.setProperty("slf4j.internal.verbosity", "ERROR")

    // Install Java-level System.out/err log capture as early as possible so
    // Console.log() output is saved regardless of how Trailblaze was launched
    // (IDE, JAR, shell script, etc.).
    //
    // Skip for STDIO MCP mode: stdout must be a pristine JSON-RPC stream.
    // The DesktopLogFileWriter tee would wrap stdout and leak non-JSON output
    // (its own "Logging to ..." message) before Console.useStdErr() runs.
    val isStdioMode = args.contains("mcp") && !args.contains("--http")
    val isDescribeCommands = args.contains("--describe-commands")
    if (!isStdioMode && !isDescribeCommands) {
      val httpPort = resolvePortFromArgs()
      DesktopLogFileWriter.install(httpPort = httpPort)
    }

    val cli = TrailblazeCliCommand(appProvider, configProvider)
    val commandLine = CommandLine(cli).setCaseInsensitiveEnumValuesAllowed(true)

    // Replace the default flat command list with grouped sections.
    commandLine.helpSectionMap[CommandLine.Model.UsageMessageSpec.SECTION_KEY_COMMAND_LIST] =
      GroupedCommandListRenderer()

    // Support `sq` CLI integration: output JSON describing subcommands and exit.
    // Must stay above anything that could trigger AdbPathResolver.ADB_COMMAND (lazy),
    // since its `Console.log` output would leak onto stdout before Console.useStdErr()
    // runs in --direct STDIO mode.
    if (args.contains("--describe-commands")) {
      println(commandLine.describeCommands())
      return
    }

    val exitCode = commandLine.execute(*args)
    if (exitCode != 0) {
      exitProcess(exitCode)
    }
  }

  /**
   * Lightweight pre-scan to resolve the HTTP port before picocli runs.
   *
   * Precedence: `TRAILBLAZE_PORT` env var → default.
   */
  private fun resolvePortFromArgs(): Int {
    return System.getenv(TrailblazePortManager.HTTP_PORT_ENV_VAR)?.toIntOrNull()
      ?: TrailblazeDevicePort.TRAILBLAZE_DEFAULT_HTTP_PORT
  }

  /**
   * Subcommands safe to run in-process on the daemon via `/cli/exec`. The rest
   * of the set must keep going through the existing JVM path because they either
   * directly touch desktop UI state (`app`), call `kotlin.system.exitProcess`
   * inside their `call()` (would kill the daemon), or need the caller's cwd/env
   * (`config`, `trail` with relative paths).
   *
   * Snapshot and ask are thin wrappers over MCP tool calls with no env-var
   * reads, so running them in-process is a pure win: we skip the JVM cold
   * start and hit the local MCP bridge over loopback HTTP.
   *
   * `verify` is intentionally NOT on this list even though it would benefit:
   * `VerifyCommand` reads `BLAZE_FAST` from the caller's environment, but the
   * IPC request only forwards argv, so a forwarded `BLAZE_FAST=1 verify ...`
   * would silently run in non-fast mode. Adding env forwarding is a follow-up
   * (would also unlock `blaze`/`tool`, which read the same var).
   */
  private val FORWARDABLE_SUBCOMMANDS = setOf("snapshot", "ask")

  /**
   * Serializes in-process CLI executions on the daemon. The thread-local
   * capture in [CliOutCapture] is per-thread, but commands still share picocli's
   * cached `AppCommand.parent` state on the root [TrailblazeCliCommand], so
   * running two at once against the same root would race on initialization.
   * Sequential execution is correct and sufficient for the expected load
   * (interactive shell + occasional scripts).
   */
  private val execLock = Any()

  /**
   * Entry point for the `/cli/exec` daemon endpoint. Runs [args] through a
   * fresh picocli [TrailblazeCliCommand] with stdout/stderr captured per-thread
   * and returns the captured output plus exit code. Returns `forwarded=false`
   * when the subcommand isn't in [FORWARDABLE_SUBCOMMANDS] so the CLI shim can
   * fall back to its normal JVM path.
   */
  fun executeForDaemon(request: CliExecRequest): CliExecResponse {
    val args = request.args
    val first = args.firstOrNull()
    if (first == null || first !in FORWARDABLE_SUBCOMMANDS) {
      return CliExecResponse(stdout = "", stderr = "", exitCode = 0, forwarded = false)
    }

    val appProvider = appProviderRef
    val configProvider = configProviderRef
    if (appProvider == null || configProvider == null) {
      return CliExecResponse(
        stdout = "",
        stderr = "cli/exec: daemon missing providers (run() not called)\n",
        exitCode = 1,
        forwarded = true,
      )
    }

    val stdoutBuf = CappedByteArrayOutputStream(MAX_CAPTURE_BYTES)
    val stderrBuf = CappedByteArrayOutputStream(MAX_CAPTURE_BYTES)

    val exitCode = synchronized(execLock) {
      // Save/restore the global `Console.quietMode` flag. `cliWithDevice(verbose=false)`
      // flips it for the duration of the CLI command, but there's no reset path in
      // the CLI itself; without this wrapper the long-lived daemon would go silent
      // for every Console.log after the first forwarded invocation. Save-restore is
      // safer than unconditional `disableQuietMode()` because a future daemon
      // feature could legitimately set quiet mode outside this scope.
      val priorQuiet = Console.isQuietMode()
      CliOutCapture.withCapture(stdoutBuf, stderrBuf) {
        val cli = TrailblazeCliCommand(appProvider, configProvider)
        val commandLine = CommandLine(cli).setCaseInsensitiveEnumValuesAllowed(true)
        commandLine.helpSectionMap[CommandLine.Model.UsageMessageSpec.SECTION_KEY_COMMAND_LIST] =
          GroupedCommandListRenderer()
        try {
          commandLine.execute(*args.toTypedArray())
        } catch (e: CancellationException) {
          throw e
        } catch (e: Throwable) {
          System.err.println("cli/exec: uncaught ${e::class.simpleName}: ${e.message}")
          1
        } finally {
          if (priorQuiet) Console.enableQuietMode() else Console.disableQuietMode()
        }
      }
    }

    return CliExecResponse(
      stdout = stdoutBuf.toString(Charsets.UTF_8),
      stderr = stderrBuf.toString(Charsets.UTF_8),
      exitCode = exitCode,
      forwarded = true,
    )
  }

  /**
   * Cap on captured stdout/stderr per forwarded CLI invocation. A pathological
   * `snapshot` on a dense UI tree or a tight error-logging loop shouldn't be
   * able to OOM the daemon by producing megabytes of output.
   */
  private const val MAX_CAPTURE_BYTES: Int = 4 * 1024 * 1024 // 4 MiB

  /**
   * [java.io.ByteArrayOutputStream] that silently stops accepting bytes once
   * [limit] is reached and appends a truncation marker the first time it
   * happens. The surrounding picocli command is unaware — its `println` calls
   * just become no-ops — which is the right behavior for the daemon fast path
   * (stopping execution on output overflow would be a bigger surprise).
   */
  private class CappedByteArrayOutputStream(val limit: Int) : ByteArrayOutputStream() {
    private var truncated: Boolean = false
    private val marker: ByteArray = "\n[cli/exec: output truncated at $limit bytes]\n"
      .toByteArray(Charsets.UTF_8)

    @Synchronized override fun write(b: Int) {
      if (tryTruncate(1)) return
      super.write(b)
    }

    @Synchronized override fun write(b: ByteArray, off: Int, len: Int) {
      if (tryTruncate(len)) return
      val remaining = limit - size()
      if (remaining <= 0) return
      super.write(b, off, minOf(len, remaining))
      if (size() >= limit) tryTruncate(0)
    }

    private fun tryTruncate(incoming: Int): Boolean {
      if (size() + incoming <= limit) return false
      if (!truncated) {
        truncated = true
        super.write(marker, 0, marker.size)
      }
      return true
    }
  }
}

/** Provides the version string dynamically from [TrailblazeVersion]. */
class TrailblazeVersionProvider : IVersionProvider {
  override fun getVersion(): Array<String> =
    arrayOf("Trailblaze ${TrailblazeVersion.displayVersion}")
}

/**
 * Main Trailblaze CLI command.
 */
@Command(
  name = "trailblaze",
  mixinStandardHelpOptions = true,
  versionProvider = TrailblazeVersionProvider::class,
  description = ["Trailblaze - AI-powered device automation"],
  commandListHeading = "%n", // Suppress default "Commands:" — GroupedCommandListRenderer handles it
  subcommands = [
    BlazeCommand::class,
    AskCommand::class,
    VerifyCommand::class,
    SnapshotCommand::class,
    ToolCommand::class,
    ToolboxCommand::class,
    TrailCommand::class,
    SessionCommand::class,
    ReportCommand::class,
    ConfigCommand::class,
    DeviceCommand::class,
    AppCommand::class,
    McpCommand::class,
  ]
)
class TrailblazeCliCommand(
  internal val appProvider: () -> TrailblazeDesktopApp,
  internal val configProvider: () -> TrailblazeDesktopAppConfig,
) : Callable<Int> {

  /**
   * Returns the effective HTTP port.
   *
   * Precedence: saved settings (per-install) → TRAILBLAZE_PORT env var → default (52525)
   */
  fun getEffectivePort(): Int = CliConfigHelper.resolveEffectiveHttpPort()

  /**
   * Returns the effective HTTPS port.
   *
   * Precedence: saved settings (per-install) → TRAILBLAZE_HTTPS_PORT env var → default (8443)
   */
  fun getEffectiveHttpsPort(): Int = CliConfigHelper.resolveEffectiveHttpsPort()

  /**
   * Whether any port override is active (from env var or saved settings).
   */
  fun hasPortOverride(): Boolean {
    return getEffectivePort() != TrailblazeDevicePort.TRAILBLAZE_DEFAULT_HTTP_PORT ||
        getEffectiveHttpsPort() != TrailblazeDevicePort.TRAILBLAZE_DEFAULT_HTTPS_PORT
  }

  override fun call(): Int {
    // No subcommand → show help. Use `trailblaze app` to launch the desktop GUI.
    CommandLine(this).usage(System.out)
    return CommandLine.ExitCode.OK
  }

  /**
   * Core desktop launch logic used by [AppCommand].
   */
  internal fun launchDesktop(headless: Boolean): Int {
    // The desktop GUI requires macOS with a display — auto-fallback to headless on other platforms
    val effectiveHeadless = if (!headless && !canRunDesktopGui()) {
      Console.log("Desktop GUI not available on this platform — starting in headless mode.")
      true
    } else {
      headless
    }

    // Check if Trailblaze is already running.
    // Note: "show window" is handled by AppCommand.launchInBackground() before this method
    // is called. This path runs in --foreground mode (background process or launcher-not-found
    // fallback), so just attach to an existing daemon if present.
    val daemon = DaemonClient(port = getEffectivePort())
    if (daemon.isRunningBlocking()) {
      val response = daemon.showWindowBlocking()
      if (response.success) {
        Console.log("Window shown.")
        return CommandLine.ExitCode.OK
      }

      // Daemon is running but has no window (e.g., started by `trailblaze mcp`).
      // Start the desktop GUI alongside the existing daemon — it will skip starting
      // a second HTTP server since the daemon is already handling that.
      Console.log("Trailblaze server is running. Starting desktop GUI...")
    }

    // Apply port overrides to settings if any non-default ports are active
    val app = appProvider()
    if (hasPortOverride()) {
      app.applyPortOverrides(httpPort = getEffectivePort(), httpsPort = getEffectiveHttpsPort())
    }

    // Start the app (GUI or headless based on flag)
    app.startTrailblazeDesktopApp(headless = effectiveHeadless)
    return CommandLine.ExitCode.OK
  }
}

/**
 * Custom help section renderer that groups subcommands under headings
 * instead of a flat alphabetical list.
 */
internal class GroupedCommandListRenderer : CommandLine.IHelpSectionRenderer {

  private data class Group(val heading: String, val commands: List<String>)

  private val groups = listOf(
    Group(
      "Blaze:",
      listOf("blaze", "ask", "verify", "snapshot", "tool", "toolbox"),
    ),
    Group(
      "Trail:",
      listOf("trail", "session", "report"),
    ),
    Group(
      "Setup:",
      listOf("config", "device", "app", "mcp"),
    ),
  )

  override fun render(help: CommandLine.Help): String {
    val subcommands = help.subcommands()
    if (subcommands.isEmpty()) return ""

    val sb = StringBuilder()
    val listed = mutableSetOf<String>()

    for (group in groups) {
      val cmds = group.commands.mapNotNull { name ->
        subcommands[name]?.also { listed.add(name) }
      }
      if (cmds.isEmpty()) continue
      sb.appendLine(group.heading)
      for (cmd in cmds) {
        val name = cmd.commandSpec().name()
        val desc = cmd.commandSpec().usageMessage().description().firstOrNull() ?: ""
        sb.appendLine("  %-12s %s".format(name, desc))
      }
      sb.appendLine()
    }

    // Include any unlisted commands (e.g., hidden or newly added)
    val unlisted = subcommands.keys.filter { it !in listed && it != "help" }
    if (unlisted.isNotEmpty()) {
      sb.appendLine("Other:")
      for (name in unlisted) {
        val cmd = subcommands[name] ?: continue
        val desc = cmd.commandSpec().usageMessage().description().firstOrNull() ?: ""
        sb.appendLine("  %-12s %s".format(name, desc))
      }
      sb.appendLine()
    }

    // Getting started footer
    sb.appendLine("Users:")
    sb.appendLine("  Explore a device:  trailblaze blaze \"Tap the Sign In button\"")
    sb.appendLine("  Run a test:        trailblaze trail my-test.trail.yaml")
    sb.appendLine("  First time?        trailblaze config show")
    sb.appendLine()
    sb.appendLine("  Use 'blaze' to drive a device interactively with AI, then")
    sb.appendLine("  'session save' to turn your session into a replayable trail.")
    sb.appendLine()
    sb.appendLine("Agents:")
    sb.appendLine("  Run a tool:        trailblaze tool tapOnElement ref=\"Sign In\" --objective \"Tap sign in\"")
    sb.appendLine("  Browse tools:      trailblaze toolbox")
    sb.appendLine()
    sb.appendLine("  Use 'tool' to run Trailblaze tools directly. Always pass --objective (-o)")
    sb.appendLine("  with a natural language intent so steps can self-heal if the UI changes.")
    sb.appendLine()

    return sb.toString()
  }
}
