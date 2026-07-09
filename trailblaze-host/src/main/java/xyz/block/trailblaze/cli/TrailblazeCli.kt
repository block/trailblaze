package xyz.block.trailblaze.cli

import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.IVersionProvider
import xyz.block.trailblaze.TrailblazeVersion
import xyz.block.trailblaze.config.project.TrailblazeWorkspaceConfigBootstrap
import xyz.block.trailblaze.desktop.TrailblazeDesktopAppConfig
import xyz.block.trailblaze.devices.TrailblazeDevicePort
import xyz.block.trailblaze.host.WorkspaceCompileBootstrap
import xyz.block.trailblaze.logs.server.endpoints.CliExecRequest
import xyz.block.trailblaze.logs.server.endpoints.CliExecResponse
import xyz.block.trailblaze.ui.TrailblazeDesktopApp
import xyz.block.trailblaze.ui.TrailblazeDesktopUtil
import xyz.block.trailblaze.ui.TrailblazePortManager
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.canRunDesktopGui
import kotlinx.coroutines.CancellationException
import java.io.ByteArrayOutputStream
import java.util.concurrent.Callable
import java.util.concurrent.atomic.AtomicBoolean
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
 *   trailblaze --stop              - Stop the daemon
 *   trailblaze app                 - Launch desktop GUI
 *   trailblaze app --headless      - Start headless daemon
 *   trailblaze config target myapp                                 - Set target app
 *   trailblaze app --stop          - Stop the daemon
 *   trailblaze app --status        - Check daemon status
 *   trailblaze run <file>          - Run a .trail.yaml file
 *   trailblaze step "description"  - Run one step via the built-in AI agent (requires an LLM)
 *   trailblaze ask "question"      - Ask the built-in agent about what's on screen (requires an LLM)
 *   trailblaze session end         - End the CLI session
 *   trailblaze mcp                 - Start MCP server (STDIO transport + tray icon)
 *   trailblaze report              - Generate HTML report for all sessions
 *   trailblaze device               - List connected devices
 *   trailblaze show                - Open the multi-device live grid (/devices/all) in your default browser
 *   TRAILBLAZE_PORT=52900 trailblaze - Launch on a custom port (allows multiple instances)
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

  /**
   * One-shot guard for the daemon-init workspace compile bootstrap. Set on the first
   * `appProvider()` call so that subsequent CLI subcommands forwarded to the same JVM
   * (e.g., via the `/cli/exec` IPC fast path) skip the hash walk entirely. Edits to
   * trailmap manifests while the daemon is running are out of scope per #2556 — the user
   * restarts the daemon after manifest changes, just like any other config edit.
   */
  private val bootstrapHasRun = AtomicBoolean(false)

  fun run(
    args: Array<String>,
    appProvider: () -> TrailblazeDesktopApp,
    configProvider: () -> TrailblazeDesktopAppConfig,
  ) {
    // Every Trailblaze process is a macOS agent app (LSUIElement) unless it deliberately
    // shows a window. Read at AWT initialization, so it must be set before ANY code path
    // loads an AWT class: a CLI command or headless daemon that touches AWT would otherwise
    // initialize as a regular GUI app, which macOS activates — stealing keyboard focus from
    // whatever the user is typing. The one headed path (`trailblaze app` without --headless)
    // clears this in MainTrailblazeApp before its first AWT touch.
    System.setProperty(TrailblazeDesktopUtil.AWT_AGENT_APP_PROPERTY, "true")

    // Fail fast on Intel macOS / Windows with a clear "platform unsupported"
    // message — runs before any code path could touch Skiko's JNI loader and
    // surface a cryptic LibraryLoadException instead.
    TrailblazeDesktopUtil.assertSupportedPlatform()

    // Install the workspace-config-dir resolver into the model-level holder so the
    // default `platformConfigResourceSource()` layers workspace-on-disk over the
    // classpath. Must run before any discovery — `WorkspaceCompileBootstrap` below
    // triggers trailmap discovery on first appProvider() call.
    TrailblazeWorkspaceConfigBootstrap.ensureInstalled()

    // Install thread-local stdout/stderr capture BEFORE any code references
    // `System.out` — Console.jvm.kt caches it into a field at class init time,
    // so the capture has to be in place before the first Console.log call.
    CliOutCapture.install()

    // Wrap appProvider so a workspace-trailmap rebundle runs before the first
    // TrailblazeDesktopApp instance is constructed. Constructor-time field initializers
    // (notably the desktop app's eager `desktopYamlRunner = DesktopYamlRunner(...)`)
    // force the lazy `availableAppTargets`, which calls `AppTargetDiscovery.discover()`
    // — so any post-construction hook is too late to keep workspace trailmap edits visible
    // on the first discovery pass. The [bootstrapHasRun] guard memoizes the bootstrap
    // for the JVM lifetime so subsequent `appProvider()` calls (e.g. forwarded CLI
    // subcommands hitting the daemon-IPC fast path) don't repeat the hash walk.
    val bootstrappedAppProvider: () -> TrailblazeDesktopApp = {
      if (bootstrapHasRun.compareAndSet(false, true)) {
        WorkspaceCompileBootstrap.bootstrapOrExit()
      }
      appProvider()
    }
    appProviderRef = bootstrappedAppProvider
    configProviderRef = configProvider

    // Capture argv so any CliMcpClient created downstream sends it as
    // `X-Trailblaze-Origin`. The daemon surfaces this in the device-busy
    // error so users see which command is currently driving the device.
    CliMcpClient.captureOrigin(args)

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

    val cli = TrailblazeCliCommand(bootstrappedAppProvider, configProvider)
    val commandLine = CommandLine(cli).setCaseInsensitiveEnumValuesAllowed(true)
    installTrailblazeExceptionHandlers(commandLine)
    installPerToolHelpExecutionStrategy(commandLine)

    // Replace the default flat command list with grouped sections. The `--all` flag is
    // pre-scanned here (rather than read from the parsed `cli.showAll` field) because
    // help rendering happens during `commandLine.execute()` *before* picocli has bound
    // option values back onto the command instance — by the time the flag would be
    // readable from the field, the renderer has already run.
    commandLine.helpSectionMap[CommandLine.Model.UsageMessageSpec.SECTION_KEY_COMMAND_LIST] =
      GroupedCommandListRenderer(showHidden = args.contains("--all"))

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
   * (`run` with relative paths).
   *
   * `snapshot` and `ask` are thin wrappers over MCP tool calls with no env-var
   * reads, so running them in-process is a pure win: we skip the JVM cold
   * start and hit the local MCP bridge over loopback HTTP.
   *
   * `config` is forwarded for correctness, not speed: the daemon owns the
   * canonical in-memory `appConfig` and auto-persists it on every mutation, so
   * a CLI that writes the settings file directly would be silently overwritten
   * by the daemon's next state save. Forwarding routes the write through the
   * daemon's [TrailblazeSettingsRepo] (via [DaemonSettingsBridge]), keeping the
   * daemon and the file in sync. Show paths (`config`, `config show`) bail out
   * of `exitProcess` when running forwarded — see [ConfigCommand].
   *
   * `tool` is forwarded for the same shape-reason as `snapshot`: it makes a
   * single MCP `step` call with a tool YAML and prints the agent-formatted
   * result. The "device/session state" worry that originally excluded it doesn't
   * apply — `tool` reuses the same `cliReusableWithDevice` infrastructure
   * `snapshot` does, and the session reuse via `connectReusable` works
   * identically over a loopback recursive call. Forwarding closes the
   * `tool --yaml` failure mode where the JVM-spawn fallback would conflict
   * with the running daemon and bail out with `failed to connect to Trailblaze
   * daemon after starting it`. (FTUX validators caught this on web; bare
   * `tool web_navigate` happened to work because the JVM-side resolution
   * landed differently — the --yaml form did not.)
   *
   * Some heavier commands (`step`, `verify`) are not on this list — they take
   * longer than the cold-start savings would buy, and their AI-loop state
   * (multi-turn LLM calls, streaming progress) is messier to reason about
   * under in-process execution. They keep using the JVM-spawn path until we
   * have a clearer reason to pull them in.
   *
   * **Output-shape invariant.** Candidates added to this set must emit only
   * line-terminated output (i.e. `Console.log/info/error` → `println`). The
   * bash shim's `/cli/exec` replay (`ipc_try_forward` in
   * `scripts/trailblaze`) restores the trailing newline that bash
   * `$(jq -r …)` strips with `printf '%s\n'`. A subcommand that uses
   * partial-line output (`Console.appendLog`/`Console.appendInfo` or a raw
   * `print(...)` before exit) would render a phantom blank line in the
   * forwarded path while looking fine on the JVM-spawn path — a confusing
   * divergence. If a candidate truly needs partial-line output, fix the
   * shim contract first (interleaved capture is the in-progress design).
   */
  private val FORWARDABLE_SUBCOMMANDS = setOf("snapshot", "ask", "config", "tool")

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

    // In-process forwarded subcommands also need the origin captured so any
    // MCP self-connection from inside the daemon JVM tags its session with
    // the right argv (e.g. `snapshot -d android`).
    CliMcpClient.captureOrigin(args.toTypedArray())

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

    val callerCwd = request.cwd?.takeIf { it.isNotBlank() }?.let {
      try {
        java.nio.file.Paths.get(it)
      } catch (_: java.nio.file.InvalidPathException) {
        null
      }
    }

    val exitCode = synchronized(execLock) {
      // Save/restore the global `Console.quietMode` flag. cli*WithDevice(verbose=false)
      // flips it for the duration of the CLI command, but there's no reset path in
      // the CLI itself; without this wrapper the long-lived daemon would go silent
      // for every Console.log after the first forwarded invocation. Save-restore is
      // safer than unconditional `disableQuietMode()` because a future daemon
      // feature could legitimately set quiet mode outside this scope.
      val priorQuiet = Console.isQuietMode()
      // Pin the caller's cwd as a thread-local so any picocli command that walks
      // relative paths (e.g. waypoint --target's workspace-anchor lookup) anchors at
      // the user's shell directory rather than the daemon's launch directory. Null
      // cwd is fine — `CliCallerContext.callerCwd()` falls back to `Paths.get("")`
      // which preserves the prior daemon-cwd behavior for older shims.
      //
      // Same shape for env vars: `withCallerEnv` pins TRAILBLAZE_DEVICE (and any
      // future CLI-relevant vars the shim forwards) so device-resolution helpers
      // see the user's shell env rather than the daemon's stale captured env.
      // Without this, `eval $(trailblaze device connect <id>)` followed by a
      // forwarded subcommand (`snapshot`, `ask`, `config`) silently ignored the
      // shell pin — the bug that made `trailblaze snapshot` fail with "multiple
      // devices connected" right after the user did exactly what the error's own
      // hint told them to do. Null env is fine; `callerEnv` falls back to
      // `System.getenv` which is the prior behavior for shims that don't forward.
      CliCallerContext.withCallerEnv(request.env) {
        CliCallerContext.withCallerCwd(callerCwd) {
        CliOutCapture.withCapture(stdoutBuf, stderrBuf) {
          val cli = TrailblazeCliCommand(appProvider, configProvider)
          val commandLine = CommandLine(cli).setCaseInsensitiveEnumValuesAllowed(true)
          installTrailblazeExceptionHandlers(commandLine)
          // `tool` is in FORWARDABLE_SUBCOMMANDS, so this branch DOES see
          // `tool <name> --help` invocations — installing the per-tool help
          // execution strategy here keeps the daemon `/cli/exec` fast path on
          // identical wiring to the JVM-spawn path. Without this, a forwarded
          // `tool tap_on_text --help` would fall through to picocli's default
          // help renderer and miss the per-tool YAML-schema synopsis the
          // JVM-spawn path produces.
          installPerToolHelpExecutionStrategy(commandLine)
          commandLine.helpSectionMap[CommandLine.Model.UsageMessageSpec.SECTION_KEY_COMMAND_LIST] =
            GroupedCommandListRenderer(showHidden = args.contains("--all"))
          try {
            commandLine.execute(*args.toTypedArray())
          } catch (e: CancellationException) {
            throw e
          } catch (e: Throwable) {
            // Last-line-of-defense for anything the picocli exception handlers
            // didn't catch (they handle parameter + execution exceptions; this
            // catches whatever leaks past the handler chain itself). Always
            // INFRA_FAILED (2) — uncaught at this depth means the daemon JVM
            // hit an unexpected runtime error, not a user-input problem.
            reportCliError(
              verb = "cli/exec",
              reason = describeThrowableForUser(e),
            )
            TrailblazeExitCode.INFRA_FAILED.code
          } finally {
            if (priorQuiet) Console.enableQuietMode() else Console.disableQuietMode()
          }
        }
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
    StepCommand::class,
    AskCommand::class,
    VerifyCommand::class,
    SnapshotCommand::class,
    ToolCommand::class,
    ToolboxCommand::class,
    TrailCommand::class,
    SessionCommand::class,
    ReportCommand::class,
    WaypointCommand::class,
    ResultsCommand::class,
    ConfigCommand::class,
    DeviceCommand::class,
    ShowCommand::class,
    AppCommand::class,
    McpCommand::class,
    CheckCommand::class,
    MigrateTrailsCommand::class,
    // (No standalone `test` subcommand — bun unit tests run as part of `trailblaze
    // check`'s third phase. `trailblaze test` collided with "Trailblaze runs trails"
    // and was deleted in favor of the bundled-in-check flow. If a finer-grained
    // invocation is ever needed, add it as `trailblaze check tests` rather than a
    // top-level command.)
    // Hidden — see DesktopCommand. Resolves by name (`trailblaze desktop snapshot`)
    // but doesn't appear in `--help` or the GroupedCommandListRenderer's groups.
    DesktopCommand::class,
  ]
)
class TrailblazeCliCommand(
  internal val appProvider: () -> TrailblazeDesktopApp,
  internal val configProvider: () -> TrailblazeDesktopAppConfig,
) : Callable<Int> {

  /**
   * Hidden meta-flag that flips `--help` rendering to include `hidden = true` subcommands
   * (e.g. the Compose desktop driver demo command). Accepted at the top level so users
   * who know the flag exists can run `trailblaze --help --all`; the renderer reads it
   * back via a pre-scan of args (see `Main.runFromCli`). Has no effect when not paired
   * with `--help`.
   */
  @Suppress("unused")
  @CommandLine.Option(
    names = ["--all"],
    hidden = true,
    description = ["Include hidden commands in --help output."],
  )
  internal var showAll: Boolean = false

  @CommandLine.Option(
    names = ["--stop"],
    description = ["Stop the running daemon and exit."],
  )
  internal var stop: Boolean = false

  /**
   * Returns the effective HTTP port.
   *
   * Precedence: saved settings (per-install) → TRAILBLAZE_PORT env var → default (52525)
   */
  fun getEffectivePort(): Int = CliConfigHelper.resolveEffectiveHttpPort()

  /**
   * Returns the effective HTTPS port.
   *
   * Precedence: saved settings (per-install) → TRAILBLAZE_HTTPS_PORT env var → derived
   * from the resolved HTTP port (HTTP + 1).
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
    if (stop) {
      return shutdownDaemonAndWait(getEffectivePort())
    }

    // No subcommand → show help. Use `trailblaze app` to launch the desktop GUI.
    //
    // Mirror the renderer wiring that `TrailblazeCli.run` and `executeForDaemon` apply to
    // their `CommandLine` instances. Without this, bare `trailblaze` (no args) would render
    // help via the default picocli renderer — losing the grouped-section headings (Drive:,
    // Trail:, Setup:, Built-in agent:) that `--help` already shows.
    val cl = CommandLine(this).setCaseInsensitiveEnumValuesAllowed(true)
    cl.helpSectionMap[CommandLine.Model.UsageMessageSpec.SECTION_KEY_COMMAND_LIST] =
      GroupedCommandListRenderer(showHidden = showAll)
    cl.usage(System.out)
    return TrailblazeExitCode.SUCCESS.code
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
    val daemonAlreadyRunning = DaemonClient(port = getEffectivePort()).use { daemon ->
      if (!daemon.isRunningBlocking()) return@use false

      val response = daemon.showWindowBlocking()
      if (response.success) {
        Console.log("Window shown.")
        return TrailblazeExitCode.SUCCESS.code
      }

      // Daemon is running but has no window (e.g., started by `trailblaze mcp`).
      // Start the desktop GUI alongside the existing daemon — it will skip starting
      // a second HTTP server since the daemon is already handling that.
      Console.log("Trailblaze server is running. Starting desktop GUI...")
      true
    }

    // Apply port overrides to settings if any non-default ports are active
    val app = appProvider()
    if (hasPortOverride()) {
      app.applyPortOverrides(httpPort = getEffectivePort(), httpsPort = getEffectiveHttpsPort())
    }

    // Start the app (GUI or headless based on flag). When no daemon was running above, this
    // process must own the port — losing the bind race exits instead of leaving a duplicate
    // tray icon.
    app.startTrailblazeDesktopApp(headless = effectiveHeadless, daemonAlreadyRunning = daemonAlreadyRunning)
    return TrailblazeExitCode.SUCCESS.code
  }
}

/**
 * Custom help section renderer that groups subcommands under headings
 * instead of a flat alphabetical list.
 */
internal class GroupedCommandListRenderer(
  /**
   * When `true`, hidden subcommands (those with `@Command(hidden = true)`) are included
   * in the rendered help instead of being filtered out. Wired to the top-level `--all`
   * flag in [TrailblazeCliCommand]. Defaults to `false` so the standard
   * `trailblaze --help` invocation stays clean.
   */
  private val showHidden: Boolean = false,
) : CommandLine.IHelpSectionRenderer {

  private data class Group(val heading: String, val commands: List<String>)

  private val groups = listOf(
    // Order matters. `Drive:` is first because the deterministic primitives are the
    // recommended path — any AI coding agent (Claude Code, Codex, Goose, Cursor, ...)
    // can drive a device by shelling out to these. `Trail:` and `Setup:` follow.
    // `Built-in agent:` is last on purpose: `step`/`ask`/`verify` route through the
    // bundled LLM agent and are slower + less deterministic than the primitives. They
    // exist for users who want to use Trailblaze's own agent end-to-end, but we want
    // the visual hierarchy to point new users at the primitives first. See
    // [BUILT_IN_AGENT_GROUP_NAME] and the LLM-dependency footnote rendered alongside it.
    Group(
      "Drive:",
      listOf("snapshot", "tool", "toolbox"),
    ),
    Group(
      "Trail:",
      // `migrate-trails` is listed so that when `--all` surfaces hidden subcommands it
      // lands under Trail: (its natural home — it operates on trail YAML files) rather
      // than the `Other:` catch-all. With #3385 making it `hidden = true` by default,
      // the renderer's hidden-filter drops it from normal `--help` output anyway.
      listOf("run", "session", "report", "results", "waypoint", "migrate-trails"),
    ),
    Group(
      "Setup:",
      listOf("config", "device", "show", "app", "mcp", "check", "test"),
    ),
    Group(
      BUILT_IN_AGENT_GROUP_NAME,
      listOf("step", "ask", "verify"),
    ),
  )

  companion object {
    /**
     * Heading printed above the `step`/`ask`/`verify` group. Pulled out as a constant so
     * the renderer body and the LLM-dependency footnote agree on the wording, and so tests
     * can assert against the exact string without copy-pasting it.
     */
    internal const val BUILT_IN_AGENT_GROUP_NAME: String = "Built-in agent:"

    /**
     * One-line note rendered immediately under the [BUILT_IN_AGENT_GROUP_NAME] group.
     * Calls out the LLM dependency so users who default to `llm = none` understand why
     * those commands fail before they're set up. Distributions that ship with a managed
     * default provider already configured will see the note as informational rather than
     * blocking — it's the same string in both cases.
     */
    internal const val BUILT_IN_AGENT_LLM_NOTE: String =
      "  These commands require an LLM. Run `trailblaze config llm <provider/model>` to set one up."
  }

  override fun render(help: CommandLine.Help): String {
    // Strip hidden subcommands once at the top so neither the named-group rendering
    // nor the "Other:" tail can leak them. picocli's Help.subcommands() returns a map
    // that *includes* hidden entries — a `@Command(hidden = true)` only suppresses the
    // command from picocli's *built-in* renderer, not from custom ones like this. We
    // filter explicitly here so commands like the Compose desktop driver demo command
    // stay reachable by name without showing up in the help output, unless the caller
    // passed `--all` (the flag flips [showHidden] true for that invocation).
    // Drop alias entries (one map entry per `@Command(aliases = …)` value, all
    // pointing at the same Help instance) so an aliased command is keyed only by
    // its canonical name — otherwise the group lookup misses it AND the "Other:"
    // tail picks the alias key up. See [canonicalSubcommands] for full details.
    val subcommands = if (showHidden) {
      help.canonicalSubcommands()
    } else {
      help.canonicalSubcommands().filterValues { !it.commandSpec().usageMessage().hidden() }
    }
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
      // The Built-in agent group gets a one-line LLM-dependency note inline (before the
      // trailing blank line) so it's anchored visually to the rows it qualifies. Doing it
      // here rather than as a separate footer keeps the note tied to the group — if the
      // group is ever empty (all three commands removed) the note disappears with it.
      if (group.heading == BUILT_IN_AGENT_GROUP_NAME) {
        sb.appendLine(BUILT_IN_AGENT_LLM_NOTE)
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

    // Intentionally NO "Users:" / "Agents:" footer here. Earlier versions split selected
    // examples by audience, but the Trailblaze CLI serves both humans and AI agents with
    // the *same* primitives — splitting the footer suggested two different surfaces when
    // there's only one. The per-command `trailblaze <cmd> --help` carries the deeper
    // documentation; the top-level index just lists what's available.

    return sb.toString()
  }
}
