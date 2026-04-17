package xyz.block.trailblaze.revyl

import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.TrailblazeProcessBuilderUtils
import java.io.File

/**
 * Device interaction client that delegates to the `revyl` CLI binary.
 *
 * Every method builds a `revyl device <subcommand> --json` process,
 * executes it, and parses the structured JSON output. The CLI handles
 * auth, backend proxy routing, and AI-powered target grounding.
 *
 * The `revyl` binary is resolved from PATH by default, or can be pointed to via
 * `REVYL_BINARY` for local/dev overrides. If not found, [startSession] throws a
 * [RevylCliException] with install instructions.
 *
 * @property revylBinaryOverride Optional explicit path to the revyl binary.
 *     Defaults to `REVYL_BINARY`; if null, PATH lookup uses `revyl`.
 * @property workingDirectory Optional working directory for CLI
 *     invocations. Defaults to the JVM's current directory.
 */
class RevylCliClient(
  private val revylBinaryOverride: String? = System.getenv("REVYL_BINARY"),
  private val workingDirectory: File? = null,
) {

  private val json = TrailblazeJsonInstance
  private val sessions = mutableMapOf<Int, RevylSession>()
  private var activeSessionIndex: Int = ACTIVE_SESSION

  private val resolvedBinary: String = revylBinaryOverride ?: "revyl"
  val isCliAvailable: Boolean by lazy { TrailblazeProcessBuilderUtils.isCommandAvailable(resolvedBinary) }

  companion object {
    /** Sentinel value for "use the currently active session". */
    const val ACTIVE_SESSION = -1

    /** Default timeout in seconds for CLI commands. */
    const val CLI_TIMEOUT_SECONDS = 120L

    /** Environment variable name for the Revyl API key. */
    const val REVYL_API_KEY_ENV = "REVYL_API_KEY"

    /** Platform identifier for iOS devices. */
    const val PLATFORM_IOS = "ios"

    /** Platform identifier for Android devices. */
    const val PLATFORM_ANDROID = "android"
  }

  // ---------------------------------------------------------------------------
  // CLI verification
  // ---------------------------------------------------------------------------

  /**
   * Verifies the revyl CLI binary is available on PATH. Called lazily on first
   * device provisioning so construction never throws.
   *
   * @throws RevylCliException If the binary is not found, with install instructions.
   */
  private fun verifyCliAvailable() {
    if (!isCliAvailable) {
      throw RevylCliException(
        "revyl CLI not found (resolved binary: '$resolvedBinary'). " +
          "Ensure 'revyl' is on PATH (or set REVYL_BINARY) and set REVYL_API_KEY, then try again."
      )
    }
  }

  // ---------------------------------------------------------------------------
  // Session lifecycle
  // ---------------------------------------------------------------------------

  /**
   * Provisions a cloud device by running `revyl device start`.
   *
   * @param platform "ios" or "android".
   * @param appUrl Optional public URL to an .apk/.ipa to install on start.
   * @param appLink Optional deep-link to open after launch.
   * @param deviceModel Optional explicit device model (e.g. "Pixel 7").
   * @param osVersion Optional explicit OS version (e.g. "Android 14").
   * @param appId Optional Revyl `apps` table UUID. The backend resolves the
   *     latest build artifact and package name from this id.
   * @param buildVersionId Optional `builds` table UUID. When provided the
   *     backend uses this exact build instead of resolving the latest.
   * @return The newly created [RevylSession] parsed from CLI JSON output.
   * @throws RevylCliException If the CLI exits with a non-zero code.
   */
  fun startSession(
    platform: String,
    appUrl: String? = null,
    appLink: String? = null,
    deviceModel: String? = null,
    osVersion: String? = null,
    appId: String? = null,
    buildVersionId: String? = null,
  ): RevylSession {
    verifyCliAvailable()
    val args = mutableListOf("device", "start", "--platform", platform.lowercase())
    if (!appUrl.isNullOrBlank()) {
      args += listOf("--app-url", appUrl)
    }
    if (!appLink.isNullOrBlank()) {
      args += listOf("--app-link", appLink)
    }
    if (!deviceModel.isNullOrBlank()) {
      args += listOf("--device-model", deviceModel)
    }
    if (!osVersion.isNullOrBlank()) {
      args += listOf("--os-version", osVersion)
    }
    if (!appId.isNullOrBlank()) {
      args += listOf("--app-id", appId)
    }
    if (!buildVersionId.isNullOrBlank()) {
      args += listOf("--build-version-id", buildVersionId)
    }

    val result = runCli(args)
    val obj = json.parseToJsonElement(result).jsonObject

    val session = RevylSession(
      index = obj["index"]?.jsonPrimitive?.int ?: 0,
      sessionId = obj["session_id"]?.jsonPrimitive?.content ?: "",
      workflowRunId = obj["workflow_run_id"]?.jsonPrimitive?.content ?: "",
      workerBaseUrl = obj["worker_base_url"]?.jsonPrimitive?.content ?: "",
      viewerUrl = obj["viewer_url"]?.jsonPrimitive?.content ?: "",
      platform = platform.lowercase(),
      screenWidth = obj["screen_width"]?.jsonPrimitive?.intOrNull ?: 0,
      screenHeight = obj["screen_height"]?.jsonPrimitive?.intOrNull ?: 0,
    )
    sessions[session.index] = session
    activeSessionIndex = session.index
    Console.log("RevylCli: device ready (session ${session.index}, ${session.platform})")
    Console.log("  Viewer: ${session.viewerUrl}")
    if (session.screenWidth > 0) {
      Console.log("  Screen: ${session.screenWidth}x${session.screenHeight}")
    }
    return session
  }

  /**
   * Stops a device session and removes it from the local session map.
   *
   * @param index Session index to stop. Defaults to [ACTIVE_SESSION].
   * @throws RevylCliException If the CLI exits with a non-zero code.
   */
  fun stopSession(index: Int = ACTIVE_SESSION) {
    val targetIndex = if (index >= 0) index else activeSessionIndex
    val args = mutableListOf("device", "stop")
    if (targetIndex >= 0) args += listOf("-s", targetIndex.toString())
    runCli(args)
    sessions.remove(targetIndex)
    if (activeSessionIndex == targetIndex) {
      activeSessionIndex = if (sessions.isNotEmpty()) sessions.keys.first() else ACTIVE_SESSION
    }
  }

  /**
   * Stops all active device sessions.
   *
   * @throws RevylCliException If the CLI exits with a non-zero code.
   */
  fun stopAllSessions() {
    runCli(listOf("device", "stop", "--all"))
    sessions.clear()
  }

  // ---------------------------------------------------------------------------
  // Device actions — all return RevylActionResult with coordinates
  // ---------------------------------------------------------------------------

  private fun deviceArgs(vararg args: String): List<String> {
    val base = mutableListOf("device")
    if (activeSessionIndex >= 0) {
      base += listOf("-s", activeSessionIndex.toString())
    }
    base += args.toList()
    return base
  }

  /**
   * Captures a PNG screenshot and returns the raw bytes.
   *
   * @param outPath File path to write the screenshot to.
   * @return Raw PNG bytes read from the output file.
   * @throws RevylCliException If the CLI exits with a non-zero code.
   */
  fun screenshot(outPath: String? = null): ByteArray {
    val resolvedOutPath = outPath ?: createTempScreenshotPath()
    val shouldDeleteFile = outPath == null
    runCli(deviceArgs("screenshot", "--out", resolvedOutPath))
    val file = File(resolvedOutPath)
    if (!file.exists()) {
      throw RevylCliException("Screenshot file not found at $resolvedOutPath")
    }
    return try {
      file.readBytes()
    } finally {
      if (shouldDeleteFile) {
        file.delete()
      }
    }
  }

  /**
   * Taps at exact pixel coordinates on the device screen.
   *
   * @param x Horizontal pixel coordinate.
   * @param y Vertical pixel coordinate.
   * @return Action result with the tapped coordinates.
   * @throws RevylCliException If the CLI exits with a non-zero code.
   */
  fun tap(x: Int, y: Int): RevylActionResult {
    val stdout = runCli(deviceArgs("tap", "--x", x.toString(), "--y", y.toString()))
    return RevylActionResult.fromJson(stdout)
  }

  /**
   * Taps a UI element identified by natural language description.
   *
   * @param target Natural language description (e.g. "Sign In button").
   * @return Action result with the resolved coordinates.
   * @throws RevylCliException If the CLI exits with a non-zero code.
   */
  fun tapTarget(target: String): RevylActionResult {
    val stdout = runCli(deviceArgs("tap", "--target", target))
    return RevylActionResult.fromJson(stdout)
  }

  /**
   * Double-taps a UI element identified by natural language description.
   *
   * @param target Natural language description of the element to double-tap.
   * @return Action result with the resolved coordinates.
   * @throws RevylCliException If the CLI exits with a non-zero code.
   */
  fun doubleTap(target: String): RevylActionResult {
    val stdout = runCli(deviceArgs("double-tap", "--target", target))
    return RevylActionResult.fromJson(stdout)
  }

  /**
   * Types text into an input field, optionally targeting a specific element.
   *
   * @param text The text to type.
   * @param target Optional natural language element description to tap first.
   * @param clearFirst If true, clears the field before typing.
   * @return Action result with the field coordinates.
   * @throws RevylCliException If the CLI exits with a non-zero code.
   */
  fun typeText(text: String, target: String? = null, clearFirst: Boolean = false): RevylActionResult {
    val args = deviceArgs("type", "--text", text).toMutableList()
    if (!target.isNullOrBlank()) args += listOf("--target", target)
    args += "--clear-first=$clearFirst"
    val stdout = runCli(args)
    return RevylActionResult.fromJson(stdout)
  }

  /**
   * Swipes in the given direction, optionally from a targeted element.
   *
   * @param direction One of "up", "down", "left", "right".
   * @param target Optional natural language element description for swipe origin.
   * @return Action result with the swipe origin coordinates.
   * @throws RevylCliException If the CLI exits with a non-zero code.
   */
  fun swipe(direction: String, target: String? = null): RevylActionResult {
    val args = deviceArgs("swipe", "--direction", direction).toMutableList()
    if (!target.isNullOrBlank()) args += listOf("--target", target)
    val stdout = runCli(args)
    return RevylActionResult.fromJson(stdout)
  }

  /**
   * Long-presses a UI element identified by natural language description.
   *
   * @param target Natural language description of the element.
   * @return Action result with the pressed coordinates.
   * @throws RevylCliException If the CLI exits with a non-zero code.
   */
  fun longPress(target: String): RevylActionResult {
    val stdout = runCli(deviceArgs("long-press", "--target", target))
    return RevylActionResult.fromJson(stdout)
  }

  /**
   * Presses the Android back button.
   *
   * @return Action result (coordinates are 0,0 for back).
   * @throws RevylCliException If the CLI exits with a non-zero code.
   */
  fun back(): RevylActionResult {
    val stdout = runCli(deviceArgs("back"))
    return RevylActionResult.fromJson(stdout)
  }

  /**
   * Sends a key press event (ENTER or BACKSPACE).
   *
   * @param key Key name: "ENTER" or "BACKSPACE".
   * @return Action result (coordinates are 0,0 for key presses).
   * @throws RevylCliException If the CLI exits with a non-zero code.
   */
  fun pressKey(key: String): RevylActionResult {
    val stdout = runCli(deviceArgs("key", "--key", key.uppercase()))
    return RevylActionResult.fromJson(stdout)
  }

  /**
   * Opens a URL or deep link on the device.
   *
   * @param url The URL or deep link to open.
   * @throws RevylCliException If the CLI exits with a non-zero code.
   */
  fun navigate(url: String) {
    runCli(deviceArgs("navigate", "--url", url))
  }

  /**
   * Clears text from the currently focused input field.
   *
   * @param target Optional natural language element description.
   * @return Action result with the field coordinates.
   * @throws RevylCliException If the CLI exits with a non-zero code.
   */
  fun clearText(target: String? = null): RevylActionResult {
    val args = deviceArgs("clear-text").toMutableList()
    if (!target.isNullOrBlank()) args += listOf("--target", target)
    val stdout = runCli(args)
    return RevylActionResult.fromJson(stdout)
  }

  /**
   * Installs an app on the device from a public URL.
   *
   * @param appUrl Direct download URL for the .apk or .ipa.
   * @throws RevylCliException If the CLI exits with a non-zero code.
   */
  fun installApp(appUrl: String) {
    runCli(deviceArgs("install", "--app-url", appUrl))
  }

  /**
   * Launches an installed app by its bundle/package ID.
   *
   * @param bundleId The app's bundle identifier (iOS) or package name (Android).
   * @throws RevylCliException If the CLI exits with a non-zero code.
   */
  fun launchApp(bundleId: String) {
    runCli(deviceArgs("launch", "--bundle-id", bundleId))
  }

  /**
   * Navigates to the device home screen.
   *
   * @throws RevylCliException If the CLI exits with a non-zero code.
   */
  fun home() {
    runCli(deviceArgs("home"))
  }

  /**
   * Toggles device network connectivity (airplane mode).
   *
   * @param connected true to enable network (disable airplane mode),
   *     false to disable network (enable airplane mode).
   * @throws RevylCliException If the CLI exits with a non-zero code.
   */
  fun setNetworkConnected(connected: Boolean) {
    runCli(deviceArgs("network", if (connected) "--connected" else "--disconnected"))
  }

  // ---------------------------------------------------------------------------
  // Device catalog
  // ---------------------------------------------------------------------------

  /**
   * Queries the available device models from the Revyl backend catalog.
   *
   * Calls `revyl device targets --json` and parses the response into a
   * deduplicated list of [RevylDeviceTarget] entries (one per unique model).
   *
   * @return Available device models grouped by platform.
   * @throws RevylCliException If the CLI command fails.
   */
  fun getDeviceTargets(): List<RevylDeviceTarget> {
    val stdout = runCli(listOf("device", "targets"))
    val root = json.parseToJsonElement(stdout).jsonObject
    val results = mutableListOf<RevylDeviceTarget>()
    val seenModels = mutableSetOf<String>()

    for ((platformKey, devicePlatform) in listOf(
      PLATFORM_ANDROID to TrailblazeDevicePlatform.ANDROID,
      PLATFORM_IOS to TrailblazeDevicePlatform.IOS,
    )) {
      val entries = root[platformKey]?.jsonArray ?: continue
      for (entry in entries) {
        val model = entry.jsonObject["Model"]?.jsonPrimitive?.content ?: continue
        val runtime = entry.jsonObject["Runtime"]?.jsonPrimitive?.content ?: continue
        if (seenModels.add("$platformKey:$model")) {
          results.add(RevylDeviceTarget(platform = devicePlatform, model = model, osVersion = runtime))
        }
      }
    }
    return results
  }

  // ---------------------------------------------------------------------------
  // High-level steps (validation)
  // ---------------------------------------------------------------------------

  /**
   * Executes a natural-language validation step on the active device via
   * `revyl device validation "<description>" --json`.
   *
   * Revyl's worker agent performs a visual assertion against the current
   * screen state and returns a pass/fail result.
   *
   * @param description Natural-language assertion (e.g. "The search results are visible").
   * @return Parsed [RevylLiveStepResult] with success flag and step output.
   * @throws RevylCliException If the CLI process exits with a non-zero code.
   */
  fun validation(description: String): RevylLiveStepResult {
    val stdout = runCli(deviceArgs("validation", description))
    return RevylLiveStepResult.fromJson(stdout)
  }

  // ---------------------------------------------------------------------------
  // CLI execution
  // ---------------------------------------------------------------------------

  /**
   * Executes a revyl CLI command with `--json` and returns stdout.
   *
   * Inherits `REVYL_API_KEY` and other env vars from the parent process.
   * On non-zero exit, throws [RevylCliException] with stderr content.
   *
   * @param args Command arguments after the binary name.
   * @return Stdout content from the CLI process.
   * @throws RevylCliException If the process exits with a non-zero code.
   */
  private fun runCli(args: List<String>): String {
    val command = listOf(resolvedBinary) + args + "--json"
    Console.log("RevylCli: ${redactCommand(command)}")

    val processBuilder = ProcessBuilder(command)
      .redirectErrorStream(false)

    if (workingDirectory != null) {
      processBuilder.directory(workingDirectory)
    }

    val process = processBuilder.start()

    // Read stdout and stderr concurrently to avoid deadlocks when the process
    // writes enough data to fill one stream's buffer while we block-read the other.
    var stderr = ""
    val stderrThread = Thread {
      stderr = process.errorStream.bufferedReader().readText()
    }.also { it.start() }
    val stdout = process.inputStream.bufferedReader().readText()
    stderrThread.join()

    val exited = process.waitFor(CLI_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
    if (!exited) {
      process.destroyForcibly()
      throw RevylCliException(
        "revyl ${args.firstOrNull() ?: ""} ${args.getOrNull(1) ?: ""} " +
          "timed out after $CLI_TIMEOUT_SECONDS seconds"
      )
    }
    val exitCode = process.exitValue()

    if (exitCode != 0) {
      val errorDetail = stderr.ifBlank { stdout }
      throw RevylCliException(
        "revyl ${args.firstOrNull() ?: ""} ${args.getOrNull(1) ?: ""} " +
          "failed (exit $exitCode): ${errorDetail.take(500)}"
      )
    }

    return stdout.trim()
  }

  /** Redacts sensitive flag values (e.g. --text) from CLI commands for logging. */
  private fun redactCommand(command: List<String>): String {
    val sensitiveFlags = setOf("--text")
    val result = mutableListOf<String>()
    var redactNext = false
    for (token in command) {
      if (redactNext) {
        result.add("***")
        redactNext = false
      } else if (token in sensitiveFlags) {
        result.add(token)
        redactNext = true
      } else {
        result.add(token)
      }
    }
    return result.joinToString(" ")
  }

  private fun createTempScreenshotPath(): String {
    val tempFile = File.createTempFile("revyl-screenshot-", ".png")
    tempFile.deleteOnExit()
    return tempFile.absolutePath
  }
}

/**
 * Exception thrown when a revyl CLI command fails.
 *
 * @property message Human-readable description including the exit code and stderr.
 */
class RevylCliException(message: String) : RuntimeException(message)

/**
 * A device model available in the Revyl cloud catalog.
 *
 * @property platform One of [RevylCliClient.PLATFORM_IOS] or [RevylCliClient.PLATFORM_ANDROID].
 * @property model Human-readable model name (e.g. "iPhone 16", "Pixel 7").
 * @property osVersion Runtime / OS version string (e.g. "Android 14", "iOS 18.2").
 */
data class RevylDeviceTarget(val platform: TrailblazeDevicePlatform, val model: String, val osVersion: String)
