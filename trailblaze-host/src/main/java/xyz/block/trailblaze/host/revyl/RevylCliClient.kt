package xyz.block.trailblaze.host.revyl

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.block.trailblaze.util.Console
import java.io.File

/**
 * Device interaction client that delegates to the `revyl` CLI binary.
 *
 * Every method builds a `revyl device <subcommand> --json` process,
 * executes it, and parses the structured JSON output. The CLI handles
 * auth, backend proxy routing, and AI-powered target grounding.
 *
 * If the `revyl` binary is not found on PATH, it is automatically
 * installed via the official installer script from GitHub.
 * The only prerequisite is setting the `REVYL_API_KEY` environment variable.
 *
 * @property revylBinaryOverride Explicit path to the revyl binary.
 *     Defaults to `REVYL_BINARY` env var, then PATH lookup, then
 *     auto-install via install.sh.
 * @property workingDirectory Optional working directory for CLI
 *     invocations. Defaults to the JVM's current directory.
 */
class RevylCliClient(
  private val revylBinaryOverride: String? = System.getenv("REVYL_BINARY"),
  private val workingDirectory: File? = null,
) {

  private val json = Json { ignoreUnknownKeys = true }
  private val sessions = mutableMapOf<Int, RevylSession>()
  private var activeSessionIndex: Int = 0

  private var resolvedBinary: String = revylBinaryOverride ?: "revyl"
  private val installDir = File(System.getProperty("user.home"), ".revyl/bin")
  private val installerUrl =
    "https://raw.githubusercontent.com/RevylAI/revyl-cli/main/scripts/install.sh"

  init {
    ensureRevylInstalled()
  }

  /**
   * Returns the currently active session, or null if none has been started.
   */
  fun getActiveSession(): RevylSession? = sessions[activeSessionIndex]

  /**
   * Returns the session at the given index, or null if not found.
   *
   * @param index Session index to retrieve.
   */
  fun getSession(index: Int): RevylSession? = sessions[index]

  /**
   * Finds a session by its workflow run ID.
   *
   * @param workflowRunId The Hatchet workflow run identifier.
   * @return The matching session, or null if not found.
   */
  fun getSession(workflowRunId: String): RevylSession? =
    sessions.values.firstOrNull { it.workflowRunId == workflowRunId }

  /**
   * Returns all active sessions.
   */
  fun getAllSessions(): Map<Int, RevylSession> = sessions.toMap()

  /**
   * Switches the active session to the given index.
   *
   * @param index Session index to make active.
   * @throws IllegalArgumentException If no session exists at the given index.
   */
  fun useSession(index: Int) {
    require(sessions.containsKey(index)) { "No session at index $index. Active sessions: ${sessions.keys}" }
    activeSessionIndex = index
    Console.log("RevylCli: switched to session $index (${sessions[index]!!.platform})")
  }

  // ---------------------------------------------------------------------------
  // Auto-install and auto-update
  // ---------------------------------------------------------------------------

  /**
   * Ensures the revyl CLI binary is available and up to date.
   *
   * If the binary is missing, runs the official installer. If installed
   * but outdated compared to the latest GitHub release, re-runs the
   * installer to upgrade. Network failures are logged and the existing
   * binary is used as-is.
   *
   * @throws RevylCliException If installation fails and no binary is available.
   */
  private fun ensureRevylInstalled() {
    val installed = getInstalledVersion()
    if (installed == null) {
      Console.log("RevylCli: 'revyl' not found — installing...")
      runInstaller()
      return
    }

    val latest = getLatestVersion()
    if (latest != null && latest != installed) {
      Console.log("RevylCli: upgrading $installed -> $latest")
      try {
        runInstaller()
      } catch (e: Exception) {
        Console.log("RevylCli: upgrade failed (${e.message}), continuing with $installed")
      }
    } else {
      Console.log("RevylCli: $installed (up to date)")
    }
  }

  /**
   * Returns the installed CLI version string (e.g. "v0.1.14"), or null
   * if the binary is not found or not executable.
   */
  private fun getInstalledVersion(): String? {
    return try {
      val process = ProcessBuilder(resolvedBinary, "--version")
        .redirectErrorStream(true)
        .start()
      val output = process.inputStream.bufferedReader().readText().trim()
      if (process.waitFor() == 0) {
        output.substringAfterLast(" ", "").takeIf { it.startsWith("v") }
      } else null
    } catch (_: Exception) {
      null
    }
  }

  /**
   * Resolves the latest release version from GitHub (e.g. "v0.1.15")
   * by following the /releases/latest redirect. Returns null on any
   * network failure, timeout, or parse error.
   */
  private fun getLatestVersion(): String? {
    return try {
      val url = java.net.URL("https://github.com/RevylAI/revyl-cli/releases/latest")
      val conn = url.openConnection() as java.net.HttpURLConnection
      conn.instanceFollowRedirects = false
      conn.connectTimeout = 3000
      conn.readTimeout = 3000
      val location = conn.getHeaderField("Location")
      conn.disconnect()
      location?.substringAfterLast("/")?.takeIf { it.startsWith("v") }
    } catch (_: Exception) {
      null
    }
  }

  /**
   * Runs the official Revyl CLI installer script via curl.
   *
   * @throws RevylCliException If the installer exits with a non-zero code
   *     or the binary is not found after installation.
   */
  private fun runInstaller() {
    try {
      val process = ProcessBuilder("sh", "-c", "curl -fsSL '$installerUrl' | sh")
        .redirectErrorStream(true)
        .also { pb ->
          pb.environment()["REVYL_INSTALL_DIR"] = installDir.absolutePath
          pb.environment()["REVYL_NO_MODIFY_PATH"] = "1"
        }
        .start()
      val output = process.inputStream.bufferedReader().readText()
      val exitCode = process.waitFor()

      if (exitCode != 0) {
        throw RevylCliException(
          "Installer failed (exit $exitCode): ${output.take(500)}\n" +
            "Install manually: brew install RevylAI/tap/revyl"
        )
      }

      val binaryPath = File(installDir, "revyl")
      if (binaryPath.exists() && binaryPath.canExecute()) {
        resolvedBinary = binaryPath.absolutePath
        val newVersion = getInstalledVersion() ?: "unknown"
        Console.log("RevylCli: installed $newVersion to ${binaryPath.absolutePath}")
      } else {
        throw RevylCliException(
          "Installer completed but binary not found at ${binaryPath.absolutePath}. " +
            "Install manually: brew install RevylAI/tap/revyl"
        )
      }
    } catch (e: RevylCliException) {
      throw e
    } catch (e: Exception) {
      throw RevylCliException(
        "Auto-install failed: ${e.message}. " +
          "Install manually: brew install RevylAI/tap/revyl " +
          "or download from https://github.com/RevylAI/revyl-cli/releases"
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
   * @return The newly created [RevylSession] parsed from CLI JSON output.
   * @throws RevylCliException If the CLI exits with a non-zero code.
   * @throws IllegalArgumentException If only one of deviceModel/osVersion is provided.
   */
  fun startSession(
    platform: String,
    appUrl: String? = null,
    appLink: String? = null,
    deviceModel: String? = null,
    osVersion: String? = null,
  ): RevylSession {
    require(deviceModel.isNullOrBlank() == osVersion.isNullOrBlank()) {
      "deviceModel and osVersion must both be provided or both be null/blank " +
        "(got deviceModel=$deviceModel, osVersion=$osVersion)"
    }
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
   * @param index Session index to stop. Defaults to -1 (active session).
   * @throws RevylCliException If the CLI exits with a non-zero code.
   */
  fun stopSession(index: Int = -1) {
    val targetIndex = if (index >= 0) index else activeSessionIndex
    val args = mutableListOf("device", "stop")
    if (targetIndex >= 0) args += listOf("-s", targetIndex.toString())
    runCli(args)
    sessions.remove(targetIndex)
    if (activeSessionIndex == targetIndex && sessions.isNotEmpty()) {
      activeSessionIndex = sessions.keys.first()
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
  fun screenshot(outPath: String = createTempScreenshotPath()): ByteArray {
    runCli(deviceArgs("screenshot", "--out", outPath))
    val file = File(outPath)
    if (!file.exists()) {
      throw RevylCliException("Screenshot file not found at $outPath")
    }
    return file.readBytes()
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
    if (clearFirst) args += "--clear-first"
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

    for (platform in listOf("android", "ios")) {
      val entries = root[platform]?.jsonArray ?: continue
      for (entry in entries) {
        val model = entry.jsonObject["Model"]?.jsonPrimitive?.content ?: continue
        val runtime = entry.jsonObject["Runtime"]?.jsonPrimitive?.content ?: continue
        if (seenModels.add("$platform:$model")) {
          results.add(RevylDeviceTarget(platform = platform, model = model, osVersion = runtime))
        }
      }
    }
    return results
  }

  // ---------------------------------------------------------------------------
  // High-level steps (instruction / validation)
  // ---------------------------------------------------------------------------

  /**
   * Executes a natural-language instruction step on the active device via
   * `revyl device instruction "<description>" --json`.
   *
   * Revyl's worker agent handles planning, grounding, and execution
   * in a single round-trip.
   *
   * @param description Natural-language instruction (e.g. "Tap the Search tab").
   * @return Parsed [RevylLiveStepResult] with success flag and step output.
   * @throws RevylCliException If the CLI process exits with a non-zero code.
   */
  fun instruction(description: String): RevylLiveStepResult {
    val stdout = runCli(deviceArgs("instruction", description))
    return RevylLiveStepResult.fromJson(stdout)
  }

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
    Console.log("RevylCli: ${command.joinToString(" ")}")

    val processBuilder = ProcessBuilder(command)
      .redirectErrorStream(false)

    if (workingDirectory != null) {
      processBuilder.directory(workingDirectory)
    }

    val process = processBuilder.start()
    val stdout = process.inputStream.bufferedReader().readText()
    val stderr = process.errorStream.bufferedReader().readText()
    val exitCode = process.waitFor()

    if (exitCode != 0) {
      val errorDetail = stderr.ifBlank { stdout }
      throw RevylCliException(
        "revyl ${args.firstOrNull() ?: ""} ${args.getOrNull(1) ?: ""} " +
          "failed (exit $exitCode): ${errorDetail.take(500)}"
      )
    }

    return stdout.trim()
  }

  private fun createTempScreenshotPath(): String {
    val tmpDir = System.getProperty("java.io.tmpdir")
    return "$tmpDir/revyl-screenshot-${System.currentTimeMillis()}.png"
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
 * @property platform "ios" or "android".
 * @property model Human-readable model name (e.g. "iPhone 16", "Pixel 7").
 * @property osVersion Runtime / OS version string (e.g. "Android 14", "iOS 18.2").
 */
data class RevylDeviceTarget(val platform: String, val model: String, val osVersion: String)
