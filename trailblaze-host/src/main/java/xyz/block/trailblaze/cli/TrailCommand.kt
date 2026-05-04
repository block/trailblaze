package xyz.block.trailblaze.cli

import kotlinx.coroutines.runBlocking
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import xyz.block.trailblaze.capture.CaptureOptions
import xyz.block.trailblaze.compose.driver.rpc.ComposeRpcServer
import xyz.block.trailblaze.compose.driver.tools.ComposeToolSetIds
import xyz.block.trailblaze.desktop.TrailblazeDesktopAppConfig
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.rules.BasePlaywrightElectronTest
import xyz.block.trailblaze.llm.LlmProviderEnvVarUtil
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeReferrer
import xyz.block.trailblaze.desktop.LlmTokenStatus
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.logs.model.getSessionStatus
import xyz.block.trailblaze.logs.server.endpoints.CliRunRequest
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.model.DesktopAppRunYamlParams
import xyz.block.trailblaze.model.DeviceConnectionStatus
import xyz.block.trailblaze.model.TrailExecutionResult
import xyz.block.trailblaze.model.TrailblazeConfig
import xyz.block.trailblaze.model.findById
import xyz.block.trailblaze.playwright.tools.WebToolSetIds
import xyz.block.trailblaze.recordings.TrailRecordings
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.report.utils.TrailblazeYamlSessionRecording.generateRecordedYaml
import xyz.block.trailblaze.revyl.tools.RevylToolSetIds
import xyz.block.trailblaze.toolcalls.TrailblazeToolSetCatalog
import xyz.block.trailblaze.ui.TrailblazeDesktopApp
import xyz.block.trailblaze.ui.TrailblazeDeviceManager
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.GitUtils
import xyz.block.trailblaze.util.TrailYamlTemplateResolver
import xyz.block.trailblaze.yaml.TrailConfig
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

/**
 * Run one or more trail files (`.trail.yaml` or `blaze.yaml`) on a connected device.
 * Takes either explicit file arguments or a shell glob that expands to file paths;
 * does not accept directory arguments.
 */
@Command(
  name = "trail",
  mixinStandardHelpOptions = true,
  description = ["Run a trail file (.trail.yaml) — execute a scripted test on a device"],
)
open class TrailCommand : Callable<Int> {

  @CommandLine.ParentCommand
  private lateinit var parent: TrailblazeCliCommand

  /** Set by the shutdown hook on Ctrl+C to stop processing further trail files. */
  @Volatile private var cancelled = false

  @Parameters(
    index = "0..*",
    arity = "1..*",
    paramLabel = "<trailFile>",
    description = ["One or more trail files (.trail.yaml or blaze.yaml). Use your shell's glob to run a batch (e.g., flows/**/*.trail.yaml)."]
  )
  lateinit var trailFiles: List<File>

  @Option(
    names = ["-d", "--device"],
    description = ["Device: platform (android, ios, web), platform/instance-id, or instance ID"]
  )
  var device: String? = null

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
    names = ["--self-heal"],
    description = [
      "When a recorded step fails, let AI take over and continue. " +
        "Overrides the persisted 'trailblaze config self-heal' setting for this run. " +
        "Omit to inherit the saved setting (opt-in, off by default)."
    ],
  )
  var selfHeal: Boolean? = null

  @Option(
    names = ["-v", "--verbose"],
    description = ["Enable verbose output"]
  )
  var verbose: Boolean = false

  @Option(
    names = ["--driver"],
    description = ["Driver type to use (e.g., PLAYWRIGHT_NATIVE, ANDROID_ONDEVICE_INSTRUMENTATION). Overrides driver from trail config."]
  )
  var driverType: String? = null

  // Legacy flag — kept for back-compat with existing scripts. New users should
  // prefer `--headless=false`. Hidden from `--help` so the new flag is the
  // single discoverable spelling.
  @Option(
    names = ["--show-browser"],
    description = ["[Deprecated] Show the browser window. Prefer --headless=false."],
    hidden = true,
  )
  var showBrowser: Boolean = false

  @Option(
    names = ["--headless"],
    description = [
      "Launch the Playwright browser headless (default true). Pass --no-headless or " +
        "--headless=false to surface a visible window. Equivalent to --show-browser when negated.",
    ],
    negatable = true,
  )
  var headless: Boolean = true

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
    names = ["--no-logging"],
    description = ["Disable session logging — no files written to logs/, session does not appear in Sessions tab"]
  )
  var noLogging: Boolean = false

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
    names = ["--capture-network"],
    description = [
      "Auto-capture network requests/responses to <session-dir>/network.ndjson on " +
        "supported devices (web today; mobile devices added as engines land). " +
        "Mirrors the desktop-app \"Capture Network Traffic\" toggle.",
    ],
  )
  var captureNetwork: Boolean = false

  @Option(
    names = ["--capture-all"],
    description = ["Enable all capture streams: video, logcat, network (local dev mode)"]
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
    // `--llm=none` is a sentinel for "no LLM configured" — recordings-only mode where the
    // tool stack runs without inference. Resolve it to (LLM_NONE, LLM_NONE) so downstream
    // code matches the same shape `trailblaze config llm none` writes (see CliConfigHelper).
    if (llm != null) {
      if (llmProvider != null || llmModel != null) {
        Console.error("Error: --llm is mutually exclusive with --llm-provider and --llm-model.")
        return CommandLine.ExitCode.USAGE
      }
      if (llm.equals(LLM_NONE, ignoreCase = true)) {
        llmProvider = LLM_NONE
        llmModel = LLM_NONE
      } else {
        val parts = llm!!.split("/", limit = 2)
        if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) {
          Console.error("Error: --llm must be in provider/model format (e.g., openai/gpt-4-1) or 'none' for recordings-only mode.")
          return CommandLine.ExitCode.USAGE
        }
        llmProvider = parts[0]
        llmModel = parts[1]
      }
    }

    // Resolve --device: flag takes priority, fall back to config default.
    if (device == null) {
      device = CliConfigHelper.readConfig()?.cliDevicePlatform
    }

    // Validate every trail file: must exist, be a regular file, and be a known trail name.
    // Directory arguments are not accepted — use your shell's glob to expand to a list
    // (e.g., `trailblaze trail flows/**/*.trail.yaml`) or pass files explicitly. The
    // recognized trail names are `*.trail.yaml` (platform-specific recordings) and
    // `blaze.yaml` (NL-only definitions).
    for (file in trailFiles) {
      if (!file.exists()) {
        Console.error("Error: Trail file does not exist: ${file.absolutePath}")
        return CommandLine.ExitCode.SOFTWARE
      }
      if (file.isDirectory) {
        Console.error("Error: '${file.absolutePath}' is a directory; pass trail files explicitly or via a shell glob (e.g., flows/**/*.trail.yaml).")
        return CommandLine.ExitCode.USAGE
      }
      if (!file.isFile) {
        Console.error("Error: Not a regular file: ${file.absolutePath}")
        return CommandLine.ExitCode.USAGE
      }
      if (!TrailRecordings.isTrailFile(file.name)) {
        Console.error("Error: Expected a trail file (${TrailRecordings.DOT_TRAIL_DOT_YAML_FILE_SUFFIX} or blaze.yaml), got: ${file.name}")
        return CommandLine.ExitCode.USAGE
      }
    }

    // Check if a daemon is already running — delegate to it to avoid starting a second
    // Gradle JVM. This is critical for CI where multiple trails run sequentially.
    // --no-daemon skips this check and always runs in-process.
    val daemonPort = parent.getEffectivePort()
    val daemon = DaemonClient(port = daemonPort)
    if (!noDaemon) {
      if (daemon.isRunningBlocking()) {
        return delegateToDaemon(daemon)
      }
    } else if (daemon.isRunningBlocking()) {
      // Shut down existing daemon so this process can bind the port.
      Console.info("Shutting down existing daemon on port $daemonPort...")
      daemon.shutdownBlocking()
    }

    // In-process run. Single-file and multi-file callers share the same per-file loop
    // so accounting, recording, and reporting are consistent at any N.
    Console.info("Running ${trailFiles.size} trail file(s)")
    Console.info(SECTION_DIVIDER)

    var passed = 0
    var failed = 0
    val allNewSessionIds = mutableListOf<SessionId>()

    // Initialize the app once for all files
    val app = parent.appProvider()

    // Apply port overrides from env vars / saved settings
    if (parent.hasPortOverride()) {
      app.applyPortOverrides(httpPort = parent.getEffectivePort(), httpsPort = parent.getEffectiveHttpsPort())
    }
    app.ensureServerRunning()

    // Register shutdown hook for Ctrl+C
    Runtime.getRuntime().addShutdownHook(Thread {
      cancelled = true
      Console.info("\n\nExecution stopped by user.")
    })

    for ((index, file) in trailFiles.withIndex()) {
      if (cancelled) break
      Console.info("\n[${index + 1}/${trailFiles.size}] Running: ${file.name}")
      Console.info(ITEM_DIVIDER)
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
    Console.info("\n" + SECTION_DIVIDER)
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

    // Generate markdown report if --markdown was specified
    if (markdown && allNewSessionIds.isNotEmpty()) {
      try {
        val logsRepo = app.deviceManager.logsRepo
        val reportGenerator = app.createCliReportGenerator()
        val markdownFile = reportGenerator.generateMarkdownReport(logsRepo, allNewSessionIds)
        if (markdownFile != null) {
          Console.info("Markdown: file://${markdownFile.absolutePath}")
        }
      } catch (e: Exception) {
        Console.error("Failed to generate markdown report: ${e.message}")
      }
    }

    exitProcess(if (failed > 0) CommandLine.ExitCode.SOFTWARE else CommandLine.ExitCode.OK)
  }

  /**
   * Delegates trail execution to a running daemon via HTTP, one file at a time.
   *
   * This avoids starting a second Gradle JVM — the daemon already has device
   * discovery, LLM config, and the trail runner ready to go.
   */
  private fun delegateToDaemon(daemon: DaemonClient): Int {
    Console.info("Delegating ${trailFiles.size} trail file(s) to running Trailblaze daemon...")
    Console.info(SECTION_DIVIDER)

    var passed = 0
    var failed = 0

    // Register Ctrl+C handler to cancel the in-flight daemon run
    Runtime.getRuntime().addShutdownHook(Thread {
      val runId = daemon.currentRunId
      if (runId != null) {
        Console.info("\nCancelling run...")
        daemon.cancelRunBlocking(runId)
      }
    })

    // Capture is handled by the daemon's DesktopYamlRunner — running two screenrecord/simctl
    // processes on the same device causes conflicts, so we skip capture in the CLI delegate path.
    for ((index, file) in trailFiles.withIndex()) {
      Console.info("\n[${index + 1}/${trailFiles.size}] Running: ${file.name}")
      Console.info(ITEM_DIVIDER)

      val rawYaml = file.readText()
      val yamlContent = TrailYamlTemplateResolver.resolve(rawYaml, file)
      val testName = deriveTestName(file)

      val request = CliRunRequest(
        yamlContent = yamlContent,
        trailFilePath = file.absolutePath,
        testName = testName,
        driverType = driverType,
        deviceId = device,
        llmProvider = llmProvider,
        llmModel = llmModel,
        useRecordedSteps = useRecordedSteps,
        // --show-browser is the legacy flag; --headless is the new spelling. Either
        // produces a visible browser when explicitly requested. Both are off by default.
        showBrowser = showBrowser || !headless,
        noLogging = noLogging,
        agentImplementation = agent.takeIf { it != AgentImplementation.DEFAULT.name },
        selfHeal = selfHeal,
        captureNetworkTraffic = captureNetwork || captureAll,
      )

      val response = daemon.runSync(request) { progress ->
        Console.info(progress)
      }

      if (response.success) {
        Console.info("✅ PASSED")
        passed++
        // Generate recording from session logs, then save to trail source directory.
        val sid = response.sessionId
        if (!noRecord && sid != null) {
          val sessionId = SessionId(sid)
          generateRecordingForSession(sessionId)
          saveRecordingToTrailDirectory(
            file, sessionId, response.deviceClassifiers,
          )
        }
      } else {
        Console.error("❌ FAILED: ${response.error ?: "Unknown error"}")
        failed++
      }
    }

    Console.info("\n" + SECTION_DIVIDER)
    Console.info("Results: $passed passed, $failed failed out of ${trailFiles.size} total")
    // Match the success marker emitted by the in-process path at the per-file `onComplete`
    // site, so `./trailblaze trail <file>` prints the same phrase regardless of whether it
    // runs in-process or via the daemon.
    if (failed == 0) {
      Console.info("\n✅ Trail completed successfully!")
    }

    // Flush output before exiting so error messages are visible in CI logs
    System.out.flush()
    System.err.flush()
    exitProcess(if (failed > 0) CommandLine.ExitCode.SOFTWARE else CommandLine.ExitCode.OK)
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
   * Builds the list of available devices for trail execution.
   *
   * Compose and web trails use virtual devices (no scanning needed).
   * Mobile trails scan for physically connected Android/iOS devices.
   * Returns null (after printing an error) if no devices are found.
   */
  private fun loadConnectedDevices(
    trailDriverType: TrailblazeDriverType?,
    trailPlatform: TrailblazeDevicePlatform?,
    app: TrailblazeDesktopApp,
    composePort: Int,
  ): List<TrailblazeConnectedDeviceSummary>? {
    if (trailDriverType == TrailblazeDriverType.COMPOSE) {
      Console.log("Detected compose trail — using Compose RPC driver (port $composePort)")
      return listOf(
        TrailblazeConnectedDeviceSummary(
          trailblazeDriverType = TrailblazeDriverType.COMPOSE,
          instanceId = "compose",
          description = "Compose (RPC)",
        ),
      )
    }
    if (trailPlatform == TrailblazeDevicePlatform.WEB) {
      Console.info("Detected web trail — using Playwright browser")
      return listOf(
        TrailblazeConnectedDeviceSummary(
          trailblazeDriverType = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
          instanceId = TrailblazeDeviceManager.PLAYWRIGHT_NATIVE_INSTANCE_ID,
          description = "Playwright Browser (Native)",
        ),
        TrailblazeConnectedDeviceSummary(
          trailblazeDriverType = TrailblazeDriverType.PLAYWRIGHT_ELECTRON,
          instanceId = TrailblazeDeviceManager.PLAYWRIGHT_ELECTRON_INSTANCE_ID,
          description = "Playwright Electron (CDP)",
        ),
      )
    }
    Console.info("Loading connected devices...")
    val scannedDevices = runBlocking {
      app.deviceManager.loadDevicesSuspend(applyDriverFilter = false)
    }
    if (scannedDevices.isEmpty()) {
      Console.error("Error: No devices connected.")
      Console.error("  Android: Connect via USB or start an emulator")
      Console.error("  iOS: Start an iOS simulator via Xcode")
      Console.error("  Web: Add 'driver: PLAYWRIGHT_NATIVE' to your trail config")
      return null
    }
    return scannedDevices
  }

  /**
   * Resolves which device to run the trail on.
   *
   * Matches the `--device` CLI spec (supports "platform/instance-id", "platform",
   * or raw "instance-id"), falls back to driver type, platform, or first available.
   * Returns null (after printing an error) if no matching device is found.
   */
  private fun resolveTargetDevice(
    allDevices: List<TrailblazeConnectedDeviceSummary>,
    trailDriverType: TrailblazeDriverType?,
    trailPlatform: TrailblazeDevicePlatform?,
    deviceSpec: String?,
  ): TrailblazeConnectedDeviceSummary? {
    if (deviceSpec != null) {
      val parts = deviceSpec.split("/", limit = 2)
      val specPlatform = TrailblazeDevicePlatform.fromString(parts[0])
      val specInstanceId = if (specPlatform != null) parts.getOrNull(1) else deviceSpec

      if (specInstanceId != null) {
        val candidates = if (specPlatform != null) {
          allDevices.filter { it.platform == specPlatform }
        } else {
          allDevices
        }
        return candidates.find { it.trailblazeDeviceId.instanceId == specInstanceId }
          ?: candidates.find { it.trailblazeDeviceId.instanceId.contains(specInstanceId) }
          ?: run {
            Console.error("Error: Device '${deviceSpec}' not found.")
            printAvailableDevices(allDevices)
            null
          }
      } else {
        val platformDevices = allDevices.filter { it.platform == specPlatform }
        return (platformDevices.find {
          it.trailblazeDriverType == TrailblazeDriverType.PLAYWRIGHT_NATIVE
        } ?: platformDevices.firstOrNull())?.also {
          Console.info("Auto-selected device for platform '${specPlatform}': ${it.trailblazeDeviceId.instanceId}")
        } ?: run {
          Console.error("Error: No device found for platform '${specPlatform}'.")
          printAvailableDevices(allDevices)
          null
        }
      }
    }
    if (trailDriverType != null) {
      return allDevices.find { it.trailblazeDriverType == trailDriverType }?.also {
        Console.info("Auto-selected device for driver '$trailDriverType': ${it.trailblazeDeviceId.instanceId}")
      } ?: run {
        Console.error("Error: Trail requires driver '$trailDriverType' but no matching device found.")
        printAvailableDevices(allDevices)
        null
      }
    }
    if (trailPlatform != null) {
      val platformDevices = allDevices.filter { it.platform == trailPlatform }
      return (platformDevices.find {
        it.trailblazeDriverType == TrailblazeDriverType.PLAYWRIGHT_NATIVE
      } ?: platformDevices.firstOrNull())?.also {
        Console.info("Auto-selected device for platform '$trailPlatform': ${it.trailblazeDeviceId.instanceId}")
      } ?: run {
        Console.error("Error: Trail requires platform '$trailPlatform' but no matching device found.")
        printAvailableDevices(allDevices)
        null
      }
    }
    return allDevices.first().also {
      Console.info("Using first available device: ${it.trailblazeDeviceId.instanceId}")
    }
  }

  private fun printAvailableDevices(devices: List<TrailblazeConnectedDeviceSummary>) {
    Console.error("Available devices:")
    devices.forEach { d ->
      Console.error("  - ${d.trailblazeDeviceId.instanceId} (${d.platform.displayName}, ${d.trailblazeDriverType})")
    }
  }

  /**
   * Resolves the LLM model from CLI flags or saved settings.
   * Returns null (after printing an error with provider status) if no model is available.
   */
  private fun resolveLlmModel(
    config: TrailblazeDesktopAppConfig,
    llmProvider: String?,
    llmModelId: String?,
  ): TrailblazeLlmModel? {
    if (llmProvider != null || llmModelId != null) {
      return config.resolveLlmModel(llmProvider, llmModelId)
        ?: run {
          Console.error("Error: Could not resolve LLM model for provider='$llmProvider', model='$llmModelId'.")
          Console.error("Ensure the provider has a configured API key and the model ID is valid.")
          null
        }
    }
    return try {
      config.getCurrentLlmModel()
    } catch (e: Exception) {
      Console.error("Error: No AI provider configured.")
      Console.error("")
      val tokenStatuses = config.getAllLlmTokenStatuses()
      if (tokenStatuses.isNotEmpty()) {
        Console.error("AI providers:")
        for ((provider, status) in tokenStatuses.entries.sortedBy { it.key.display }) {
          val (icon, text) = when (status) {
            is LlmTokenStatus.Available -> "+" to "Available"
            is LlmTokenStatus.Expired -> "!" to "Expired (may need refresh)"
            is LlmTokenStatus.NotAvailable -> {
              val envVar = LlmProviderEnvVarUtil.getEnvironmentVariableKeyForProvider(provider)
              "-" to "Not configured${if (envVar != null) " (set $envVar)" else ""}"
            }
          }
          Console.error("  [$icon] ${provider.display}: $text")
        }
        Console.error("")
      }
      Console.error("Set up a provider:    trailblaze config llm <provider/model>")
      Console.error("Or skip AI entirely:  trailblaze tool <name> (direct device control)")
      null
    }
  }

  /**
   * Runs a single .trail.yaml file and returns the exit code and any new session IDs created.
   */
  private fun runSingleTrailFile(
    file: File,
    app: TrailblazeDesktopApp,
  ): Pair<Int, List<SessionId>> {
    // Read the YAML file and resolve template variables (e.g., {{CWD}}, {{BASE_URL}})
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

    // Resolve driver type: CLI --driver flag > trail config driver field > app setting.
    val appSettingDriverType = trailConfig?.platform
      ?.let { TrailblazeDevicePlatform.fromString(it) }
      ?.let { platform ->
        app.deviceManager.settingsRepo.serverStateFlow.value
          .appConfig.selectedTrailblazeDriverTypes[platform]
      }
    val driverString = driverType ?: trailConfig?.driver ?: appSettingDriverType?.name
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

    val allDevices = loadConnectedDevices(trailDriverType, trailPlatform, app, composePort)
      ?: return CommandLine.ExitCode.SOFTWARE to emptyList()

    val targetDevice = resolveTargetDevice(allDevices, trailDriverType, trailPlatform, device)
      ?: return CommandLine.ExitCode.SOFTWARE to emptyList()

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

    val config = parent.configProvider()
    val llmModel = resolveLlmModel(config, llmProvider, llmModel)
      ?: return CommandLine.ExitCode.SOFTWARE to emptyList()

    Console.info("Using LLM: ${llmModel.trailblazeLlmProvider.id}/${llmModel.modelId}")
    Console.info("Agent: $agentImpl")

    val testName = deriveTestName(file)

    // Auto-detect recorded steps: if the trail YAML contains recordings, use them.
    val effectiveUseRecordedSteps = useRecordedSteps || try {
      val trailYaml = createTrailblazeYaml()
      val trailItems = trailYaml.decodeTrail(yamlContent)
      trailYaml.hasRecordedSteps(trailItems)
    } catch (_: Exception) {
      false
    }

    // Pin a session ID upfront so the post-completion status check can target THIS
    // trail's session rather than enumerating every "new" session in the logs repo.
    // See SessionId.pinnedFor — same construction is used in handleCliRunRequest.
    val pinnedSessionId = SessionId.pinnedFor(testName)

    // Create the run request
    val runYamlRequest = RunYamlRequest(
      testName = testName,
      yaml = yamlContent,
      trailFilePath = file.absolutePath,
      targetAppName = trailConfig?.target,
      useRecordedSteps = effectiveUseRecordedSteps,
      trailblazeDeviceId = targetDevice.trailblazeDeviceId,
      trailblazeLlmModel = llmModel,
      driverType = trailDriverType,
      config = TrailblazeConfig(
        // Either --show-browser or --no-headless (i.e. headless=false) makes the browser
        // visible. Both default to off (browser stays headless).
        browserHeadless = !showBrowser && headless,
        selfHeal = resolveEffectiveSelfHeal(),
        overrideSessionId = pinnedSessionId,
        captureNetworkTraffic = captureNetwork || captureAll,
      ),
      referrer = TrailblazeReferrer(id = "cli", display = "CLI"),
      agentImplementation = agentImpl,
    )

    return executeTrailAndCollectResults(app, runYamlRequest, trailConfig, config, file, pinnedSessionId)
  }

  /**
   * Executes a trail via the desktop YAML runner and collects results.
   *
   * Handles building [DesktopAppRunYamlParams], running the trail, waiting for completion,
   * cross-checking session status from logs, and generating recordings.
   *
   * @return exit code and list of new session IDs created during execution
   */
  private fun executeTrailAndCollectResults(
    app: TrailblazeDesktopApp,
    runYamlRequest: RunYamlRequest,
    trailConfig: TrailConfig?,
    config: TrailblazeDesktopAppConfig,
    file: File,
    pinnedSessionId: SessionId,
  ): Pair<Int, List<SessionId>> {
    // Latch to wait for completion
    val completionLatch = CountDownLatch(1)
    var exitCode = CommandLine.ExitCode.OK

    // Resolve target app: prefer trail config's `target` field, fall back to settings selection.
    // This ensures custom tools (e.g., myApp_launchSignedIn) are registered for the
    // correct app even when the desktop UI has a different app selected.
    val targetTestApp = trailConfig?.target?.let { config.availableAppTargets.findById(it) }
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
    Console.info(SECTION_DIVIDER)

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
    //
    // Only inspect THIS trail's pinned session — `newSessionIds` enumerates every session
    // that landed in the repo during this run, which includes sibling trails when several
    // run in parallel against the same daemon (the benchmark fan-out). Without the pin,
    // a sibling's TimeoutReached gets mis-attributed to this trail and we report 0/3 even
    // when two trails actually passed (observed during testing on haiku-4-5).
    if (exitCode == CommandLine.ExitCode.OK) {
      val status = logsRepo.getLogsForSession(pinnedSessionId).getSessionStatus()
      if (status is SessionStatus.Unknown) {
        Console.error("Session $pinnedSessionId has no status (no logs received)")
        exitCode = CommandLine.ExitCode.SOFTWARE
      } else if (status is SessionStatus.Ended && status !is SessionStatus.Ended.Succeeded &&
        status !is SessionStatus.Ended.SucceededWithSelfHeal) {
        Console.error("Session $pinnedSessionId ended with status: ${status::class.simpleName}")
        exitCode = CommandLine.ExitCode.SOFTWARE
      } else if (status !is SessionStatus.Ended) {
        Console.error("Session $pinnedSessionId did not complete (status: ${status::class.simpleName})")
        exitCode = CommandLine.ExitCode.SOFTWARE
      }
    }

    // Generate recording YAML from session logs so saveRecordingToTrailDirectory can find it.
    // For host-mode runs, TrailblazeHostYamlRunner already generates this file. For on-device
    // instrumentation runs, the recording is not generated during execution, so we generate
    // it here from the session logs. Same parallel-safety reasoning as the status check
    // above — only generate for THIS trail's pinned session, not sibling sessions that
    // happen to be in the repo.
    if (!noRecord) {
      generateRecordingForSession(pinnedSessionId)
    }

    return exitCode to listOf(pinnedSessionId)
  }

  /**
   * Generates a `recording.trail.yaml` file in the session's log directory from
   * the session logs, if one doesn't already exist.
   *
   * Reads logs directly from disk rather than from the cached flow, because for
   * on-device instrumentation runs the flow cache may not have all logs yet.
   */
  private fun generateRecordingForSession(sessionId: SessionId) {
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
      val maxWaitMs = RECORDING_LOG_STABILITY_MAX_WAIT_MS
      val pollMs = RECORDING_LOG_STABILITY_POLL_MS
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
        TrailConfig(
          id = originalConfig?.id,
          title = originalConfig?.title,
          description = originalConfig?.description,
          priority = originalConfig?.priority,
          context = originalConfig?.context,
          source = originalConfig?.source,
          metadata = originalConfig?.metadata,
          target = originalConfig?.target,
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
          TrailblazeToolSetCatalog.resolveForDriver(
            driverType, WebToolSetIds.ALL,
          ).toolClasses
        TrailblazeDriverType.PLAYWRIGHT_ELECTRON ->
          TrailblazeToolSetCatalog.resolveForDriver(
            driverType, WebToolSetIds.ALL,
          ).toolClasses + BasePlaywrightElectronTest.ELECTRON_BUILT_IN_TOOL_CLASSES
        TrailblazeDriverType.COMPOSE ->
          TrailblazeToolSetCatalog.resolveForDriver(
            driverType, ComposeToolSetIds.ALL,
          ).toolClasses
        TrailblazeDriverType.REVYL_ANDROID,
        TrailblazeDriverType.REVYL_IOS ->
          TrailblazeToolSetCatalog.resolveForDriver(
            driverType, RevylToolSetIds.ALL,
          ).toolClasses
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

  /**
   * Resolves the effective `self-heal` setting for this run, honoring the usual precedence:
   *   1. `--self-heal` CLI flag (explicit per-run intent).
   *   2. `TRAILBLAZE_SELF_HEAL_ENABLED` env var (CI / pipeline intent — a CI pipeline runner
   *      may set this on runner steps when the pipeline config opts in).
   *   3. Persisted `trailblaze config self-heal` setting (user's local default).
   *   4. Opt-in default (off).
   *
   * Companion resolver for JUnit runs: [xyz.block.trailblaze.host.rules.BaseHostTrailblazeTest]`
   * .resolveSelfHealFromEnvOrConfig()`, which is a 2-tier (env → config) subset — tests have no
   * CLI flag to honor.
   */
  internal fun resolveEffectiveSelfHeal(): Boolean =
    selfHeal
      ?: System.getenv("TRAILBLAZE_SELF_HEAL_ENABLED")?.lowercase()?.toBooleanStrictOrNull()
      ?: CliConfigHelper.readConfig()?.selfHealEnabled
      ?: false

  companion object {
    /**
     * Derives a meaningful test name from a trail file.
     *
     * For the standard subdirectory layout (e.g., `test-counter/trail.yaml` or
     * `test-counter/blaze.yaml`), uses the parent directory name. Otherwise falls
     * back to the filename without extension.
     */
    fun deriveTestName(file: File): String {
      val baseName = file.nameWithoutExtension
      return if (baseName == "trail" || baseName == "recording.trail" || baseName == "blaze") {
        file.absoluteFile.parentFile?.name ?: baseName
      } else {
        baseName
      }
    }
  }
}
