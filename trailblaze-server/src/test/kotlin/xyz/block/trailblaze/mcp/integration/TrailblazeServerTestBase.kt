package xyz.block.trailblaze.mcp.integration

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import xyz.block.trailblaze.util.Console

/**
 * Base class for integration tests that require a running Trailblaze server.
 *
 * This base class handles:
 * - **Auto-launching Trailblaze** if not already running
 * - Providing a configured [McpTestClient]
 * - Device detection and platform filtering
 *
 * ## Auto-Launch Behavior
 *
 * If Trailblaze is not running, the test will automatically start it using
 * `./gradlew :trailblaze-desktop:run`. Override [gradleRunTask] to customize.
 *
 * The server process will be stopped automatically after tests complete.
 *
 * ## Usage
 *
 * ```kotlin
 * class MyIntegrationTest : TrailblazeServerTestBase() {
 *
 *   override val devicePlatform = "android"  // or "ios", or null for any
 *
 *   @Test
 *   fun `my test`() {
 *     runBlocking {
 *       val result = client.runPrompt(listOf("Open Settings"))
 *       assertTrue(result.isSuccess)
 *     }
 *   }
 * }
 * ```
 *
 * ## Configuration
 *
 * Override properties to customize behavior:
 * - [gradleRunTask] - Gradle task to start server (default: `:trailblaze-desktop:run`)
 * - [autoLaunch] - Whether to auto-launch if not running (default: true)
 * - [requestTimeoutMs] - Timeout for MCP requests (default: 5 minutes)
 * - [requireDevice] - Whether to skip test if no device is connected (default: true)
 * - [devicePlatform] - "android", "ios", or null for any device
 */
abstract class TrailblazeServerTestBase {

  companion object {
    private const val DEFAULT_MCP_URL = "http://localhost:52525/mcp"
    // Use /ping endpoint for health check (exists in Trailblaze server)
    private const val DEFAULT_HEALTH_URL = "http://localhost:52525/ping"
    private const val DEFAULT_REQUEST_TIMEOUT_MS = 300_000L // 5 minutes
    private const val SERVER_STARTUP_TIMEOUT_MS = 180_000L // 3 minutes
    private const val HEALTH_CHECK_INTERVAL_MS = 2_000L // 2 seconds

    private const val DEFAULT_GRADLE_TASK = ":trailblaze-desktop:run"

    // Track if we started the server (so we can stop it)
    private var serverProcess: Process? = null
    private var serverStartedByTest = false
  }

  // Configurable properties - override in subclasses as needed
  protected open val mcpUrl: String = DEFAULT_MCP_URL
  protected open val healthUrl: String = DEFAULT_HEALTH_URL
  protected open val requestTimeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS
  protected open val requireDevice: Boolean = true
  protected open val devicePlatform: String? = null // "android", "ios", or null for any
  protected open val clientName: String = this::class.simpleName ?: "TrailblazeTest"

  /**
   * Gradle task to run to start the Trailblaze server.
   *
   * Defaults to `:trailblaze-desktop:run`.
   */
  protected open val gradleRunTask: String = DEFAULT_GRADLE_TASK

  /**
   * Whether to automatically launch Trailblaze if not running.
   * Set to false to require manual server start.
   */
  protected open val autoLaunch: Boolean = true

  /**
   * Whether to force kill and restart the server for deterministic behavior.
   * Default is true - tests will always start with a fresh server instance.
   */
  protected open val forceRestart: Boolean = true

  // Test infrastructure
  protected lateinit var client: McpTestClient
  protected var deviceId: String? = null

  /** Returns true if [client] has been initialized. Subclasses must use this instead of `::client.isInitialized`. */
  protected fun isClientInitialized(): Boolean = ::client.isInitialized

  @Before
  fun baseSetUp() {
    runBlocking {
      // Step 1: Handle server startup
      if (forceRestart) {
        // For determinism: always kill existing and start fresh
        Console.log("[TrailblazeServerTestBase] Force restart enabled - killing existing processes...")
        killExistingTrailblazeProcesses()
        Console.log("[TrailblazeServerTestBase] Launching fresh server instance...")
        if (!launchServer()) {
          assumeTrue("Failed to start Trailblaze server", false)
          return@runBlocking
        }
      } else if (!isServerRunning()) {
        if (autoLaunch) {
          Console.log("[TrailblazeServerTestBase] Server not running, launching...")
          if (!launchServer()) {
            assumeTrue("Failed to start Trailblaze server", false)
            return@runBlocking
          }
        } else {
          printManualStartInstructions()
          assumeTrue("Trailblaze server not running - start it manually", false)
          return@runBlocking
        }
      } else {
        Console.log("[TrailblazeServerTestBase] Server is already running (forceRestart=false)")
      }

      // Step 2: Create MCP client
      client = McpTestClient(
        serverUrl = mcpUrl,
        clientName = clientName,
        requestTimeoutMs = requestTimeoutMs,
      )

      // Step 3: Initialize MCP session
      try {
        client.initialize()
        Console.log("[TrailblazeServerTestBase] MCP session initialized")
      } catch (e: Exception) {
        Console.log("[TrailblazeServerTestBase] Failed to initialize MCP: ${e.message}")
        assumeTrue("Failed to initialize MCP session: ${e.message}", false)
        return@runBlocking
      }

      // Step 4: Check for devices if required
      if (requireDevice) {
        val devicesResult = client.listConnectedDevices()
        if (!devicesResult.isSuccess) {
          Console.log("[TrailblazeServerTestBase] No devices connected: ${devicesResult.content}")
          val platform = devicePlatform ?: "any"
          assumeTrue("No $platform device connected. Connect a device and try again.", false)
          return@runBlocking
        }

        // Extract device ID based on platform
        deviceId = when (devicePlatform) {
          "android" -> extractAndroidDeviceId(devicesResult.content)
          "ios" -> extractIosDeviceId(devicesResult.content)
          else -> extractAnyDeviceId(devicesResult.content)
        }

        if (deviceId == null) {
          Console.log("[TrailblazeServerTestBase] Could not find ${devicePlatform ?: "any"} device")
          Console.log("[TrailblazeServerTestBase] Available devices: ${devicesResult.content}")
          assumeTrue(
            "No ${devicePlatform ?: ""} device found. Available: ${devicesResult.content}",
            false,
          )
          return@runBlocking
        }

        Console.log("[TrailblazeServerTestBase] Using device: $deviceId")
      }
    }
  }

  @After
  fun baseTearDown() {
    runBlocking {
      // Close MCP client
      if (::client.isInitialized) {
        try {
          client.close()
        } catch (_: Exception) {
          // Ignore cleanup errors
        }
      }

      // Note: We don't stop the server here - it keeps running for subsequent tests
      // and is nice to see the UI. It will be stopped when the JVM exits or manually.
    }
  }

  // ==========================================================================
  // Server Launch
  // ==========================================================================

  /**
   * Kills any existing Trailblaze processes and frees port 52525 to ensure deterministic tests.
   */
  private fun killExistingTrailblazeProcesses() {
    Console.log("[TrailblazeServerTestBase] Checking for existing Trailblaze processes...")

    try {
      // Kill processes using port 52525 (macOS/Linux)
      val killPortProcess = ProcessBuilder("bash", "-c", "lsof -ti:52525 | xargs -r kill -9 2>/dev/null || true")
        .redirectErrorStream(true)
        .start()
      killPortProcess.waitFor(10, TimeUnit.SECONDS)

      // Kill any gradle trailblaze-desktop:run processes
      val killDesktopProcess = ProcessBuilder("bash", "-c", "pkill -9 -f 'trailblaze-desktop:run' 2>/dev/null || true")
        .redirectErrorStream(true)
        .start()
      killDesktopProcess.waitFor(10, TimeUnit.SECONDS)

      // Give OS time to release the port
      Thread.sleep(1000)

      // Verify port is free
      if (isServerRunning()) {
        Console.log("[TrailblazeServerTestBase] Warning: Port 52525 still in use after cleanup")
      } else {
        Console.log("[TrailblazeServerTestBase] Port 52525 is free")
      }
    } catch (e: Exception) {
      Console.log("[TrailblazeServerTestBase] Warning: Could not clean up existing processes: ${e.message}")
    }
  }

  private suspend fun launchServer(): Boolean {
    // First, kill any existing Trailblaze processes for determinism
    killExistingTrailblazeProcesses()

    // Find project root (look for gradlew)
    val projectRoot = findProjectRoot()
    if (projectRoot == null) {
      Console.log("[TrailblazeServerTestBase] Could not find project root (gradlew)")
      return false
    }

    Console.log("[TrailblazeServerTestBase] Project root: ${projectRoot.absolutePath}")
    Console.log("[TrailblazeServerTestBase] Launching: ./gradlew $gradleRunTask")

    try {
      // Start Gradle in the background
      val processBuilder = ProcessBuilder(
        "${projectRoot.absolutePath}/gradlew",
        gradleRunTask,
        "--no-daemon",
      )
      processBuilder.directory(projectRoot)
      processBuilder.redirectErrorStream(true)

      // Inherit IO so we can see the output (nice for debugging)
      processBuilder.inheritIO()

      serverProcess = processBuilder.start()
      serverStartedByTest = true

      Console.log("[TrailblazeServerTestBase] Waiting for server to be ready...")

      // Wait for server to be ready
      val startTime = System.currentTimeMillis()
      while (System.currentTimeMillis() - startTime < SERVER_STARTUP_TIMEOUT_MS) {
        if (isServerRunning()) {
          val elapsed = System.currentTimeMillis() - startTime
          Console.log("[TrailblazeServerTestBase] Server ready after ${elapsed}ms")
          return true
        }
        delay(HEALTH_CHECK_INTERVAL_MS)
      }

      Console.log("[TrailblazeServerTestBase] Server startup timed out after ${SERVER_STARTUP_TIMEOUT_MS}ms")
      return false
    } catch (e: Exception) {
      Console.log("[TrailblazeServerTestBase] Failed to launch server: ${e.message}")
      e.printStackTrace()
      return false
    }
  }

  private fun findProjectRoot(): File? {
    var dir = File(System.getProperty("user.dir"))
    repeat(10) {
      if (File(dir, "gradlew").exists()) {
        return dir
      }
      dir = dir.parentFile ?: return null
    }
    return null
  }

  private fun printManualStartInstructions() {
    Console.log(
      """
      |
      |=======================================================================
      | TRAILBLAZE SERVER NOT RUNNING
      |=======================================================================
      |
      | These tests require a running Trailblaze server. Please start it:
      |
      |   ./gradlew $gradleRunTask
      |
      | Or set autoLaunch = true to start automatically.
      |
      | Make sure you have:
      |   - LLM credentials configured (e.g., OpenAI, Anthropic)
      |   - A connected device (Android emulator or iOS simulator)
      |
      |=======================================================================
      """.trimMargin(),
    )
  }

  // ==========================================================================
  // Server Check
  // ==========================================================================

  private fun isServerRunning(): Boolean {
    return try {
      val url = URL(healthUrl)
      val connection = url.openConnection() as HttpURLConnection
      connection.connectTimeout = 2000
      connection.readTimeout = 2000
      connection.requestMethod = "GET"
      val responseCode = connection.responseCode
      connection.disconnect()
      responseCode == 200
    } catch (e: Exception) {
      false
    }
  }

  // ==========================================================================
  // Device ID Extraction
  // ==========================================================================

  private fun extractAndroidDeviceId(response: String): String? {
    // Look for emulator pattern first (most common for testing)
    val emulatorPattern = Regex("""emulator-\d+""")
    emulatorPattern.find(response)?.value?.let { return it }

    // Look for physical Android device patterns (serial numbers)
    val serialPattern = Regex("""[A-Z0-9]{10,}""")
    serialPattern.find(response)?.value?.let { return it }

    // Look for deviceId in JSON response
    val deviceIdPattern = Regex(""""deviceId"\s*:\s*"([^"]+)"""")
    deviceIdPattern.find(response)?.groupValues?.getOrNull(1)?.let {
      if (!it.contains("-")) return it // Not a UUID (iOS)
    }

    return null
  }

  private fun extractIosDeviceId(response: String): String? {
    // iOS simulator UUIDs are in format: XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX
    val uuidPattern = Regex(
      """[A-F0-9]{8}-[A-F0-9]{4}-[A-F0-9]{4}-[A-F0-9]{4}-[A-F0-9]{12}""",
      RegexOption.IGNORE_CASE,
    )
    uuidPattern.find(response)?.value?.let { return it }

    return null
  }

  private fun extractAnyDeviceId(response: String): String? {
    // Try iOS first (UUIDs are more specific), then Android
    return extractIosDeviceId(response) ?: extractAndroidDeviceId(response)
  }
}
