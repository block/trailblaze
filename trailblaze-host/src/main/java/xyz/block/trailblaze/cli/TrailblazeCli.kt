package xyz.block.trailblaze.cli

import kotlinx.coroutines.runBlocking
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.IVersionProvider
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import xyz.block.trailblaze.TrailblazeVersion
import xyz.block.trailblaze.capture.CaptureOptions
import xyz.block.trailblaze.capture.CaptureSession
import xyz.block.trailblaze.compose.driver.rpc.ComposeRpcServer
import xyz.block.trailblaze.compose.driver.tools.ComposeToolSet
import xyz.block.trailblaze.desktop.LlmTokenStatus
import xyz.block.trailblaze.desktop.TrailblazeDesktopAppConfig
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDevicePort
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.rules.BasePlaywrightElectronTest
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.llm.TrailblazeReferrer
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.logs.model.getSessionStatus
import xyz.block.trailblaze.logs.server.endpoints.CliRunRequest
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.mcp.McpToolProfile
import xyz.block.trailblaze.mcp.TrailblazeMcpMode
import xyz.block.trailblaze.model.DesktopAppRunYamlParams
import xyz.block.trailblaze.model.DeviceConnectionStatus
import xyz.block.trailblaze.model.TrailExecutionResult
import xyz.block.trailblaze.model.TrailblazeConfig
import xyz.block.trailblaze.model.findById
import xyz.block.trailblaze.playwright.tools.PlaywrightNativeToolSet
import xyz.block.trailblaze.recordings.TrailRecordings
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.report.utils.TrailblazeYamlSessionRecording.generateRecordedYaml
import xyz.block.trailblaze.ui.TrailblazeDesktopApp
import xyz.block.trailblaze.ui.TrailblazeDeviceManager
import xyz.block.trailblaze.ui.TrailblazePortManager
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.GitUtils
import xyz.block.trailblaze.util.TrailYamlTemplateResolver
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import java.awt.GraphicsEnvironment
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

/**
 * Shared CLI infrastructure for Trailblaze desktop applications.
 * 
 * Both open source and internal versions use this shared CLI with their
 * specific [TrailblazeDesktopApp] implementation.
 * 
 * Usage:
 *   trailblaze                     - Launch GUI (default)
 *   trailblaze desktop             - Launch GUI (explicit subcommand)
 *   trailblaze desktop --headless  - Start headless MCP server
 *   trailblaze --headless          - Start headless MCP server (legacy, on root command)
 *   trailblaze run <file>          - Run a .trail.yaml file
 *   trailblaze mcp                 - Start MCP server (STDIO transport + tray icon)
 *   trailblaze mcp --http          - Start MCP server (Streamable HTTP)
 *   trailblaze devices             - List connected devices
 *   trailblaze -p 52526            - Launch on a custom port (allows multiple instances)
 *   trailblaze --help              - Show all commands and options
 */
object TrailblazeCli {

  /**
   * Detects whether the JVM was launched from an IDE (IntelliJ IDEA, Android Studio, etc.).
   *
   * When a user clicks the Play button in an IDE, they expect a full restart.
   * However, the previous instance may still be holding the HTTP port, causing
   * the new instance to detect it as "already running" and exit early.
   *
   * Detection checks for JetBrains IDE launcher artifacts:
   * - `idea_rt.jar` on the classpath (injected by the IDE run configuration)
   * - IntelliJ's `AppMainV2` launcher in `sun.java.command`
   */
  fun isLaunchedFromIde(): Boolean {
    // Check classpath for IntelliJ's runtime jar
    val classpath = System.getProperty("java.class.path") ?: ""
    if (classpath.contains("idea_rt.jar")) return true

    // Check if launched via IntelliJ's application launcher
    val command = System.getProperty("sun.java.command") ?: ""
    if (command.contains("com.intellij.rt.execution")) return true

    return false
  }

  /**
   * Main entry point for the CLI.
   *
   * @param args Command line arguments
   * @param appProvider Factory function to create the app instance
   * @param configProvider Factory function to get the config (for CLI commands that need it before app creation)
   */
  fun run(
    args: Array<String>,
    appProvider: () -> TrailblazeDesktopApp,
    configProvider: () -> TrailblazeDesktopAppConfig,
  ) {
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
      val httpPort = resolvePortFromArgs(args)
      DesktopLogFileWriter.install(httpPort = httpPort)
    }

    val cli = TrailblazeCliCommand(appProvider, configProvider)
    val commandLine = CommandLine(cli)
      .setCaseInsensitiveEnumValuesAllowed(true)

    // Support `sq` CLI integration: output JSON describing subcommands and exit.
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
   * Lightweight pre-scan of CLI args to resolve the HTTP port before picocli runs.
   *
   * Precedence: `-p`/`--port` flag → `TRAILBLAZE_PORT` env var → default.
   */
  private fun resolvePortFromArgs(args: Array<String>): Int {
    val portIndex = args.indexOfFirst { it == "-p" || it == "--port" }
    if (portIndex != -1 && portIndex + 1 < args.size) {
      args[portIndex + 1].toIntOrNull()?.let { return it }
    }
    System.getenv(TrailblazePortManager.HTTP_PORT_ENV_VAR)?.toIntOrNull()?.let { return it }
    return TrailblazeDevicePort.TRAILBLAZE_DEFAULT_HTTP_PORT
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
  description = ["Trailblaze - AI-powered UI automation"],
  subcommands = [
    DesktopCommand::class,
    RunCommand::class,
    McpCommand::class,
    DevicesCommand::class,
    ConfigCommand::class,
    StatusCommand::class,
    StopCommand::class,
    CommandLine.HelpCommand::class,
  ]
)
class TrailblazeCliCommand(
  private val appProvider: () -> TrailblazeDesktopApp,
  private val configProvider: () -> TrailblazeDesktopAppConfig,
) : Callable<Int> {

  @Option(
    names = ["--headless"],
    description = ["Start in headless mode (MCP server only, no GUI)"]
  )
  var headless: Boolean = false

  @Option(
    names = ["-p", "--port"],
    description = ["HTTP port for the Trailblaze server (default: ${TrailblazeDevicePort.TRAILBLAZE_DEFAULT_HTTP_PORT})"]
  )
  var port: Int? = null

  @Option(
    names = ["--https-port"],
    description = ["HTTPS port for the Trailblaze server (default: ${TrailblazeDevicePort.TRAILBLAZE_DEFAULT_HTTPS_PORT})"]
  )
  var httpsPort: Int? = null

  /**
   * Returns the effective HTTP port.
   *
   * Precedence: CLI flag → saved settings (per-install) → TRAILBLAZE_PORT env var → default (52525)
   */
  fun getEffectivePort(): Int {
    // 1. CLI flag (highest priority — one-off override)
    port?.let { return it }
    return CliConfigHelper.resolveEffectiveHttpPort()
  }

  /**
   * Returns the effective HTTPS port.
   *
   * Precedence: CLI flag → saved settings (per-install) → TRAILBLAZE_HTTPS_PORT env var → default (8443)
   */
  fun getEffectiveHttpsPort(): Int {
    // 1. CLI flag (highest priority — one-off override)
    httpsPort?.let { return it }
    return CliConfigHelper.resolveEffectiveHttpsPort()
  }

  /**
   * Whether any port override is active (from CLI flag, env var, or saved settings).
   */
  fun hasPortOverride(): Boolean {
    return getEffectivePort() != TrailblazeDevicePort.TRAILBLAZE_DEFAULT_HTTP_PORT ||
        getEffectiveHttpsPort() != TrailblazeDevicePort.TRAILBLAZE_DEFAULT_HTTPS_PORT
  }

  override fun call(): Int = launchDesktop(headless)

  /**
   * Core desktop launch logic shared by the root command (no-args) and [DesktopCommand].
   */
  internal fun launchDesktop(headless: Boolean): Int {
    // Check if Trailblaze is already running
    val daemon = DaemonClient(port = getEffectivePort())
    if (daemon.isRunning()) {
      if (TrailblazeCli.isLaunchedFromIde()) {
        // IDE launch (e.g., IntelliJ Play button) — the user expects a full restart.
        // Shut down the previous instance so we can start fresh.
        Console.log("IDE launch detected — shutting down previous instance...")
        daemon.shutdown()
        val maxWaitMs = 10_000L
        val start = System.currentTimeMillis()
        while (daemon.isRunning() && System.currentTimeMillis() - start < maxWaitMs) {
          Thread.sleep(500)
        }
      } else {
        // Normal CLI launch — show the existing window or attach to the daemon.
        val response = daemon.showWindow()
        if (response.success) {
          Console.log("Window shown.")
          return CommandLine.ExitCode.OK
        }

        // Daemon is running but has no window (e.g., started by `trailblaze mcp`).
        // Start the desktop GUI alongside the existing daemon — it will skip starting
        // a second HTTP server since the daemon is already handling that.
        Console.log("Trailblaze server is running. Starting desktop GUI...")
      }
    }

    // Apply port overrides to settings if any non-default ports are active
    val app = appProvider()
    if (hasPortOverride()) {
      app.applyPortOverrides(httpPort = getEffectivePort(), httpsPort = getEffectiveHttpsPort())
    }

    // Start the app (GUI or headless based on flag)
    app.startTrailblazeDesktopApp(headless = headless)
    return CommandLine.ExitCode.OK
  }
}

/**
 * Launch the Trailblaze desktop application.
 *
 * This is the explicit subcommand equivalent of running `trailblaze` with no arguments.
 * Use this when integrating with a distribution system that requires an explicit subcommand.
 */
@Command(
  name = "desktop",
  mixinStandardHelpOptions = true,
  description = ["Launch the Trailblaze desktop application"]
)
class DesktopCommand : Callable<Int> {

  @CommandLine.ParentCommand
  private lateinit var parent: TrailblazeCliCommand

  @Option(
    names = ["--headless"],
    description = ["Start in headless mode (MCP server only, no GUI)"]
  )
  var headless: Boolean = false

  override fun call(): Int = parent.launchDesktop(headless || parent.headless)
}

/**
 * Run a .trail.yaml file or directory of trail files on a connected device.
 */
@Command(
  name = "run",
  mixinStandardHelpOptions = true,
  description = ["Run a .trail.yaml file or directory of trail files on a connected device"]
)
class RunCommand : Callable<Int> {

  @CommandLine.ParentCommand
  private lateinit var parent: TrailblazeCliCommand

  /** Set by the shutdown hook on Ctrl+C to stop processing further trail files. */
  @Volatile private var cancelled = false

  @Parameters(
    index = "0",
    description = ["Path to a .trail.yaml file or directory containing trail files"]
  )
  lateinit var trailFile: File

  @Option(
    names = ["--headless"],
    description = ["Run without GUI (MCP server mode)"]
  )
  var headless: Boolean = false

  @Option(
    names = ["-d", "--device"],
    description = ["Device ID to run on (e.g., 'emulator-5554'). If not specified, uses first available device."]
  )
  var deviceId: String? = null

  @Option(
    names = ["-a", "--agent"],
    description = ["Agent: TRAILBLAZE_RUNNER, MULTI_AGENT_V3. Default: ${AgentImplementation.DEFAULT_NAME}"]
  )
  var agent: String = AgentImplementation.DEFAULT.name

  @Option(
    names = ["--use-recorded-steps"],
    description = ["Use recorded tool sequences instead of LLM inference"]
  )
  var useRecordedSteps: Boolean = false

  @Option(
    names = ["--set-of-mark"],
    description = ["Enable Set of Mark mode (default: true)"],
    negatable = true
  )
  var setOfMark: Boolean = true

  @Option(
    names = ["-v", "--verbose"],
    description = ["Enable verbose output"]
  )
  var verbose: Boolean = false

  @Option(
    names = ["--driver"],
    description = ["Driver type to use (e.g., PLAYWRIGHT_NATIVE, ANDROID_HOST). Overrides driver from trail config."]
  )
  var driverType: String? = null

  @Option(
    names = ["--show-browser"],
    description = ["Show the browser window (default: headless). Useful for debugging web trails."]
  )
  var showBrowser: Boolean = false

  @Option(
    names = ["--llm"],
    description = ["LLM provider/model shorthand (e.g., openai/gpt-4-1). " +
        "Mutually exclusive with --llm-provider and --llm-model."]
  )
  var llm: String? = null

  @Option(
    names = ["--llm-provider"],
    description = ["LLM provider override (e.g., openai, anthropic, google)"]
  )
  var llmProvider: String? = null

  @Option(
    names = ["--llm-model"],
    description = ["LLM model ID override (e.g., gemini-3-flash, gpt-4-1)"]
  )
  var llmModel: String? = null

  @Option(
    names = ["--no-report"],
    description = ["Skip HTML report generation after execution"]
  )
  var noReport: Boolean = false

  @Option(
    names = ["--no-record"],
    description = ["Skip saving the recording back to the trail source directory"]
  )
  var noRecord: Boolean = false

  @Option(
    names = ["--markdown"],
    description = ["Generate a markdown report after execution"]
  )
  var markdown: Boolean = false

  @Option(
    names = ["--no-daemon"],
    description = ["Run in-process without delegating to or starting a persistent daemon. " +
        "The server shuts down when the run completes."]
  )
  var noDaemon: Boolean = false

  @Option(
    names = ["-p", "--port"],
    description = ["HTTP port for the Trailblaze server (overrides parent --port if set)"]
  )
  var port: Int? = null

  @Option(
    names = ["--https-port"],
    description = ["HTTPS port for the Trailblaze server (overrides parent --https-port if set)"]
  )
  var httpsPort: Int? = null

  @Option(
    names = ["--compose-port"],
    description = ["RPC port for Compose driver connections (default: ${ComposeRpcServer.COMPOSE_DEFAULT_PORT})"]
  )
  var composePort: Int = ComposeRpcServer.COMPOSE_DEFAULT_PORT

  @Option(
    names = ["--capture-video"],
    description = ["Record device screen video for the session (on by default, use --no-capture-video to disable)"],
    negatable = true,
  )
  var captureVideo: Boolean = true

  @Option(
    names = ["--capture-logcat"],
    description = ["Capture logcat output filtered to the app under test (local dev mode)"]
  )
  var captureLogcat: Boolean = false

  @Option(
    names = ["--capture-all"],
    description = ["Enable all capture streams: video, logcat (local dev mode)"]
  )
  var captureAll: Boolean = false

  private val captureOptions: CaptureOptions get() {
    return CaptureOptions(
      captureVideo = captureVideo || captureAll,
      captureLogcat = captureLogcat || captureAll,
    )
  }

  override fun call(): Int {
    // Suppress internal debug logs unless --verbose is passed.
    // Console.info() and Console.error() remain visible for user-facing output.
    if (!verbose) {
      Console.enableQuietMode()
    }

    // Resolve --llm shorthand: splits "provider/model" into llmProvider + llmModel.
    if (llm != null) {
      if (llmProvider != null || llmModel != null) {
        Console.error("Error: --llm is mutually exclusive with --llm-provider and --llm-model.")
        return CommandLine.ExitCode.USAGE
      }
      val parts = llm!!.split("/", limit = 2)
      if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) {
        Console.error("Error: --llm must be in provider/model format (e.g., openai/gpt-4-1).")
        return CommandLine.ExitCode.USAGE
      }
      llmProvider = parts[0]
      llmModel = parts[1]
    }

    // Validate trail file/directory exists
    if (!trailFile.exists()) {
      Console.error("Error: Trail file or directory does not exist: ${trailFile.absolutePath}")
      return CommandLine.ExitCode.SOFTWARE
    }

    // Check if a daemon is already running — delegate to it to avoid starting a second
    // Gradle JVM. This is critical for CI where multiple trails run sequentially.
    // --no-daemon skips this check and always runs in-process.
    val daemonPort = port ?: parent.getEffectivePort()
    val daemon = DaemonClient(port = daemonPort)
    if (!noDaemon) {
      if (daemon.isRunning()) {
        return delegateToDaemon(daemon)
      }
    } else if (daemon.isRunning()) {
      // Shut down existing daemon so this process can bind the port.
      Console.info("Shutting down existing daemon on port $daemonPort...")
      daemon.shutdown()
    }

    // Directory mode: find and run all .trail.yaml files
    if (trailFile.isDirectory) {
      val trailFiles = trailFile.walkTopDown()
        .filter { it.isFile && it.name.endsWith(".trail.yaml") }
        .sortedBy { it.absolutePath }
        .toList()

      if (trailFiles.isEmpty()) {
        Console.error("Error: No .trail.yaml files found in ${trailFile.absolutePath}")
        return CommandLine.ExitCode.SOFTWARE
      }

      Console.info("Found ${trailFiles.size} trail file(s) in ${trailFile.absolutePath}")
      Console.info("=".repeat(60))

      var passed = 0
      var failed = 0
      val allNewSessionIds = mutableListOf<SessionId>()

      // Initialize the app once for all files
      val app = parent.appProvider()

      // Apply port overrides: RunCommand flags take priority, then fall back to parent
      val effectivePort = port ?: parent.getEffectivePort()
      val effectiveHttpsPort = httpsPort ?: parent.getEffectiveHttpsPort()
      if (effectivePort != TrailblazeDevicePort.TRAILBLAZE_DEFAULT_HTTP_PORT ||
        effectiveHttpsPort != TrailblazeDevicePort.TRAILBLAZE_DEFAULT_HTTPS_PORT) {
        app.applyPortOverrides(httpPort = effectivePort, httpsPort = effectiveHttpsPort)
      }
      app.ensureServerRunning()

      // Register shutdown hook for Ctrl+C
      Runtime.getRuntime().addShutdownHook(Thread {
        cancelled = true
        Console.info("\n\nExecution stopped by user.")
      })

      for ((index, file) in trailFiles.withIndex()) {
        if (cancelled) break
        Console.info("\n[${index + 1}/${trailFiles.size}] Running: ${file.relativeTo(trailFile)}")
        Console.info("-".repeat(60))
        val (exitCode, sessionIds) = runSingleTrailFile(file, app)
        allNewSessionIds.addAll(sessionIds)
        if (exitCode == CommandLine.ExitCode.OK) {
          passed++
          // Save recording to trail source directory on success
          if (!noRecord && sessionIds.isNotEmpty()) {
            val logsRepo = app.deviceManager.logsRepo
            for (sessionId in sessionIds) {
              val classifiers = logsRepo.getSessionInfo(sessionId)
                ?.trailblazeDeviceInfo?.classifiers?.map { it.classifier } ?: emptyList()
              saveRecordingToTrailDirectory(file, sessionId, classifiers)
            }
          }
        } else {
          failed++
        }
      }

      // Print summary
      Console.info("\n" + "=".repeat(60))
      Console.info("Results: $passed passed, $failed failed out of ${trailFiles.size} total")

      // Generate combined report
      if (!noReport && allNewSessionIds.isNotEmpty()) {
        try {
          val logsRepo = app.deviceManager.logsRepo
          val reportGenerator = app.createCliReportGenerator()
          reportGenerator.printSummary(logsRepo, allNewSessionIds)
          val reportFile = reportGenerator.generateReport(logsRepo, allNewSessionIds)
          if (reportFile != null) {
            Console.info("\nReport: file://${reportFile.absolutePath}")
          }
        } catch (e: Exception) {
          Console.error("Failed to generate report: ${e.message}")
        }
      }

      exitProcess(if (failed > 0) CommandLine.ExitCode.SOFTWARE else CommandLine.ExitCode.OK)
    }

    // Single file mode
    if (!TrailRecordings.isTrailFile(trailFile.name) && !trailFile.name.endsWith(".yaml")) {
      Console.error("Warning: File does not have .trail.yaml extension: ${trailFile.name}")
    }

    val app = parent.appProvider()

    // Apply port overrides: RunCommand flags take priority, then fall back to parent
    val effectivePort = port ?: parent.getEffectivePort()
    val effectiveHttpsPort = httpsPort ?: parent.getEffectiveHttpsPort()
    if (effectivePort != TrailblazeDevicePort.TRAILBLAZE_DEFAULT_HTTP_PORT ||
      effectiveHttpsPort != TrailblazeDevicePort.TRAILBLAZE_DEFAULT_HTTPS_PORT) {
      app.applyPortOverrides(httpPort = effectivePort, httpsPort = effectiveHttpsPort)
    }
    app.ensureServerRunning()

    // Register shutdown hook for Ctrl+C
    Runtime.getRuntime().addShutdownHook(Thread {
      cancelled = true
      Console.info("\n\nExecution stopped by user.")
    })

    val (exitCode, newSessionIds) = runSingleTrailFile(trailFile, app)

    // Save recording to trail source directory
    if (!noRecord && exitCode == CommandLine.ExitCode.OK && newSessionIds.isNotEmpty()) {
      val logsRepo = app.deviceManager.logsRepo
      for (sessionId in newSessionIds) {
        val classifiers = logsRepo.getSessionInfo(sessionId)
          ?.trailblazeDeviceInfo?.classifiers?.map { it.classifier } ?: emptyList()
        saveRecordingToTrailDirectory(trailFile, sessionId, classifiers)
      }
    }

    // Print pass/fail summary and generate report
    val logsRepo = app.deviceManager.logsRepo
    val reportGenerator = app.createCliReportGenerator()
    reportGenerator.printSummary(logsRepo, newSessionIds)

    if (!noReport && newSessionIds.isNotEmpty()) {
      try {
        val reportFile = reportGenerator.generateReport(logsRepo, newSessionIds)
        if (reportFile != null) {
          Console.info("\nReport: file://${reportFile.absolutePath}")
        }
      } catch (e: Exception) {
        Console.error("Failed to generate report: ${e.message}")
      }
    }

    // Generate markdown report if --markdown was specified
    if (markdown && newSessionIds.isNotEmpty()) {
      try {
        val markdownFile = reportGenerator.generateMarkdownReport(logsRepo, newSessionIds)
        if (markdownFile != null) {
          Console.info("Markdown: file://${markdownFile.absolutePath}")
        }
      } catch (e: Exception) {
        Console.error("Failed to generate markdown report: ${e.message}")
      }
    }

    exitProcess(exitCode)
  }

  /**
   * Delegates trail execution to a running daemon via HTTP.
   *
   * This avoids starting a second Gradle JVM — the daemon already has device
   * discovery, LLM config, and the trail runner ready to go.
   */
  private fun delegateToDaemon(daemon: DaemonClient): Int {
    if (trailFile.isDirectory) {
      return delegateDirectoryToDaemon(daemon)
    }

    Console.info("Delegating to running Trailblaze daemon...")

    val rawYaml = trailFile.readText()
    val yamlContent = TrailYamlTemplateResolver.resolve(rawYaml, trailFile)

    val testName = if (trailFile.nameWithoutExtension == "trail" ||
      trailFile.nameWithoutExtension == "recording.trail" ||
      trailFile.nameWithoutExtension == "blaze"
    ) {
      trailFile.absoluteFile.parentFile?.name ?: trailFile.nameWithoutExtension
    } else {
      trailFile.nameWithoutExtension
    }

    val request = CliRunRequest(
      yamlContent = yamlContent,
      trailFilePath = trailFile.absolutePath,
      testName = testName,
      driverType = driverType,
      deviceId = deviceId,
      llmProvider = llmProvider,
      llmModel = llmModel,
      useRecordedSteps = useRecordedSteps,
      setOfMark = setOfMark,
      showBrowser = showBrowser,
    )

    // Register Ctrl+C handler to cancel the in-flight daemon run
    Runtime.getRuntime().addShutdownHook(Thread {
      val runId = daemon.currentRunId
      if (runId != null) {
        Console.info("\nCancelling run...")
        daemon.cancelRun(runId)
      }
    })

    // Capture is handled by the daemon's DesktopYamlRunner — running two screenrecord/simctl
    // processes on the same device causes conflicts, so we skip capture in the CLI delegate path.

    val response = daemon.run(request) { progress ->
      Console.info(progress)
    }

    if (response.success) {
      Console.info("\n✅ Trail completed successfully!")
      if (response.sessionId != null) {
        Console.info("Session: ${response.sessionId}")
      }
      // Generate recording from session logs, then save to trail source directory.
      // This runs in the CLI process (separate from the daemon) so it doesn't
      // block the daemon's HTTP server from receiving trailing log POSTs.
      val sid = response.sessionId
      if (!noRecord && sid != null) {
        val sessionId = SessionId(sid)
        generateRecordingForSessionFromDisk(sessionId)
        saveRecordingToTrailDirectory(
          trailFile, sessionId, response.deviceClassifiers,
        )
      }
    } else {
      Console.error("\n❌ Trail failed: ${response.error ?: "Unknown error"}")
    }

    // Flush output before exiting so error messages are visible in CI logs
    System.out.flush()
    System.err.flush()
    exitProcess(if (response.success) CommandLine.ExitCode.OK else CommandLine.ExitCode.SOFTWARE)
  }

  /** Moves a file, falling back to copy+delete when renameTo fails (e.g., cross-filesystem). */
  private fun moveFile(src: File, dest: File): Boolean {
    if (src.renameTo(dest)) return true
    // renameTo fails across filesystems; fall back to copy + delete
    return try {
      src.copyTo(dest, overwrite = true)
      src.delete()
      true
    } catch (e: Exception) {
      false
    }
  }

  /**
   * Delegates a directory of trail files to a running daemon, one at a time.
   */
  private fun delegateDirectoryToDaemon(daemon: DaemonClient): Int {
    val trailFiles = trailFile.walkTopDown()
      .filter { it.isFile && (it.name.endsWith(".trail.yaml") || it.name == "blaze.yaml") }
      .sortedBy { it.absolutePath }
      .toList()

    if (trailFiles.isEmpty()) {
      Console.error("Error: No trail files found in ${trailFile.absolutePath}")
      return CommandLine.ExitCode.SOFTWARE
    }

    Console.info("Delegating ${trailFiles.size} trail file(s) to running Trailblaze daemon...")
    Console.info("=".repeat(60))

    var passed = 0
    var failed = 0

    // Register Ctrl+C handler to cancel the in-flight daemon run
    Runtime.getRuntime().addShutdownHook(Thread {
      val runId = daemon.currentRunId
      if (runId != null) {
        daemon.cancelRun(runId)
      }
    })

    for ((index, file) in trailFiles.withIndex()) {
      Console.info("\n[${index + 1}/${trailFiles.size}] Running: ${file.relativeTo(trailFile)}")
      Console.info("-".repeat(60))

      val rawYaml = file.readText()
      val yamlContent = TrailYamlTemplateResolver.resolve(rawYaml, file)
      val testName = if (file.nameWithoutExtension == "trail" ||
        file.nameWithoutExtension == "recording.trail" ||
        file.nameWithoutExtension == "blaze"
      ) {
        file.absoluteFile.parentFile?.name ?: file.nameWithoutExtension
      } else {
        file.nameWithoutExtension
      }

      val request = CliRunRequest(
        yamlContent = yamlContent,
        trailFilePath = file.absolutePath,
        testName = testName,
        driverType = driverType,
        deviceId = deviceId,
        llmProvider = llmProvider,
        llmModel = llmModel,
        useRecordedSteps = useRecordedSteps,
        setOfMark = setOfMark,
        showBrowser = showBrowser,
      )

      val response = daemon.run(request) { progress ->
        Console.info(progress)
      }
      if (response.success) {
        Console.info("✅ PASSED")
        passed++
        // Generate recording and save to trail source directory
        val sid = response.sessionId
        if (!noRecord && sid != null) {
          val sessionId = SessionId(sid)
          generateRecordingForSessionFromDisk(sessionId)
          saveRecordingToTrailDirectory(
            file, sessionId, response.deviceClassifiers,
          )
        }
      } else {
        Console.error("❌ FAILED: ${response.error ?: "Unknown error"}")
        failed++
      }
    }

    Console.info("\n" + "=".repeat(60))
    Console.info("Results: $passed passed, $failed failed out of ${trailFiles.size} total")

    exitProcess(if (failed > 0) CommandLine.ExitCode.SOFTWARE else CommandLine.ExitCode.OK)
  }

  /**
   * Runs a single .trail.yaml file and returns the exit code and any new session IDs created.
   */
  private fun runSingleTrailFile(
    file: File,
    app: TrailblazeDesktopApp,
  ): Pair<Int, List<SessionId>> {
    // Read the YAML file and resolve template variables (e.g., {{CWD}})
    val rawYaml = file.readText()
    val yamlContent = TrailYamlTemplateResolver.resolve(rawYaml, file)
    if (verbose) {
      Console.log("Reading trail file: ${file.absolutePath}")
      Console.log("YAML content (${yamlContent.length} bytes)")
    }

    // Parse trail config to extract driver and platform hints (before device loading so we can
    // short-circuit for web/compose trails that don't need Android/iOS device discovery).
    val trailConfig = try {
      createTrailblazeYaml().extractTrailConfig(yamlContent)
    } catch (_: Exception) {
      null
    }

    // Resolve driver type: CLI --driver flag overrides trail config driver field.
    val driverString = driverType ?: trailConfig?.driver
    val trailDriverType = driverString?.let { ds ->
      TrailblazeDriverType.fromString(ds)
        ?: run {
          Console.error("Error: Unknown driver type '$ds'.")
          Console.error("Valid driver types: ${TrailblazeDriverType.entries.joinToString { it.name }}")
          return CommandLine.ExitCode.SOFTWARE to emptyList()
        }
    }

    // Derive platform: driver takes precedence, then fall back to config platform string.
    val trailPlatform = trailDriverType?.platform
      ?: trailConfig?.platform?.let { TrailblazeDevicePlatform.fromString(it) }

    val isComposeTrail = trailDriverType == TrailblazeDriverType.COMPOSE

    // For web trails, inject the virtual device directly — no physical device discovery needed.
    val isWebTrail = trailPlatform == TrailblazeDevicePlatform.WEB
    if (isWebTrail) {
      Console.info("Detected web trail — using Playwright browser")
    }
    if (isComposeTrail) {
      Console.log("Detected compose trail — using Compose RPC driver (port $composePort)")
    }

    // Load devices (skip expensive Android/iOS scan for web-only and compose trails)
    val allDevices = if (isComposeTrail) {
      listOf(
        TrailblazeConnectedDeviceSummary(
          trailblazeDriverType = TrailblazeDriverType.COMPOSE,
          instanceId = "compose",
          description = "Compose (RPC)",
        ),
      )
    } else if (isWebTrail) {
      buildList {
        add(
          TrailblazeConnectedDeviceSummary(
            trailblazeDriverType = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
            instanceId = TrailblazeDeviceManager.PLAYWRIGHT_NATIVE_INSTANCE_ID,
            description = "Playwright Browser (Native)",
          ),
        )
        add(
          TrailblazeConnectedDeviceSummary(
            trailblazeDriverType = TrailblazeDriverType.PLAYWRIGHT_ELECTRON,
            instanceId = TrailblazeDeviceManager.PLAYWRIGHT_ELECTRON_INSTANCE_ID,
            description = "Playwright Electron (CDP)",
          ),
        )
      }
    } else {
      Console.info("Loading connected devices...")
      val scannedDevices = runBlocking {
        app.deviceManager.loadDevicesSuspend(applyDriverFilter = false)
      }
      if (scannedDevices.isEmpty()) {
        Console.error("Error: No devices connected.")
        Console.error("  Android: Connect via USB or start an emulator")
        Console.error("  iOS: Start an iOS simulator via Xcode")
        Console.error("  Web: Add 'driver: PLAYWRIGHT_NATIVE' to your trail config")
        return CommandLine.ExitCode.SOFTWARE to emptyList()
      }
      scannedDevices
    }

    // Find the target device
    val targetDevice = if (deviceId != null) {
      // Explicit --device flag: find by instance ID
      allDevices.find { it.trailblazeDeviceId.instanceId == deviceId }
        ?: allDevices.find { it.trailblazeDeviceId.instanceId.contains(deviceId!!) }
        ?: run {
          Console.error("Error: Device '$deviceId' not found.")
          Console.error("Available devices:")
          allDevices.forEach { device ->
            Console.error("  - ${device.trailblazeDeviceId.instanceId} (${device.platform.displayName}, ${device.trailblazeDriverType})")
          }
          return CommandLine.ExitCode.SOFTWARE to emptyList()
        }
    } else if (trailDriverType != null) {
      // Driver specified: match by driver type directly
      allDevices.find { it.trailblazeDriverType == trailDriverType }?.also {
        Console.info("Auto-selected device for driver '$trailDriverType': ${it.trailblazeDeviceId.instanceId}")
      } ?: run {
        Console.error("Error: Trail requires driver '$trailDriverType' but no matching device found.")
        Console.error("Available devices:")
        allDevices.forEach { device ->
          Console.error("  - ${device.trailblazeDeviceId.instanceId} (${device.platform.displayName}, ${device.trailblazeDriverType})")
        }
        return CommandLine.ExitCode.SOFTWARE to emptyList()
      }
    } else if (trailPlatform != null) {
      // Platform specified (no driver): auto-select device matching the trail's platform hint.
      // For WEB, prefer PLAYWRIGHT_NATIVE.
      val platformDevices = allDevices.filter { it.platform == trailPlatform }
      (platformDevices.find {
        it.trailblazeDriverType == TrailblazeDriverType.PLAYWRIGHT_NATIVE
      } ?: platformDevices.firstOrNull())?.also {
        Console.info("Auto-selected device for platform '$trailPlatform': ${it.trailblazeDeviceId.instanceId}")
      } ?: run {
        Console.error("Error: Trail requires platform '$trailPlatform' but no matching device found.")
        Console.error("Available devices:")
        allDevices.forEach { device ->
          Console.error("  - ${device.trailblazeDeviceId.instanceId} (${device.platform.displayName}, ${device.trailblazeDriverType})")
        }
        return CommandLine.ExitCode.SOFTWARE to emptyList()
      }
    } else {
      allDevices.first().also {
        Console.info("Using first available device: ${it.trailblazeDeviceId.instanceId}")
      }
    }

    Console.info("Target device: ${targetDevice.trailblazeDeviceId.instanceId} (${targetDevice.platform.displayName})")
    Console.info("Driver: ${targetDevice.trailblazeDriverType}")

    // Parse agent implementation
    val agentImpl = try {
      AgentImplementation.valueOf(agent.uppercase())
    } catch (e: IllegalArgumentException) {
      Console.error("Error: Invalid agent implementation '$agent'.")
      Console.error("Valid options: ${AgentImplementation.entries.joinToString(", ") { it.name }}")
      return 1 to emptyList()
    }

    // Get LLM model: CLI flags override saved settings
    val config = parent.configProvider()
    val llmModel = if (llmProvider != null || llmModel != null) {
      config.resolveLlmModel(llmProvider, llmModel)
        ?: run {
          Console.error("Error: Could not resolve LLM model for provider='$llmProvider', model='$llmModel'.")
          Console.error("Ensure the provider has a configured API key and the model ID is valid.")
          return CommandLine.ExitCode.SOFTWARE to emptyList()
        }
    } else {
      try {
        config.getCurrentLlmModel()
      } catch (e: Exception) {
        Console.error("Error: No LLM configured. Please set an API key environment variable.")
        Console.error("Supported: ANTHROPIC_API_KEY, OPENAI_API_KEY, GOOGLE_API_KEY")
        return CommandLine.ExitCode.SOFTWARE to emptyList()
      }
    }

    Console.info("Using LLM: ${llmModel.trailblazeLlmProvider.id}/${llmModel.modelId}")
    Console.info("Agent: $agentImpl")

    // Derive a meaningful test name: use parent directory name for the standard
    // subdirectory layout (e.g., test-counter/trail.yaml → "test-counter"),
    // otherwise fall back to the filename without extension.
    val testName = if (file.nameWithoutExtension == "trail" || file.nameWithoutExtension == "recording.trail") {
      file.absoluteFile.parentFile?.name ?: file.nameWithoutExtension
    } else {
      file.nameWithoutExtension
    }

    // Auto-detect recorded steps: if the trail YAML contains recordings, use them.
    val effectiveUseRecordedSteps = useRecordedSteps || try {
      val trailYaml = createTrailblazeYaml()
      val trailItems = trailYaml.decodeTrail(yamlContent)
      trailYaml.hasRecordedSteps(trailItems)
    } catch (_: Exception) {
      false
    }

    // Create the run request
    val runYamlRequest = RunYamlRequest(
      testName = testName,
      yaml = yamlContent,
      trailFilePath = file.absolutePath,
      targetAppName = trailConfig?.app,
      useRecordedSteps = effectiveUseRecordedSteps,
      trailblazeDeviceId = targetDevice.trailblazeDeviceId,
      trailblazeLlmModel = llmModel,
      driverType = trailDriverType,
      config = TrailblazeConfig(
        setOfMarkEnabled = setOfMark,
        browserHeadless = !showBrowser,
      ),
      referrer = TrailblazeReferrer(id = "cli", display = "CLI"),
      agentImplementation = agentImpl,
    )

    // Latch to wait for completion
    val completionLatch = CountDownLatch(1)
    var exitCode = CommandLine.ExitCode.OK

    // Resolve target app: prefer trail config's `app` field, fall back to settings selection.
    // This ensures custom tools (e.g., myApp_launchSignedIn) are registered for the
    // correct app even when the desktop UI has a different app selected.
    val targetTestApp = trailConfig?.app?.let { config.availableAppTargets.findById(it) }
      ?: app.deviceManager.getCurrentSelectedTargetApp()
    if (verbose) {
      Console.log("Target app: ${targetTestApp?.displayName ?: "None (using built-in tools only)"}")
    }

    val params = DesktopAppRunYamlParams(
      forceStopTargetApp = false,
      runYamlRequest = runYamlRequest,
      targetTestApp = targetTestApp,
      onProgressMessage = { message ->
        Console.info(message)
      },
      onConnectionStatus = { status ->
        when (status) {
          is DeviceConnectionStatus.DeviceConnectionError.ConnectionFailure -> {
            Console.error("Connection failed: ${status.errorMessage}")
            exitCode = CommandLine.ExitCode.SOFTWARE
            completionLatch.countDown()
          }
          is DeviceConnectionStatus.WithTargetDevice.TrailblazeInstrumentationRunning -> {
            if (verbose) {
              Console.log("Instrumentation running on device: ${status.trailblazeDeviceId.instanceId}")
            }
          }
          else -> {
            if (verbose) {
              Console.log("Connection status: $status")
            }
          }
        }
      },
      additionalInstrumentationArgs = emptyMap(),
      composeRpcPort = composePort,
      onComplete = { result ->
        when (result) {
          is TrailExecutionResult.Success -> {
            Console.info("\n✅ Trail completed successfully!")
            exitCode = CommandLine.ExitCode.OK
          }
          is TrailExecutionResult.Failed -> {
            Console.error("\n❌ Trail failed: ${result.errorMessage ?: "Unknown error"}")
            exitCode = CommandLine.ExitCode.SOFTWARE
          }
          is TrailExecutionResult.Cancelled -> {
            Console.info("\n⚠️ Trail was cancelled")
            exitCode = CommandLine.ExitCode.SOFTWARE
          }
        }
        completionLatch.countDown()
      },
    )

    Console.info("\nStarting trail execution...")
    Console.info("=".repeat(60))

    // Snapshot existing session IDs so we can identify new ones after execution
    val logsRepo = app.deviceManager.logsRepo
    val existingSessionIds = logsRepo.getSessionIds().toSet()

    // Run the trail
    app.desktopYamlRunner.runYaml(params)

    // Wait for completion
    try {
      completionLatch.await()
    } catch (e: InterruptedException) {
      Console.info("Execution interrupted")
      exitCode = CommandLine.ExitCode.SOFTWARE
    }

    // Identify sessions created during this run and wait for their logs to stabilize.
    // The completion callback fires as soon as the trail finishes, but the device-side
    // agent may still be streaming log data (LLM requests, tool logs, session status).
    // Without waiting, exitProcess() kills the HTTP server before logs are persisted.
    val newSessionIds = awaitLogStability(logsRepo, existingSessionIds)

    // Cross-check session status from logs. The TrailExecutionResult callback may report
    // success even when individual objectives failed (e.g., the runner completed without
    // crashing but the session ended with a failure status). For on-device instrumentation,
    // the RPC call returns immediately so the TrailExecutionResult is always Success.
    // The session logs are the source of truth for pass/fail.
    if (exitCode == CommandLine.ExitCode.OK) {
      for (sessionId in newSessionIds) {
        val status = logsRepo.getLogsForSession(sessionId).getSessionStatus()
        if (status is SessionStatus.Unknown) {
          Console.error("Session $sessionId has no status (no logs received)")
          exitCode = CommandLine.ExitCode.SOFTWARE
        } else if (status is SessionStatus.Ended && status !is SessionStatus.Ended.Succeeded &&
          status !is SessionStatus.Ended.SucceededWithFallback) {
          Console.error("Session $sessionId ended with status: ${status::class.simpleName}")
          exitCode = CommandLine.ExitCode.SOFTWARE
        } else if (status !is SessionStatus.Ended) {
          Console.error("Session $sessionId did not complete (status: ${status::class.simpleName})")
          exitCode = CommandLine.ExitCode.SOFTWARE
        }
      }
    }

    // Generate recording YAML from session logs so saveRecordingToTrailDirectory can find it.
    // For host-mode runs, TrailblazeHostYamlRunner already generates this file. For on-device
    // instrumentation runs, the recording is not generated during execution, so we generate
    // it here from the session logs.
    if (!noRecord) {
      for (sessionId in newSessionIds) {
        generateRecordingForSession(logsRepo, sessionId)
      }
    }

    return exitCode to newSessionIds
  }

  /**
   * Lightweight recording generator for the delegate-to-daemon path.
   * Runs in the CLI process so it doesn't block the daemon's HTTP server.
   */
  private fun generateRecordingForSessionFromDisk(sessionId: SessionId) {
    val gitRoot = GitUtils.getGitRootViaCommand() ?: return
    val logsDir = File(File(gitRoot, "logs"), sessionId.value)
    if (!logsDir.exists()) return
    // Delegate to the full implementation using a dummy logsRepo — it's not used.
    generateRecordingForSession(logsRepo = null, sessionId = sessionId)
  }

  /**
   * Generates a `recording.trail.yaml` file in the session's log directory from
   * the session logs, if one doesn't already exist.
   *
   * Reads logs directly from disk rather than from the cached flow, because for
   * on-device instrumentation runs the flow cache may not have all logs yet.
   */
  private fun generateRecordingForSession(
    @Suppress("UNUSED_PARAMETER") logsRepo: xyz.block.trailblaze.report.utils.LogsRepo?,
    sessionId: SessionId,
  ) {
    try {
      val gitRoot = GitUtils.getGitRootViaCommand() ?: return
      val sessionDir = File(File(gitRoot, "logs"), sessionId.value)
      val recordingFile = File(sessionDir, "recording.trail.yaml")
      if (recordingFile.exists()) return // Already generated (e.g., by host-mode runner)

      // Wait for all selector-based TrailblazeToolLog entries to arrive on disk.
      // On-device instrumentation logs the DelegatingTrailblazeToolLog (nodeId-based)
      // immediately but the corresponding TrailblazeToolLog (selector-based, same
      // traceId) is flushed later — sometimes 10-30s after the Ended status.
      // Poll until every DelegatingTrailblazeToolLog has a matching TrailblazeToolLog.
      val maxWaitMs = 120_000L
      val pollMs = 2_000L
      val waitStart = System.currentTimeMillis()

      fun readLogs(): List<xyz.block.trailblaze.logs.client.TrailblazeLog> {
        val logFiles = sessionDir.listFiles()?.filter { it.extension == "json" } ?: emptyList()
        return logFiles.mapNotNull { file ->
          try {
            xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
              .decodeFromString<xyz.block.trailblaze.logs.client.TrailblazeLog>(file.readText())
          } catch (_: Exception) {
            null
          }
        }.sortedBy { it.timestamp }
      }

      var logs = readLogs()
      while (System.currentTimeMillis() - waitStart < maxWaitMs) {
        val delegatingTraceIds = logs
          .filterIsInstance<xyz.block.trailblaze.logs.client.TrailblazeLog.DelegatingTrailblazeToolLog>()
          .mapNotNull { it.traceId }
          .toSet()
        val selectorTraceIds = logs
          .filterIsInstance<xyz.block.trailblaze.logs.client.TrailblazeLog.TrailblazeToolLog>()
          .mapNotNull { it.traceId }
          .toSet()
        val missingTraceIds = delegatingTraceIds - selectorTraceIds
        if (missingTraceIds.isEmpty()) break
        Console.log("Recording: waiting for ${missingTraceIds.size} TrailblazeToolLog(s) to arrive...")
        Thread.sleep(pollMs)
        logs = readLogs()
      }

      if (logs.isEmpty()) {
        Console.log("No logs found for session ${sessionId.value}, skipping recording generation")
        return
      }

      // Extract session config from the Started status log
      val startedStatus = logs
        .filterIsInstance<xyz.block.trailblaze.logs.client.TrailblazeLog.TrailblazeSessionStatusChangeLog>()
        .map { it.sessionStatus }
        .filterIsInstance<SessionStatus.Started>()
        .firstOrNull()

      val sessionTrailConfig = startedStatus?.let { started ->
        val originalConfig = started.trailConfig
        xyz.block.trailblaze.yaml.TrailConfig(
          id = originalConfig?.id,
          title = originalConfig?.title,
          description = originalConfig?.description,
          priority = originalConfig?.priority,
          context = originalConfig?.context,
          source = originalConfig?.source,
          metadata = originalConfig?.metadata,
          app = originalConfig?.app,
          driver = started.trailblazeDeviceInfo.trailblazeDriverType.name,
          platform = started.trailblazeDeviceInfo.platform.name.lowercase(),
        )
      }

      // Include driver-specific tool classes so the YAML serializer recognizes
      // all tools that may appear in the session logs (e.g., Playwright tools are
      // not in AllBuiltInTrailblazeToolsForSerialization).
      val driverType = startedStatus?.trailblazeDeviceInfo?.trailblazeDriverType
      val customToolClasses = when (driverType) {
        TrailblazeDriverType.PLAYWRIGHT_NATIVE ->
          PlaywrightNativeToolSet.LlmToolSet.toolClasses
        TrailblazeDriverType.PLAYWRIGHT_ELECTRON ->
          PlaywrightNativeToolSet.LlmToolSet.toolClasses +
              BasePlaywrightElectronTest.ELECTRON_BUILT_IN_TOOL_CLASSES
        TrailblazeDriverType.COMPOSE ->
          ComposeToolSet.LlmToolSet.toolClasses
        else -> emptySet()
      }

      val recordingYaml = logs.generateRecordedYaml(
        sessionTrailConfig = sessionTrailConfig,
        customToolClasses = customToolClasses,
      )
      if (recordingYaml.isBlank()) {
        Console.log("No recording data for session ${sessionId.value}, skipping")
        return
      }

      recordingFile.writeText(recordingYaml)
      Console.log("Recording generated: ${recordingFile.absolutePath}")
    } catch (e: Exception) {
      Console.log("Failed to generate recording for session ${sessionId.value}: ${e.message}")
    }
  }

  /**
   * Waits for session logs to reach a terminal state before returning.
   *
   * For on-device instrumentation, the RPC call returns immediately so this method
   * effectively waits for the entire trail execution to complete. For host-mode execution,
   * it waits for any trailing log data after the runner finishes.
   *
   * This method polls the logs directory until:
   * 1. At least one new session directory appears (up to [sessionDetectionTimeoutMs])
   * 2. All sessions reach a terminal [SessionStatus.Ended] status, then waits a short
   *    buffer for any trailing files
   * 3. Falls back to [maxWaitMs] timeout if the Ended status never arrives
   *
   * Sessions that exceed the [maxWaitMs] without reaching Ended status are treated as
   * failures by the caller's cross-check logic.
   */
  private fun awaitLogStability(
    logsRepo: LogsRepo,
    existingSessionIds: Set<SessionId>,
    sessionDetectionTimeoutMs: Long = 10_000,
    postEndedBufferMs: Long = 3_000,
    maxWaitMs: Long = 600_000,
    pollIntervalMs: Long = 500,
  ): List<SessionId> {
    // Phase 1: Wait for at least one new session directory to appear.
    val detectionStart = System.currentTimeMillis()
    var newSessionIds = emptyList<SessionId>()
    while (System.currentTimeMillis() - detectionStart < sessionDetectionTimeoutMs) {
      newSessionIds = logsRepo.getSessionIds().filter { it !in existingSessionIds }
      if (newSessionIds.isNotEmpty()) break
      Thread.sleep(pollIntervalMs)
    }
    if (newSessionIds.isEmpty()) return emptyList()

    // Phase 2: Wait for all sessions to reach a terminal status (Ended).
    // The on-device agent sends the Ended status log after completing execution, so this
    // is the definitive signal that the test is done and all logs have been generated.
    val waitStart = System.currentTimeMillis()
    var allEnded = false

    while (System.currentTimeMillis() - waitStart < maxWaitMs) {
      // Re-check for session IDs in case more appear.
      newSessionIds = logsRepo.getSessionIds().filter { it !in existingSessionIds }

      allEnded = newSessionIds.all { sessionId ->
        val status = logsRepo.getLogsForSession(sessionId).getSessionStatus()
        status is SessionStatus.Ended
      }

      if (allEnded) break
      Thread.sleep(pollIntervalMs)
    }

    // Phase 3: Short buffer after Ended status for any trailing files (screenshots, etc.).
    if (allEnded) {
      Thread.sleep(postEndedBufferMs)
      // Re-check for any new sessions that appeared during the buffer.
      newSessionIds = logsRepo.getSessionIds().filter { it !in existingSessionIds }
    }

    return newSessionIds
  }

  /**
   * Copies the session recording back to the trail source directory as a platform-specific
   * recording file (e.g., `android.trail.yaml`, `ios-iphone.trail.yaml`).
   *
   * @param trailFile The trail file or directory that was executed
   * @param sessionId The session ID from the completed run
   * @param deviceClassifiers Device classifiers for computing the recording filename
   */
  private fun saveRecordingToTrailDirectory(
    trailFile: File,
    sessionId: SessionId,
    deviceClassifiers: List<String>,
  ) {
    try {
      val gitRoot = GitUtils.getGitRootViaCommand() ?: return
      val recordingFile = File(File(File(gitRoot, "logs"), sessionId.value), "recording.trail.yaml")
      if (!recordingFile.exists()) {
        Console.log("No recording found for session ${sessionId.value}")
        return
      }

      // Determine target directory: same directory as the trail file (or the directory itself)
      val targetDir = if (trailFile.isDirectory) trailFile else trailFile.parentFile ?: return

      // Compute the recording filename from device classifiers
      val recordingFileName = if (deviceClassifiers.isNotEmpty()) {
        deviceClassifiers.joinToString("-") + TrailRecordings.DOT_TRAIL_DOT_YAML_FILE_SUFFIX
      } else {
        "recording${TrailRecordings.DOT_TRAIL_DOT_YAML_FILE_SUFFIX}"
      }

      val targetFile = File(targetDir, recordingFileName)
      recordingFile.copyTo(targetFile, overwrite = true)
      Console.info("Recording saved to: ${targetFile.absolutePath}")
    } catch (e: Exception) {
      Console.error("Failed to save recording to trail directory: ${e.message}")
    }
  }
}

/**
 * Start the MCP server with a specified transport.
 *
 * By default, starts an STDIO server for MCP client integrations
 * (e.g., Claude Code, Claude Desktop, Firebender, Goose) with a menu bar
 * tray icon so you can open the Trailblaze desktop app and view logs.
 * Use `--http` to start a standalone Streamable HTTP server instead.
 *
 * Examples:
 *   trailblaze mcp                  - Start STDIO MCP server with tray icon
 *   trailblaze mcp --http           - Start Streamable HTTP MCP server
 *   trailblaze mcp --http -p 8080   - Start HTTP MCP server on port 8080
 */
@Command(
  name = "mcp",
  mixinStandardHelpOptions = true,
  description = ["Start the MCP server"],
  subcommands = [McpInstallCommand::class]
)
class McpCommand : Callable<Int> {

  @CommandLine.ParentCommand
  private lateinit var parent: TrailblazeCliCommand

  @Option(
    names = ["--http"],
    description = ["Use Streamable HTTP transport instead of STDIO. Starts a standalone HTTP MCP server."]
  )
  var http: Boolean = false

  @Option(
    names = ["--direct", "--no-daemon"],
    description = [
      "Run as an in-process MCP server over STDIO instead of the default proxy mode. " +
        "Bypasses the Trailblaze daemon and runs everything in a single process. " +
        "Use this for environments where the HTTP daemon cannot run."
    ],
  )
  var direct: Boolean = false

  @Option(
    names = ["--tool-profile"],
    description = ["Tool profile: FULL or MINIMAL (only device/blaze/verify/ask/trail). Defaults to MINIMAL for STDIO, FULL for HTTP."],
  )
  var toolProfile: String? = null

  override fun call(): Int {
    // Resolve tool profile: env var > CLI flag > transport-based default
    // STDIO defaults to MINIMAL (external MCP clients), HTTP defaults to FULL (internal use)
    val transportDefault = if (http) McpToolProfile.FULL else McpToolProfile.MINIMAL
    val resolvedProfile = System.getenv("TRAILBLAZE_TOOL_PROFILE")?.let {
      McpToolProfile.valueOf(it.uppercase())
    } ?: toolProfile?.let { McpToolProfile.valueOf(it.uppercase()) } ?: transportDefault

    // OSS CLI always starts in MCP_CLIENT_AS_AGENT — the external client is the agent.
    // TRAILBLAZE_AS_AGENT is the server default for deployments with a configured LLM.
    val resolvedMode = TrailblazeMcpMode.MCP_CLIENT_AS_AGENT

    if (http) {
      // Streamable HTTP transport (explicit opt-in)
      System.setProperty("java.awt.headless", "true")

      val app = parent.appProvider()
      app.trailblazeMcpServer.defaultToolProfile = resolvedProfile
      app.trailblazeMcpServer.defaultMode = resolvedMode
      val port = parent.getEffectivePort()
      val httpsPort = parent.getEffectiveHttpsPort()
      Console.log("Trailblaze MCP server starting with HTTP transport on port $port (profile=${resolvedProfile.name})...")

      // Apply port overrides so the server knows the effective ports
      if (parent.hasPortOverride()) {
        app.applyPortOverrides(httpPort = port, httpsPort = httpsPort)
      }

      app.trailblazeMcpServer.startStreamableHttpMcpServer(
        port = port,
        httpsPort = httpsPort,
        wait = true,
      )
    } else if (direct) {
      // Direct STDIO transport (opt-in via --direct/--no-daemon) — runs an in-process
      // MCP server without the HTTP daemon proxy. Use for environments where the
      // daemon cannot run.
      //
      // Capture the current stdout BEFORE redirecting — DesktopLogFileWriter may have
      // already wrapped it with a tee (JSON-RPC goes to both stdout and log file).
      // After Console.useStdErr(), System.out is redirected to stderr, so we must
      // save the reference now for the STDIO transport.
      val stdoutForTransport = System.out

      // Redirect all console output to stderr BEFORE app initialization so that
      // settings loading, device scanning, and other startup output doesn't
      // contaminate stdout (which must be a clean JSON-RPC stream).
      Console.useStdErr()

      // Install file logging AFTER useStdErr so that Console.log output is saved
      // to ~/.trailblaze/desktop-logs/trailblaze.log. This is safe because System.out
      // is now stderr, so DesktopLogFileWriter tees stderr→file (not the STDIO pipe).
      DesktopLogFileWriter.install(httpPort = parent.getEffectivePort())

      Console.log("Trailblaze MCP server starting with direct STDIO transport (profile=${resolvedProfile.name})...")

      val app = parent.appProvider()
      app.trailblazeMcpServer.defaultMode = resolvedMode

      // Apply port overrides before starting the daemon
      if (parent.hasPortOverride()) {
        app.applyPortOverrides(httpPort = parent.getEffectivePort(), httpsPort = parent.getEffectiveHttpsPort())
      }

      // Start the HTTP daemon for device log ingestion (if not already running).
      // Returns true if this process started the daemon (we own it),
      // false if another process already had it running.
      val ownsDaemon = app.ensureServerRunning()

      if (ownsDaemon && !GraphicsEnvironment.isHeadless()) {
        // This process owns the daemon — show tray icon with window hidden.
        // The Compose Desktop event loop must run on the main thread (macOS AppKit
        // requirement), so we launch the STDIO server on a background thread.
        // NON-daemon so the JVM stays alive even if the desktop app exits early.
        val stdioThread = Thread {
          runBlocking {
            app.trailblazeMcpServer.startStdioMcpServer(
              stdout = stdoutForTransport,
              toolProfile = resolvedProfile,
            )
          }
          // Client disconnected — request graceful shutdown via the Compose event loop.
          // onShutdownRequest is wired to exitApplication() inside the Compose app block,
          // which cleanly tears down the UI, runs shutdown hooks, and exits the process.
          app.trailblazeMcpServer.onShutdownRequest?.invoke() ?: exitProcess(0)
        }
        stdioThread.isDaemon = false // Must be non-daemon: if the desktop app crashes or
        // exits early, the JVM must stay alive to keep the STDIO pipe open for MCP.
        stdioThread.name = "mcp-stdio"
        stdioThread.start()

        // Start the desktop app on the main thread (tray icon visible, window hidden).
        // The HTTP server is already running via ensureServerRunning(), so
        // startTrailblazeDesktopApp will detect the daemon and skip starting another.
        // Wrapped in try-catch: if the desktop app crashes, the STDIO server must keep
        // running (the non-daemon thread above keeps the JVM alive).
        try {
          app.startTrailblazeDesktopApp(headless = true)
        } catch (e: Exception) {
          Console.error("[MCP] Desktop app exited with error: ${e.message}")
          Console.error("[MCP] STDIO MCP server continues running without tray icon.")
          // Block the main thread so the JVM doesn't exit.
          // The STDIO thread will call exitProcess(0) when the client disconnects.
          stdioThread.join()
        }
      } else {
        // Secondary STDIO client (daemon owned by another process), or no display.
        // Run STDIO server directly — no tray icon needed.
        if (!ownsDaemon) {
          Console.log("Daemon already running on port ${parent.getEffectivePort()} — running as headless STDIO client")
        }
        runBlocking {
          app.trailblazeMcpServer.startStdioMcpServer(
            stdout = stdoutForTransport,
            toolProfile = resolvedProfile,
          )
        }
      }
    } else {
      // Default: STDIO-to-HTTP proxy mode — lightweight proxy that forwards JSON-RPC
      // to the Trailblaze daemon. Reconnects transparently on daemon restarts.
      return McpProxy(port = parent.getEffectivePort()).run()
    }

    return CommandLine.ExitCode.OK
  }
}

/**
 * List all connected devices.
 */
@Command(
  name = "devices",
  mixinStandardHelpOptions = true,
  description = ["List all connected devices"]
)
class DevicesCommand : Callable<Int> {

  @CommandLine.ParentCommand
  private lateinit var parent: TrailblazeCliCommand

  override fun call(): Int {
    val app = parent.appProvider()

    Console.log("Scanning for connected devices...")
    val devices = runBlocking {
      app.deviceManager.loadDevicesSuspend(applyDriverFilter = false)
    }

    if (devices.isEmpty()) {
      Console.log("No devices found.")
      Console.log("")
      Console.log("To connect devices:")
      Console.log("  Android: Connect via USB or start an emulator")
      Console.log("  iOS: Start an iOS simulator via Xcode")
      exitProcess(CommandLine.ExitCode.OK)
    }

    Console.log("")
    Console.log("Connected Devices:")
    Console.log("-".repeat(80))
    devices.forEach { device ->
      Console.log("  ID:       ${device.trailblazeDeviceId.instanceId}")
      Console.log("  Platform: ${device.platform.displayName}")
      Console.log("  Driver:   ${device.trailblazeDriverType}")
      Console.log("")
    }
    Console.log("Total: ${devices.size} device(s)")

    // Force exit to terminate background services started by app initialization
    exitProcess(CommandLine.ExitCode.OK)
  }
}

/**
 * View and modify Trailblaze configuration.
 *
 * Examples:
 *   trailblaze config                                 - Show all settings + auth status
 *   trailblaze config llm                             - Show current LLM provider/model
 *   trailblaze config llm openai/gpt-4-1              - Set LLM provider and model
 *   trailblaze config llm-provider anthropic           - Set LLM provider
 *   trailblaze config agent MULTI_AGENT_V3              - Set agent implementation
 *   trailblaze config models                           - List available LLM models
 *   trailblaze config agents                           - List available agents
 *   trailblaze config drivers                          - List available drivers
 */
@Command(
  name = "config",
  mixinStandardHelpOptions = true,
  description = ["View and modify Trailblaze configuration"],
  subcommands = [
    ConfigModelsCommand::class,
    ConfigAgentsCommand::class,
    ConfigDriversCommand::class,
  ],
)
class ConfigCommand : Callable<Int> {

  @CommandLine.ParentCommand
  private lateinit var parent: TrailblazeCliCommand

  @Parameters(index = "0", arity = "0..1", description = ["Config key to get or set"])
  var key: String? = null

  @Parameters(index = "1", arity = "0..1", description = ["Value to set"])
  var value: String? = null

  fun getConfigProvider(): TrailblazeDesktopAppConfig = parent.configProvider()

  override fun call(): Int {
    if (key == null) {
      // No args: show all config + auth status (requires configProvider)
      return showAllConfig()
    }

    val configKey = CONFIG_KEYS[key]
    if (configKey == null) {
      Console.error("Unknown config key: $key")
      Console.error("Valid keys: ${CONFIG_KEYS.keys.joinToString(", ")}")
      return CommandLine.ExitCode.USAGE
    }

    if (value == null) {
      // Key only: show that key's value
      val currentConfig = CliConfigHelper.getOrCreateConfig()
      Console.log(configKey.get(currentConfig))
      return CommandLine.ExitCode.OK
    }

    // Key + value: set and save
    val currentConfig = CliConfigHelper.getOrCreateConfig()
    val updatedConfig = configKey.set(currentConfig, value!!)
    if (updatedConfig == null) {
      Console.error("Invalid value for $key: $value")
      Console.error("Valid values: ${configKey.validValues}")
      return CommandLine.ExitCode.USAGE
    }
    CliConfigHelper.writeConfig(updatedConfig)
    Console.log("Set $key: ${configKey.get(updatedConfig)}")
    return CommandLine.ExitCode.OK
  }

  private fun showAllConfig(): Int {
    val currentConfig = CliConfigHelper.getOrCreateConfig()

    Console.log("")
    Console.log("Current Configuration:")
    Console.log("-".repeat(60))
    for (configKey in CONFIG_KEYS.values) {
      Console.log("  %-16s %s".format(configKey.name + ":", configKey.get(currentConfig)))
    }
    Console.log("")
    Console.log("Settings file: ${CliConfigHelper.getSettingsFile().absolutePath}")

    // Show auth status (requires configProvider — triggers full init)
    Console.log("")
    val config = getConfigProvider()
    val tokenStatuses = config.getAllLlmTokenStatuses()

    if (tokenStatuses.isNotEmpty()) {
      Console.log("LLM Authentication Status:")
      Console.log("-".repeat(60))

      tokenStatuses.forEach { (provider, status) ->
        val statusIcon = when (status) {
          is LlmTokenStatus.Available -> "+"
          is LlmTokenStatus.Expired -> "!"
          is LlmTokenStatus.NotAvailable -> "-"
        }
        val statusText = when (status) {
          is LlmTokenStatus.Available -> "Available"
          is LlmTokenStatus.Expired -> "Expired (may need refresh)"
          is LlmTokenStatus.NotAvailable -> "Not configured"
        }
        Console.log("  [$statusIcon] ${provider.display}: $statusText")
      }

      val missingProviders = tokenStatuses.filter { it.value is LlmTokenStatus.NotAvailable }
      if (missingProviders.isNotEmpty()) {
        Console.log("")
        Console.log("To configure missing providers, set the appropriate environment variables:")
        missingProviders.forEach { (provider, _) ->
          val envVar = config.getEnvironmentVariableForProvider(provider)
          if (envVar != null) {
            Console.log("  ${provider.display}: Set $envVar")
          }
        }
      }
      Console.log("")
    }

    // Force exit to terminate background services started by configProvider
    exitProcess(CommandLine.ExitCode.OK)
  }
}

/**
 * List available LLM models grouped by provider.
 */
@Command(
  name = "models",
  mixinStandardHelpOptions = true,
  description = ["List available LLM models by provider"],
)
class ConfigModelsCommand : Callable<Int> {

  @CommandLine.ParentCommand
  private lateinit var parent: ConfigCommand

  override fun call(): Int {
    val config = parent.getConfigProvider()
    val modelLists = config.getAllSupportedLlmModelLists()

    Console.log("")
    Console.log("Available LLM Models:")
    Console.log("=".repeat(60))

    for (modelList in modelLists.sortedBy { it.provider.id }) {
      Console.log("")
      Console.log("${modelList.provider.display} (${modelList.provider.id}):")
      Console.log("-".repeat(40))
      for (model in modelList.entries) {
        val contextK = model.contextLength / 1000
        val caps = model.capabilities.joinToString(", ") { it.id }
        Console.log("  %-35s %6dK  %s".format(model.modelId, contextK, caps))
      }
    }

    Console.log("")

    // Force exit to terminate background services started by configProvider
    exitProcess(CommandLine.ExitCode.OK)
  }
}

/**
 * List available agent implementations.
 */
@Command(
  name = "agents",
  mixinStandardHelpOptions = true,
  description = ["List available agent implementations"],
)
class ConfigAgentsCommand : Callable<Int> {

  override fun call(): Int {
    val currentConfig = CliConfigHelper.getOrCreateConfig()

    Console.log("")
    Console.log("Available Agents:")
    Console.log("-".repeat(60))

    for (agent in AgentImplementation.entries) {
      val current = if (agent == currentConfig.agentImplementation) " (current)" else ""
      val isDefault = if (agent == AgentImplementation.DEFAULT) " [default]" else ""
      Console.log("  ${agent.name}$current$isDefault")
    }

    Console.log("")
    return CommandLine.ExitCode.OK
  }
}

/**
 * List available driver types grouped by platform.
 */
@Command(
  name = "drivers",
  mixinStandardHelpOptions = true,
  description = ["List available driver types"],
)
class ConfigDriversCommand : Callable<Int> {

  override fun call(): Int {
    val currentConfig = CliConfigHelper.getOrCreateConfig()

    Console.log("")
    Console.log("Available Drivers:")
    Console.log("=".repeat(60))

    for (platform in TrailblazeDevicePlatform.entries) {
      Console.log("")
      Console.log("${platform.displayName}:")
      Console.log("-".repeat(40))
      val drivers = TrailblazeDriverType.entries.filter { it.platform == platform }
      val currentDriver = currentConfig.selectedTrailblazeDriverTypes[platform]
      for (driver in drivers) {
        val current = if (driver == currentDriver) " (current)" else ""
        Console.log("  ${driver.name}$current")
      }
    }

    Console.log("")
    return CommandLine.ExitCode.OK
  }
}

/**
 * Check if the Trailblaze daemon is running.
 */
@Command(
  name = "status",
  mixinStandardHelpOptions = true,
  description = ["Check if the Trailblaze daemon is running"]
)
class StatusCommand : Callable<Int> {

  @CommandLine.ParentCommand
  private lateinit var parent: TrailblazeCliCommand

  override fun call(): Int {
    val daemon = DaemonClient(port = parent.getEffectivePort())

    if (!daemon.isRunning()) {
      Console.log("Trailblaze daemon is not running.")
      Console.log("")
      Console.log("Start the daemon with: trailblaze")
      return CommandLine.ExitCode.OK
    }

    val status = daemon.getStatus()

    Console.log("Trailblaze daemon is running.")
    Console.log("")
    if (status != null) {
      Console.log("  Port:              ${status.port}")
      Console.log("  Connected devices: ${status.connectedDevices}")
      Console.log("  Uptime:            ${status.uptimeSeconds.seconds}")
      if (status.activeSessionId != null) {
        Console.log("  Active session:    ${status.activeSessionId}")
      }
    }

    return CommandLine.ExitCode.OK
  }
}

/**
 * Stop the Trailblaze daemon.
 */
@Command(
  name = "stop",
  mixinStandardHelpOptions = true,
  description = ["Stop the Trailblaze daemon"]
)
class StopCommand : Callable<Int> {

  @CommandLine.ParentCommand
  private lateinit var parent: TrailblazeCliCommand

  @Option(
    names = ["-f", "--force"],
    description = ["Force stop (kill process) if graceful shutdown fails"]
  )
  var force: Boolean = false

  override fun call(): Int {
    val daemon = DaemonClient(port = parent.getEffectivePort())

    if (!daemon.isRunning()) {
      Console.log("Trailblaze daemon is not running.")
      return CommandLine.ExitCode.OK
    }

    Console.log("Stopping Trailblaze daemon...")

    val response = daemon.shutdown()
    if (response.success) {
      Console.appendLog("Waiting for daemon to stop")

      // Wait for daemon to actually stop, printing dots for progress
      var attempts = 0
      while (daemon.isRunning() && attempts < 20) {
        Console.appendLog(".")
        Thread.sleep(500)
        attempts++
      }
      Console.log("")

      if (daemon.isRunning()) {
        if (force) {
          Console.error("Daemon did not stop gracefully. Force option not yet implemented.")
          return CommandLine.ExitCode.SOFTWARE
        } else {
          Console.error("Daemon did not stop gracefully. Use --force to kill.")
          return CommandLine.ExitCode.SOFTWARE
        }
      }

      Console.log("Trailblaze daemon stopped.")
    } else {
      Console.error("Failed to stop daemon: ${response.message}")
      return CommandLine.ExitCode.SOFTWARE
    }

    return CommandLine.ExitCode.OK
  }
}

// Extension property to access appProvider from RunCommand/DevicesCommand/DesktopCommand
private val TrailblazeCliCommand.appProvider: () -> TrailblazeDesktopApp
  get() = this.let {
    val field = TrailblazeCliCommand::class.java.getDeclaredField("appProvider")
    field.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    field.get(this) as () -> TrailblazeDesktopApp
  }

private val TrailblazeCliCommand.configProvider: () -> TrailblazeDesktopAppConfig
  get() = this.let {
    val field = TrailblazeCliCommand::class.java.getDeclaredField("configProvider")
    field.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    field.get(this) as () -> TrailblazeDesktopAppConfig
  }
