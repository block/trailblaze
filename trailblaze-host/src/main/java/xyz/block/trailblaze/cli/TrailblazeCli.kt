package xyz.block.trailblaze.cli

import kotlinx.coroutines.runBlocking
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import xyz.block.trailblaze.desktop.LlmTokenStatus
import xyz.block.trailblaze.desktop.TrailblazeDesktopAppConfig
import xyz.block.trailblaze.devices.TrailblazeDevicePort
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.llm.TrailblazeReferrer
import xyz.block.trailblaze.model.DesktopAppRunYamlParams
import xyz.block.trailblaze.model.DeviceConnectionStatus
import xyz.block.trailblaze.model.TrailblazeConfig
import xyz.block.trailblaze.model.TrailExecutionResult
import xyz.block.trailblaze.ui.TrailblazeDesktopApp
import xyz.block.trailblaze.util.Console
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
 *   trailblaze --headless          - Start headless MCP server  
 *   trailblaze run <file>          - Run a .trail.yaml file
 *   trailblaze mcp                 - Start MCP server (Streamable HTTP)
 *   trailblaze mcp --stdio         - Start MCP server (STDIO transport)
 *   trailblaze list-devices        - List connected devices
 *   trailblaze -p 52526            - Launch on a custom port (allows multiple instances)
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
  fun run(
    args: Array<String>,
    appProvider: () -> TrailblazeDesktopApp,
    configProvider: () -> TrailblazeDesktopAppConfig,
  ) {
    // Install Java-level System.out/err log capture as early as possible so
    // println() output is saved regardless of how Trailblaze was launched
    // (IDE, JAR, shell script, etc.).
    //
    // Skip for STDIO MCP mode: stdout must be a pristine JSON-RPC stream.
    // The DesktopLogFileWriter tee would wrap stdout and leak non-JSON output
    // (its own "Logging to ..." message) before Console.useStdErr() runs.
    val isStdioMode = args.contains("mcp") && args.contains("--stdio")
    if (!isStdioMode) {
      val httpPort = resolvePortFromArgs(args)
      DesktopLogFileWriter.install(httpPort = httpPort)
    }

    val cli = TrailblazeCliCommand(appProvider, configProvider)
    val exitCode = CommandLine(cli)
      .setCaseInsensitiveEnumValuesAllowed(true)
      .execute(*args)
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
    CliConfigHelper.readConfig()?.let { config ->
      if (config.serverPort != TrailblazeDevicePort.TRAILBLAZE_DEFAULT_HTTP_PORT) {
        return config.serverPort
      }
    }
    System.getenv("TRAILBLAZE_PORT")?.toIntOrNull()?.let { return it }
    return TrailblazeDevicePort.TRAILBLAZE_DEFAULT_HTTP_PORT
  }
}

/**
 * Main Trailblaze CLI command.
 */
@Command(
  name = "trailblaze",
  mixinStandardHelpOptions = true,
  version = ["Trailblaze 1.0"],
  description = ["Trailblaze - AI-powered mobile UI automation"],
  subcommands = [
    RunCommand::class,
    McpCommand::class,
    ListDevicesCommand::class,
    ConfigCommand::class,
    StatusCommand::class,
    StopCommand::class,
    AuthCommand::class,
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
    // 2. Saved settings (per-install override set in Settings UI)
    val savedConfig = CliConfigHelper.readConfig()
    if (savedConfig != null && savedConfig.serverPort != TrailblazeDevicePort.TRAILBLAZE_DEFAULT_HTTP_PORT) {
      return savedConfig.serverPort
    }
    // 3. Environment variable (machine-wide default)
    System.getenv("TRAILBLAZE_PORT")?.toIntOrNull()?.let { return it }
    // 4. Default
    return TrailblazeDevicePort.TRAILBLAZE_DEFAULT_HTTP_PORT
  }

  /**
   * Returns the effective HTTPS port.
   * 
   * Precedence: CLI flag → saved settings (per-install) → TRAILBLAZE_HTTPS_PORT env var → default (8443)
   */
  fun getEffectiveHttpsPort(): Int {
    // 1. CLI flag (highest priority — one-off override)
    httpsPort?.let { return it }
    // 2. Saved settings (per-install override set in Settings UI)
    val savedConfig = CliConfigHelper.readConfig()
    if (savedConfig != null && savedConfig.serverHttpsPort != TrailblazeDevicePort.TRAILBLAZE_DEFAULT_HTTPS_PORT) {
      return savedConfig.serverHttpsPort
    }
    // 3. Environment variable (machine-wide default)
    System.getenv("TRAILBLAZE_HTTPS_PORT")?.toIntOrNull()?.let { return it }
    // 4. Default
    return TrailblazeDevicePort.TRAILBLAZE_DEFAULT_HTTPS_PORT
  }

  /**
   * Whether any port override is active (from CLI flag, env var, or saved settings).
   */
  fun hasPortOverride(): Boolean {
    return getEffectivePort() != TrailblazeDevicePort.TRAILBLAZE_DEFAULT_HTTP_PORT ||
      getEffectiveHttpsPort() != TrailblazeDevicePort.TRAILBLAZE_DEFAULT_HTTPS_PORT
  }

  override fun call(): Int {
    // Check if Trailblaze is already running
    val daemon = DaemonClient(port = getEffectivePort())
    if (daemon.isRunning()) {
      Console.log("Trailblaze is already running. Showing window...")
      val response = daemon.showWindow()
      if (response.success) {
        Console.log("Window shown.")
      } else {
        Console.log("Could not show window: ${response.message}")
        Console.log("Use the menu bar icon to show the window, or run 'trailblaze stop' to stop it.")
      }
      return CommandLine.ExitCode.OK
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
 * Run a .trail.yaml file on a connected device.
 */
@Command(
  name = "run",
  mixinStandardHelpOptions = true,
  description = ["Run a .trail.yaml file on a connected device"]
)
class RunCommand : Callable<Int> {

  @CommandLine.ParentCommand
  private lateinit var parent: TrailblazeCliCommand

  @Parameters(
    index = "0",
    description = ["Path to the .trail.yaml file to execute"]
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
    names = ["--host"],
    description = ["Force host-based execution (run agent on this machine, not on device). Required for V3 agent testing."]
  )
  var forceHost: Boolean = false

  override fun call(): Int {
    // Validate trail file exists
    if (!trailFile.exists()) {
      Console.error("Error: Trail file does not exist: ${trailFile.absolutePath}")
      return CommandLine.ExitCode.SOFTWARE
    }

    if (!trailFile.name.endsWith(".trail.yaml") && !trailFile.name.endsWith(".yaml")) {
      Console.error("Warning: File does not have .trail.yaml extension: ${trailFile.name}")
    }

    // Read the YAML file
    val yamlContent = trailFile.readText()
    if (verbose) {
      Console.log("Reading trail file: ${trailFile.absolutePath}")
      Console.log("YAML content (${yamlContent.length} bytes)")
    }

    // Initialize the app (uses parent's provider)
    val app = parent.appProvider()

    // Apply port overrides if any non-default ports are active
    if (parent.hasPortOverride()) {
      app.applyPortOverrides(httpPort = parent.getEffectivePort(), httpsPort = parent.getEffectiveHttpsPort())
    }

    // Ensure the server is running (needed for OAuth callbacks and logging)
    app.ensureServerRunning()

    // Load devices
    Console.log("Loading connected devices...")
    val allDevices = runBlocking {
      app.deviceManager.loadDevicesSuspend()
    }

    if (allDevices.isEmpty()) {
      Console.error("Error: No devices connected. Please connect an Android device/emulator or iOS simulator.")
      return CommandLine.ExitCode.SOFTWARE
    }

    // Filter devices based on --host flag
    val devices = if (forceHost) {
      allDevices.filter { it.trailblazeDriverType.isHost }.also {
        if (it.isEmpty()) {
          Console.error("Error: No host-mode devices available.")
          Console.error("Available devices (all use on-device drivers):")
          allDevices.forEach { device ->
            Console.error("  - ${device.trailblazeDeviceId.instanceId} (${device.platform.displayName}, ${device.trailblazeDriverType})")
          }
          return CommandLine.ExitCode.SOFTWARE
        }
        Console.log("Filtering for host-mode devices (--host flag)")
      }
    } else {
      allDevices
    }

    // Find the target device
    val targetDevice = if (deviceId != null) {
      devices.find { it.trailblazeDeviceId.instanceId == deviceId }
        ?: devices.find { it.trailblazeDeviceId.instanceId.contains(deviceId!!) }
        ?: run {
          Console.error("Error: Device '$deviceId' not found.")
          Console.error("Available devices:")
          devices.forEach { device ->
            Console.error("  - ${device.trailblazeDeviceId.instanceId} (${device.platform.displayName}, ${device.trailblazeDriverType})")
          }
          return CommandLine.ExitCode.SOFTWARE
        }
    } else {
      devices.first().also {
        Console.log("Using first available device: ${it.trailblazeDeviceId.instanceId}")
      }
    }

    Console.log("Target device: ${targetDevice.trailblazeDeviceId.instanceId} (${targetDevice.platform.displayName})")
    Console.log("Driver: ${targetDevice.trailblazeDriverType}")

    // Get LLM model from settings
    val config = parent.configProvider()
    val llmModel = try {
      config.getCurrentLlmModel()
    } catch (e: Exception) {
      Console.error("Error: No LLM configured. Please set an API key environment variable.")
      Console.error("Supported: ANTHROPIC_API_KEY, OPENAI_API_KEY, GOOGLE_API_KEY")
      return CommandLine.ExitCode.SOFTWARE
    }

    Console.log("Using LLM: ${llmModel.trailblazeLlmProvider.id}/${llmModel.modelId}")

    // Create the run request
    val runYamlRequest = RunYamlRequest(
      testName = trailFile.nameWithoutExtension,
      yaml = yamlContent,
      trailFilePath = trailFile.absolutePath,
      targetAppName = null,
      useRecordedSteps = useRecordedSteps,
      trailblazeDeviceId = targetDevice.trailblazeDeviceId,
      trailblazeLlmModel = llmModel,
      config = TrailblazeConfig(
        setOfMarkEnabled = setOfMark,
      ),
      referrer = TrailblazeReferrer(id = "cli", display = "CLI"),
    )

    // Latch to wait for completion
    val completionLatch = CountDownLatch(1)
    var exitCode = CommandLine.ExitCode.OK

    // Use the currently selected target app from settings so that custom tools
    // (e.g., square_launchAppSignedIn) are available during YAML deserialization & execution.
    val targetTestApp = app.deviceManager.getCurrentSelectedTargetApp()
    if (verbose) {
      Console.log("Target app: ${targetTestApp?.displayName ?: "None (using built-in tools only)"}")
    }

    val params = DesktopAppRunYamlParams(
      forceStopTargetApp = false,
      runYamlRequest = runYamlRequest,
      targetTestApp = targetTestApp,
      onProgressMessage = { message ->
        Console.log(message)
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
      onComplete = { result ->
        when (result) {
          is TrailExecutionResult.Success -> {
            Console.log("\n✅ Trail completed successfully!")
            exitCode = CommandLine.ExitCode.OK
          }
          is TrailExecutionResult.Failed -> {
            Console.error("\n❌ Trail failed: ${result.errorMessage ?: "Unknown error"}")
            exitCode = CommandLine.ExitCode.SOFTWARE
          }
          is TrailExecutionResult.Cancelled -> {
            Console.log("\n⚠️ Trail was cancelled")
            exitCode = CommandLine.ExitCode.SOFTWARE
          }
        }
        completionLatch.countDown()
      },
    )

    Console.log("\nStarting trail execution...")
    Console.log("=".repeat(60))

    // Register shutdown hook for Ctrl+C
    Runtime.getRuntime().addShutdownHook(Thread {
      Console.log("\n\nExecution stopped by user.")
      completionLatch.countDown()
    })

    // Run the trail
    app.desktopYamlRunner.runYaml(params)

    // Wait for completion
    try {
      completionLatch.await()
    } catch (e: InterruptedException) {
      Console.log("Execution interrupted")
      exitCode = CommandLine.ExitCode.SOFTWARE
    }

    // Force exit to ensure all background threads are terminated
    exitProcess(exitCode)
  }
}

/**
 * Start the MCP server with a specified transport.
 *
 * By default, starts a Streamable HTTP server on the configured port.
 * Use `--stdio` to start with STDIO transport for MCP client integrations
 * (e.g., Claude Desktop, Firebender, Goose).
 *
 * Examples:
 *   trailblaze mcp                  - Start HTTP MCP server on default port
 *   trailblaze mcp --stdio          - Start STDIO MCP server (stdin/stdout)
 *   trailblaze mcp --port 8080      - Start HTTP MCP server on port 8080
 */
@Command(
  name = "mcp",
  mixinStandardHelpOptions = true,
  description = ["Start the MCP server"]
)
class McpCommand : Callable<Int> {

  @CommandLine.ParentCommand
  private lateinit var parent: TrailblazeCliCommand

  @Option(
    names = ["--stdio"],
    description = ["Use STDIO transport (stdin/stdout) instead of HTTP. Required for MCP client integrations."]
  )
  var stdio: Boolean = false

  override fun call(): Int {
    // MCP is always headless — no GUI needed
    System.setProperty("java.awt.headless", "true")

    if (stdio) {
      // Capture the current stdout BEFORE redirecting — DesktopLogFileWriter may have
      // already wrapped it with a tee (JSON-RPC goes to both stdout and log file).
      // After Console.useStdErr(), System.out is redirected to stderr, so we must
      // save the reference now for the STDIO transport.
      val stdoutForTransport = System.out

      // Redirect all console output to stderr BEFORE app initialization so that
      // settings loading, device scanning, and other startup output doesn't
      // contaminate stdout (which must be a clean JSON-RPC stream).
      Console.useStdErr()
      Console.log("Trailblaze MCP server starting with STDIO transport...")

      val app = parent.appProvider()
      runBlocking {
        app.trailblazeMcpServer.startStdioMcpServer(stdoutForTransport)
      }
    } else {
      val app = parent.appProvider()
      val port = parent.getEffectivePort()
      val httpsPort = parent.getEffectiveHttpsPort()
      Console.log("Trailblaze MCP server starting with HTTP transport on port $port...")

      // Apply port overrides so the server knows the effective ports
      if (parent.hasPortOverride()) {
        app.applyPortOverrides(httpPort = port, httpsPort = httpsPort)
      }

      app.trailblazeMcpServer.startStreamableHttpMcpServer(
        port = port,
        httpsPort = httpsPort,
        wait = true,
      )
    }

    return CommandLine.ExitCode.OK
  }
}

/**
 * List all connected devices.
 */
@Command(
  name = "list-devices",
  mixinStandardHelpOptions = true,
  description = ["List all connected devices"]
)
class ListDevicesCommand : Callable<Int> {

  @CommandLine.ParentCommand
  private lateinit var parent: TrailblazeCliCommand

  override fun call(): Int {
    val app = parent.appProvider()
    
    Console.log("Scanning for connected devices...")
    val devices = runBlocking {
      app.deviceManager.loadDevicesSuspend()
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
 *   trailblaze config                           - Show current config
 *   trailblaze config --android-driver HOST     - Set Android to host mode
 *   trailblaze config --llm-provider openai     - Set LLM provider
 *   trailblaze config --agent TWO_TIER_AGENT    - Set agent implementation
 */
@Command(
  name = "config",
  mixinStandardHelpOptions = true,
  description = ["View and modify Trailblaze configuration"]
)
class ConfigCommand : Callable<Int> {

  @Option(
    names = ["--android-driver"],
    description = ["Android driver: HOST or ONDEVICE (instrumentation)"]
  )
  var androidDriver: String? = null

  @Option(
    names = ["--ios-driver"],
    description = ["iOS driver: HOST (only option for iOS)"]
  )
  var iosDriver: String? = null

  @Option(
    names = ["--llm-provider"],
    description = ["LLM provider: openai, anthropic, google, ollama, openrouter, databricks, etc."]
  )
  var llmProvider: String? = null

  @Option(
    names = ["--llm-model"],
    description = ["LLM model ID (e.g., gpt-4-1, claude-sonnet-4-20250514, goose-gpt-4-1)"]
  )
  var llmModel: String? = null

  @Option(
    names = ["--set-of-mark"],
    description = ["Enable/disable Set of Mark mode"],
    negatable = true
  )
  var setOfMark: Boolean? = null

  @Option(
    names = ["--ai-fallback"],
    description = ["Enable/disable AI fallback when recorded steps fail"],
    negatable = true
  )
  var aiFallback: Boolean? = null

  override fun call(): Int {
    // Use lightweight config helper - no full app initialization needed
    var currentConfig = CliConfigHelper.getOrCreateConfig()

    // Check if any settings are being changed
    val hasChanges = androidDriver != null || iosDriver != null || 
                     llmProvider != null || llmModel != null || 
                     setOfMark != null || aiFallback != null

    if (hasChanges) {
      // Update Android driver
      androidDriver?.let { driver ->
        val driverType = CliConfigHelper.parseAndroidDriver(driver)
        if (driverType == null) {
          Console.error("Invalid Android driver: $driver")
          Console.error("Valid options: HOST, ONDEVICE")
          return CommandLine.ExitCode.SOFTWARE
        }
        currentConfig = currentConfig.copy(
          selectedTrailblazeDriverTypes = currentConfig.selectedTrailblazeDriverTypes + 
            (TrailblazeDevicePlatform.ANDROID to driverType)
        )
        Console.log("Set Android driver: $driverType")
      }

      // Update iOS driver
      iosDriver?.let { driver ->
        val driverType = CliConfigHelper.parseIosDriver(driver)
        if (driverType == null) {
          Console.error("Invalid iOS driver: $driver")
          Console.error("Valid options: HOST")
          return CommandLine.ExitCode.SOFTWARE
        }
        currentConfig = currentConfig.copy(
          selectedTrailblazeDriverTypes = currentConfig.selectedTrailblazeDriverTypes + 
            (TrailblazeDevicePlatform.IOS to driverType)
        )
        Console.log("Set iOS driver: $driverType")
      }

      // Update LLM provider
      llmProvider?.let { provider ->
        currentConfig = currentConfig.copy(llmProvider = provider.lowercase())
        Console.log("Set LLM provider: ${provider.lowercase()}")
      }

      // Update LLM model
      llmModel?.let { model ->
        currentConfig = currentConfig.copy(llmModel = model)
        Console.log("Set LLM model: $model")
      }

      // Update Set of Mark
      setOfMark?.let { enabled ->
        currentConfig = currentConfig.copy(setOfMarkEnabled = enabled)
        Console.log("Set Set of Mark: $enabled")
      }

      // Update AI fallback
      aiFallback?.let { enabled ->
        currentConfig = currentConfig.copy(aiFallbackEnabled = enabled)
        Console.log("Set AI fallback: $enabled")
      }

      // Save the updated config
      CliConfigHelper.writeConfig(currentConfig)
      Console.log("\nConfiguration saved.")
    }

    // Show current configuration
    Console.log("")
    Console.log("Current Configuration:")
    Console.log("-".repeat(60))
    Console.log("  Android driver:  ${currentConfig.selectedTrailblazeDriverTypes[TrailblazeDevicePlatform.ANDROID] ?: "not set"}")
    Console.log("  iOS driver:      ${currentConfig.selectedTrailblazeDriverTypes[TrailblazeDevicePlatform.IOS] ?: "not set"}")
    Console.log("  LLM provider:    ${currentConfig.llmProvider}")
    Console.log("  LLM model:       ${currentConfig.llmModel}")
    Console.log("  Set of Mark:     ${currentConfig.setOfMarkEnabled}")
    Console.log("  AI fallback:     ${currentConfig.aiFallbackEnabled}")
    Console.log("")
    Console.log("Settings file: ${CliConfigHelper.getSettingsFile().absolutePath}")

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

/**
 * Check and display LLM authentication status.
 * 
 * This command shows the token status for all configured LLM providers.
 * 
 * Examples:
 *   trailblaze auth                  - Show token status for all providers
 */
@Command(
  name = "auth",
  mixinStandardHelpOptions = true,
  description = ["Check and display LLM authentication status"]
)
class AuthCommand : Callable<Int> {

  @CommandLine.ParentCommand
  private lateinit var parent: TrailblazeCliCommand

  override fun call(): Int {
    val config = parent.configProvider()
    val tokenStatuses = config.getAllLlmTokenStatuses()
    
    if (tokenStatuses.isEmpty()) {
      Console.log("No LLM providers configured.")
      exitProcess(CommandLine.ExitCode.OK)
    }

    Console.log("")
    Console.log("LLM Provider Authentication Status:")
    Console.log("-".repeat(60))
    
    tokenStatuses.forEach { (provider, status) ->
      val statusIcon = when (status) {
        is LlmTokenStatus.Available -> "✅"
        is LlmTokenStatus.Expired -> "⚠️"
        is LlmTokenStatus.NotAvailable -> "❌"
      }
      val statusText = when (status) {
        is LlmTokenStatus.Available -> "Available"
        is LlmTokenStatus.Expired -> "Expired (may need refresh)"
        is LlmTokenStatus.NotAvailable -> "Not configured"
      }
      Console.log("  $statusIcon ${provider.display}: $statusText")
    }
    
    Console.log("")
    
    // Provide helpful hints for unconfigured providers
    val missingProviders = tokenStatuses.filter { it.value is LlmTokenStatus.NotAvailable }
    if (missingProviders.isNotEmpty()) {
      Console.log("To configure missing providers, set the appropriate environment variables:")
      missingProviders.forEach { (provider, _) ->
        val envVar = config.getEnvironmentVariableForProvider(provider)
        if (envVar != null) {
          Console.log("  ${provider.display}: Set $envVar")
        }
      }
      Console.log("")
    }
    
    // Force exit to terminate any background services
    exitProcess(CommandLine.ExitCode.OK)
  }
}

// Extension property to access appProvider from RunCommand/ListDevicesCommand
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
