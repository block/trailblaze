package xyz.block.trailblaze.cli

import kotlinx.coroutines.runBlocking
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import xyz.block.trailblaze.desktop.LlmTokenStatus
import xyz.block.trailblaze.desktop.TrailblazeDesktopAppConfig
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.llm.TrailblazeReferrer
import xyz.block.trailblaze.model.DesktopAppRunYamlParams
import xyz.block.trailblaze.model.DeviceConnectionStatus
import xyz.block.trailblaze.model.TrailblazeConfig
import xyz.block.trailblaze.model.TrailExecutionResult
import xyz.block.trailblaze.ui.TrailblazeDesktopApp
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
 *   trailblaze list-devices        - List connected devices
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
    val cli = TrailblazeCliCommand(appProvider, configProvider)
    val exitCode = CommandLine(cli)
      .setCaseInsensitiveEnumValuesAllowed(true)
      .execute(*args)
    if (exitCode != 0) {
      exitProcess(exitCode)
    }
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

  override fun call(): Int {
    // Check if Trailblaze is already running
    val daemon = DaemonClient()
    if (daemon.isRunning()) {
      println("Trailblaze is already running. Showing window...")
      val response = daemon.showWindow()
      if (response.success) {
        println("Window shown.")
      } else {
        println("Could not show window: ${response.message}")
        println("Use the menu bar icon to show the window, or run 'trailblaze stop' to stop it.")
      }
      return CommandLine.ExitCode.OK
    }
    
    // Start the app (GUI or headless based on flag)
    appProvider().startTrailblazeDesktopApp(headless = headless)
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
      System.err.println("Error: Trail file does not exist: ${trailFile.absolutePath}")
      return CommandLine.ExitCode.SOFTWARE
    }

    if (!trailFile.name.endsWith(".trail.yaml") && !trailFile.name.endsWith(".yaml")) {
      System.err.println("Warning: File does not have .trail.yaml extension: ${trailFile.name}")
    }

    // Read the YAML file
    val yamlContent = trailFile.readText()
    if (verbose) {
      println("Reading trail file: ${trailFile.absolutePath}")
      println("YAML content (${yamlContent.length} bytes)")
    }

    // Initialize the app (uses parent's provider)
    val app = parent.appProvider()

    // Ensure the server is running (needed for OAuth callbacks and logging)
    app.ensureServerRunning()

    // Load devices
    println("Loading connected devices...")
    val allDevices = runBlocking {
      app.deviceManager.loadDevicesSuspend()
    }

    if (allDevices.isEmpty()) {
      System.err.println("Error: No devices connected. Please connect an Android device/emulator or iOS simulator.")
      return CommandLine.ExitCode.SOFTWARE
    }

    // Filter devices based on --host flag
    val devices = if (forceHost) {
      allDevices.filter { it.trailblazeDriverType.isHost }.also {
        if (it.isEmpty()) {
          System.err.println("Error: No host-mode devices available.")
          System.err.println("Available devices (all use on-device drivers):")
          allDevices.forEach { device ->
            System.err.println("  - ${device.trailblazeDeviceId.instanceId} (${device.platform.displayName}, ${device.trailblazeDriverType})")
          }
          return CommandLine.ExitCode.SOFTWARE
        }
        println("Filtering for host-mode devices (--host flag)")
      }
    } else {
      allDevices
    }

    // Find the target device
    val targetDevice = if (deviceId != null) {
      devices.find { it.trailblazeDeviceId.instanceId == deviceId }
        ?: devices.find { it.trailblazeDeviceId.instanceId.contains(deviceId!!) }
        ?: run {
          System.err.println("Error: Device '$deviceId' not found.")
          System.err.println("Available devices:")
          devices.forEach { device ->
            System.err.println("  - ${device.trailblazeDeviceId.instanceId} (${device.platform.displayName}, ${device.trailblazeDriverType})")
          }
          return CommandLine.ExitCode.SOFTWARE
        }
    } else {
      devices.first().also {
        println("Using first available device: ${it.trailblazeDeviceId.instanceId}")
      }
    }

    println("Target device: ${targetDevice.trailblazeDeviceId.instanceId} (${targetDevice.platform.displayName})")
    println("Driver: ${targetDevice.trailblazeDriverType}")

    // Get LLM model from settings
    val config = parent.configProvider()
    val llmModel = try {
      config.getCurrentLlmModel()
    } catch (e: Exception) {
      System.err.println("Error: No LLM configured. Please set an API key environment variable.")
      System.err.println("Supported: ANTHROPIC_API_KEY, OPENAI_API_KEY, GOOGLE_API_KEY")
      return CommandLine.ExitCode.SOFTWARE
    }

    println("Using LLM: ${llmModel.trailblazeLlmProvider.id}/${llmModel.modelId}")

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
      println("Target app: ${targetTestApp?.displayName ?: "None (using built-in tools only)"}")
    }

    val params = DesktopAppRunYamlParams(
      forceStopTargetApp = false,
      runYamlRequest = runYamlRequest,
      targetTestApp = targetTestApp,
      onProgressMessage = { message ->
        println(message)
      },
      onConnectionStatus = { status ->
        when (status) {
          is DeviceConnectionStatus.DeviceConnectionError.ConnectionFailure -> {
            System.err.println("Connection failed: ${status.errorMessage}")
            exitCode = CommandLine.ExitCode.SOFTWARE
            completionLatch.countDown()
          }
          is DeviceConnectionStatus.WithTargetDevice.TrailblazeInstrumentationRunning -> {
            if (verbose) {
              println("Instrumentation running on device: ${status.trailblazeDeviceId.instanceId}")
            }
          }
          else -> {
            if (verbose) {
              println("Connection status: $status")
            }
          }
        }
      },
      additionalInstrumentationArgs = emptyMap(),
      onComplete = { result ->
        when (result) {
          is TrailExecutionResult.Success -> {
            println("\n✅ Trail completed successfully!")
            exitCode = CommandLine.ExitCode.OK
          }
          is TrailExecutionResult.Failed -> {
            println("\n❌ Trail failed: ${result.errorMessage ?: "Unknown error"}")
            exitCode = CommandLine.ExitCode.SOFTWARE
          }
          is TrailExecutionResult.Cancelled -> {
            println("\n⚠️ Trail was cancelled")
            exitCode = CommandLine.ExitCode.SOFTWARE
          }
        }
        completionLatch.countDown()
      },
    )

    println("\nStarting trail execution...")
    println("=".repeat(60))

    // Register shutdown hook for Ctrl+C
    Runtime.getRuntime().addShutdownHook(Thread {
      println("\n\nExecution stopped by user.")
      completionLatch.countDown()
    })

    // Run the trail
    app.desktopYamlRunner.runYaml(params)

    // Wait for completion
    try {
      completionLatch.await()
    } catch (e: InterruptedException) {
      println("Execution interrupted")
      exitCode = CommandLine.ExitCode.SOFTWARE
    }

    // Force exit to ensure all background threads are terminated
    exitProcess(exitCode)
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
    
    println("Scanning for connected devices...")
    val devices = runBlocking {
      app.deviceManager.loadDevicesSuspend()
    }

    if (devices.isEmpty()) {
      println("No devices found.")
      println()
      println("To connect devices:")
      println("  Android: Connect via USB or start an emulator")
      println("  iOS: Start an iOS simulator via Xcode")
      exitProcess(CommandLine.ExitCode.OK)
    }

    println()
    println("Connected Devices:")
    println("-".repeat(80))
    devices.forEach { device ->
      println("  ID:       ${device.trailblazeDeviceId.instanceId}")
      println("  Platform: ${device.platform.displayName}")
      println("  Driver:   ${device.trailblazeDriverType}")
      println()
    }
    println("Total: ${devices.size} device(s)")
    
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
          System.err.println("Invalid Android driver: $driver")
          System.err.println("Valid options: HOST, ONDEVICE")
          return CommandLine.ExitCode.SOFTWARE
        }
        currentConfig = currentConfig.copy(
          selectedTrailblazeDriverTypes = currentConfig.selectedTrailblazeDriverTypes + 
            (TrailblazeDevicePlatform.ANDROID to driverType)
        )
        println("Set Android driver: $driverType")
      }

      // Update iOS driver
      iosDriver?.let { driver ->
        val driverType = CliConfigHelper.parseIosDriver(driver)
        if (driverType == null) {
          System.err.println("Invalid iOS driver: $driver")
          System.err.println("Valid options: HOST")
          return CommandLine.ExitCode.SOFTWARE
        }
        currentConfig = currentConfig.copy(
          selectedTrailblazeDriverTypes = currentConfig.selectedTrailblazeDriverTypes + 
            (TrailblazeDevicePlatform.IOS to driverType)
        )
        println("Set iOS driver: $driverType")
      }

      // Update LLM provider
      llmProvider?.let { provider ->
        currentConfig = currentConfig.copy(llmProvider = provider.lowercase())
        println("Set LLM provider: ${provider.lowercase()}")
      }

      // Update LLM model
      llmModel?.let { model ->
        currentConfig = currentConfig.copy(llmModel = model)
        println("Set LLM model: $model")
      }

      // Update Set of Mark
      setOfMark?.let { enabled ->
        currentConfig = currentConfig.copy(setOfMarkEnabled = enabled)
        println("Set Set of Mark: $enabled")
      }

      // Update AI fallback
      aiFallback?.let { enabled ->
        currentConfig = currentConfig.copy(aiFallbackEnabled = enabled)
        println("Set AI fallback: $enabled")
      }

      // Save the updated config
      CliConfigHelper.writeConfig(currentConfig)
      println("\nConfiguration saved.")
    }

    // Show current configuration
    println()
    println("Current Configuration:")
    println("-".repeat(60))
    println("  Android driver:  ${currentConfig.selectedTrailblazeDriverTypes[TrailblazeDevicePlatform.ANDROID] ?: "not set"}")
    println("  iOS driver:      ${currentConfig.selectedTrailblazeDriverTypes[TrailblazeDevicePlatform.IOS] ?: "not set"}")
    println("  LLM provider:    ${currentConfig.llmProvider}")
    println("  LLM model:       ${currentConfig.llmModel}")
    println("  Set of Mark:     ${currentConfig.setOfMarkEnabled}")
    println("  AI fallback:     ${currentConfig.aiFallbackEnabled}")
    println()
    println("Settings file: ${CliConfigHelper.getSettingsFile().absolutePath}")

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

  override fun call(): Int {
    val daemon = DaemonClient()
    
    if (!daemon.isRunning()) {
      println("Trailblaze daemon is not running.")
      println()
      println("Start the daemon with: trailblaze")
      return CommandLine.ExitCode.OK
    }
    
    val status = daemon.getStatus()
    
    println("Trailblaze daemon is running.")
    println()
    if (status != null) {
      println("  Port:              ${status.port}")
      println("  Connected devices: ${status.connectedDevices}")
      println("  Uptime:            ${status.uptimeSeconds.seconds}")
      if (status.activeSessionId != null) {
        println("  Active session:    ${status.activeSessionId}")
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

  @Option(
    names = ["-f", "--force"],
    description = ["Force stop (kill process) if graceful shutdown fails"]
  )
  var force: Boolean = false

  override fun call(): Int {
    val daemon = DaemonClient()
    
    if (!daemon.isRunning()) {
      println("Trailblaze daemon is not running.")
      return CommandLine.ExitCode.OK
    }
    
    println("Stopping Trailblaze daemon...")
    
    val response = daemon.shutdown()
    if (response.success) {
      print("Waiting for daemon to stop")
      
      // Wait for daemon to actually stop, printing dots for progress
      var attempts = 0
      while (daemon.isRunning() && attempts < 20) {
        print(".")
        System.out.flush()
        Thread.sleep(500)
        attempts++
      }
      println()
      
      if (daemon.isRunning()) {
        if (force) {
          System.err.println("Daemon did not stop gracefully. Force option not yet implemented.")
          return CommandLine.ExitCode.SOFTWARE
        } else {
          System.err.println("Daemon did not stop gracefully. Use --force to kill.")
          return CommandLine.ExitCode.SOFTWARE
        }
      }
      
      println("Trailblaze daemon stopped.")
    } else {
      System.err.println("Failed to stop daemon: ${response.message}")
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
      println("No LLM providers configured.")
      exitProcess(CommandLine.ExitCode.OK)
    }

    println()
    println("LLM Provider Authentication Status:")
    println("-".repeat(60))
    
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
      println("  $statusIcon ${provider.display}: $statusText")
    }
    
    println()
    
    // Provide helpful hints for unconfigured providers
    val missingProviders = tokenStatuses.filter { it.value is LlmTokenStatus.NotAvailable }
    if (missingProviders.isNotEmpty()) {
      println("To configure missing providers, set the appropriate environment variables:")
      missingProviders.forEach { (provider, _) ->
        val envVar = config.getEnvironmentVariableForProvider(provider)
        if (envVar != null) {
          println("  ${provider.display}: Set $envVar")
        }
      }
      println()
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
