package xyz.block.trailblaze.host.revyl

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.block.trailblaze.util.Console
import java.io.File
import java.net.URL

/**
 * Device interaction client that delegates to the `revyl` CLI binary.
 *
 * Every method builds a `revyl device <subcommand> --json` process,
 * executes it, and parses the structured JSON output. The CLI handles
 * auth, backend proxy routing, and AI-powered target grounding.
 *
 * If the `revyl` binary is not found on PATH, it is automatically
 * downloaded from GitHub Releases to `~/.revyl/bin/revyl`. The only
 * prerequisite is setting the `REVYL_API_KEY` environment variable.
 *
 * @property revylBinaryOverride Explicit path to the revyl binary.
 *     Defaults to `REVYL_BINARY` env var, then PATH lookup, then
 *     auto-download.
 * @property workingDirectory Optional working directory for CLI
 *     invocations. Defaults to the JVM's current directory.
 */
class RevylCliClient(
  private val revylBinaryOverride: String? = System.getenv("REVYL_BINARY"),
  private val workingDirectory: File? = null,
) {

  private val json = Json { ignoreUnknownKeys = true }
  private var currentSession: RevylSession? = null

  private var resolvedBinary: String = revylBinaryOverride ?: "revyl"

  init {
    ensureRevylInstalled()
  }

  /**
   * Returns the currently active session, or null if none has been started.
   */
  fun getSession(): RevylSession? = currentSession

  // ---------------------------------------------------------------------------
  // Auto-install
  // ---------------------------------------------------------------------------

  /**
   * Ensures the revyl CLI binary is available. If not found on PATH,
   * downloads the correct platform binary from GitHub Releases to
   * `~/.revyl/bin/revyl` and uses that path for all subsequent calls.
   *
   * @throws RevylCliException If the platform is unsupported or download fails.
   */
  private fun ensureRevylInstalled() {
    if (isRevylAvailable()) return

    Console.log("RevylCli: 'revyl' not found on PATH — downloading automatically...")
    val (os, arch) = detectPlatform()
    val assetName = "revyl-$os-$arch" + if (os == "windows") ".exe" else ""
    val downloadUrl =
      "https://github.com/RevylAI/revyl-cli/releases/latest/download/$assetName"

    val installDir = File(System.getProperty("user.home"), ".revyl/bin")
    installDir.mkdirs()
    val binaryName = if (os == "windows") "revyl.exe" else "revyl"
    val binaryFile = File(installDir, binaryName)

    try {
      Console.log("RevylCli: downloading $downloadUrl")
      URL(downloadUrl).openStream().use { input ->
        binaryFile.outputStream().use { output -> input.copyTo(output) }
      }
      binaryFile.setExecutable(true)
      resolvedBinary = binaryFile.absolutePath
      Console.log("RevylCli: installed to ${binaryFile.absolutePath}")
    } catch (e: Exception) {
      throw RevylCliException(
        "Auto-download failed: ${e.message}. " +
          "Install manually: brew install RevylAI/tap/revyl " +
          "or download from https://github.com/RevylAI/revyl-cli/releases"
      )
    }

    if (!isRevylAvailable()) {
      throw RevylCliException(
        "Downloaded binary at ${binaryFile.absolutePath} is not executable. " +
          "Install manually: brew install RevylAI/tap/revyl"
      )
    }
  }

  /**
   * Checks whether the resolved revyl binary is callable.
   *
   * @return true if `revyl --version` exits successfully.
   */
  private fun isRevylAvailable(): Boolean {
    return try {
      val process = ProcessBuilder(resolvedBinary, "--version")
        .redirectErrorStream(true)
        .start()
      process.inputStream.bufferedReader().readText()
      process.waitFor() == 0
    } catch (_: Exception) {
      false
    }
  }

  /**
   * Detects the current OS and CPU architecture for binary selection.
   *
   * @return Pair of (os, arch) matching GitHub Release asset names.
   * @throws RevylCliException If the platform is not supported.
   */
  private fun detectPlatform(): Pair<String, String> {
    val osName = System.getProperty("os.name").lowercase()
    val os = when {
      "mac" in osName || "darwin" in osName -> "darwin"
      "linux" in osName -> "linux"
      "windows" in osName -> "windows"
      else -> throw RevylCliException("Unsupported OS: $osName")
    }
    val archName = System.getProperty("os.arch").lowercase()
    val arch = when (archName) {
      "aarch64", "arm64" -> "arm64"
      "amd64", "x86_64" -> "amd64"
      else -> throw RevylCliException("Unsupported architecture: $archName")
    }
    return Pair(os, arch)
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
   * @return The newly created [RevylSession] parsed from CLI JSON output.
   * @throws RevylCliException If the CLI exits with a non-zero code.
   */
  fun startSession(
    platform: String,
    appUrl: String? = null,
    appLink: String? = null,
  ): RevylSession {
    val args = mutableListOf("device", "start", "--platform", platform.lowercase())
    if (!appUrl.isNullOrBlank()) {
      args += listOf("--app-url", appUrl)
    }
    if (!appLink.isNullOrBlank()) {
      args += listOf("--app-link", appLink)
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
    )
    currentSession = session
    Console.log("RevylCli: device ready (session ${session.index}, ${session.platform})")
    Console.log("  Viewer: ${session.viewerUrl}")
    return session
  }

  /**
   * Stops the active device session.
   *
   * @param index Session index to stop. Defaults to -1 (active session).
   * @throws RevylCliException If the CLI exits with a non-zero code.
   */
  fun stopSession(index: Int = -1) {
    val args = mutableListOf("device", "stop")
    if (index >= 0) args += listOf("-s", index.toString())
    runCli(args)
    currentSession = null
  }

  // ---------------------------------------------------------------------------
  // Device actions
  // ---------------------------------------------------------------------------

  /**
   * Captures a PNG screenshot and returns the raw bytes.
   *
   * @param outPath File path to write the screenshot to.
   * @return Raw PNG bytes read from the output file.
   * @throws RevylCliException If the CLI exits with a non-zero code.
   */
  fun screenshot(outPath: String = createTempScreenshotPath()): ByteArray {
    runCli(listOf("device", "screenshot", "--out", outPath))
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
   * @throws RevylCliException If the CLI exits with a non-zero code.
   */
  fun tap(x: Int, y: Int) {
    runCli(listOf("device", "tap", "--x", x.toString(), "--y", y.toString()))
  }

  /**
   * Taps a UI element identified by natural language description.
   * The CLI handles AI-powered grounding to resolve the target to
   * exact coordinates transparently.
   *
   * @param target Natural language description (e.g. "Sign In button").
   * @throws RevylCliException If the CLI exits with a non-zero code.
   */
  fun tapTarget(target: String) {
    runCli(listOf("device", "tap", "--target", target))
  }

  /**
   * Types text into an input field, optionally targeting a specific element.
   *
   * @param text The text to type.
   * @param target Optional natural language element description to tap first.
   * @param clearFirst If true, clears the field before typing.
   * @throws RevylCliException If the CLI exits with a non-zero code.
   */
  fun typeText(text: String, target: String? = null, clearFirst: Boolean = false) {
    val args = mutableListOf("device", "type", "--text", text)
    if (!target.isNullOrBlank()) args += listOf("--target", target)
    if (clearFirst) args += "--clear-first"
    runCli(args)
  }

  /**
   * Swipes in the given direction, optionally from a targeted element.
   *
   * @param direction One of "up", "down", "left", "right".
   * @param target Optional natural language element description for swipe origin.
   * @throws RevylCliException If the CLI exits with a non-zero code.
   */
  fun swipe(direction: String, target: String? = null) {
    val args = mutableListOf("device", "swipe", "--direction", direction)
    if (!target.isNullOrBlank()) args += listOf("--target", target)
    runCli(args)
  }

  /**
   * Long-presses a UI element identified by natural language description.
   *
   * @param target Natural language description of the element.
   * @throws RevylCliException If the CLI exits with a non-zero code.
   */
  fun longPress(target: String) {
    runCli(listOf("device", "long-press", "--target", target))
  }

  /**
   * Presses the Android back button.
   *
   * @throws RevylCliException If the CLI exits with a non-zero code.
   */
  fun back() {
    runCli(listOf("device", "back"))
  }

  /**
   * Sends a key press event (ENTER or BACKSPACE).
   *
   * @param key Key name: "ENTER" or "BACKSPACE".
   * @throws RevylCliException If the CLI exits with a non-zero code.
   */
  fun pressKey(key: String) {
    runCli(listOf("device", "key", "--key", key.uppercase()))
  }

  /**
   * Opens a URL or deep link on the device.
   *
   * @param url The URL or deep link to open.
   * @throws RevylCliException If the CLI exits with a non-zero code.
   */
  fun navigate(url: String) {
    runCli(listOf("device", "navigate", "--url", url))
  }

  /**
   * Clears text from the currently focused input field.
   *
   * @param target Optional natural language element description.
   * @throws RevylCliException If the CLI exits with a non-zero code.
   */
  fun clearText(target: String? = null) {
    val args = mutableListOf("device", "clear-text")
    if (!target.isNullOrBlank()) args += listOf("--target", target)
    runCli(args)
  }

  /**
   * Installs an app on the device from a public URL.
   *
   * @param appUrl Direct download URL for the .apk or .ipa.
   * @throws RevylCliException If the CLI exits with a non-zero code.
   */
  fun installApp(appUrl: String) {
    runCli(listOf("device", "install", "--app-url", appUrl))
  }

  /**
   * Launches an installed app by its bundle/package ID.
   *
   * @param bundleId The app's bundle identifier (iOS) or package name (Android).
   * @throws RevylCliException If the CLI exits with a non-zero code.
   */
  fun launchApp(bundleId: String) {
    runCli(listOf("device", "launch", "--bundle-id", bundleId))
  }

  /**
   * Navigates to the device home screen.
   *
   * @throws RevylCliException If the CLI exits with a non-zero code.
   */
  fun home() {
    runCli(listOf("device", "home"))
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
