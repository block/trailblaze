package xyz.block.trailblaze.host.axe

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Thin wrapper around the [AXe CLI](https://github.com/cameroncooke/AXe).
 *
 * Shells out to the `axe` binary and captures stdout/stderr. This is the POC path for
 * driving iOS Simulators directly through Apple's Accessibility APIs instead of going
 * through Maestro → XCUITest. All methods assume AXe is installed and on the PATH
 * (the POC installs via `brew install cameroncooke/axe/axe`).
 *
 * AXe is fire-and-forget — it dispatches HID events and returns immediately without
 * waiting for the UI to settle. The POC uses a fixed post-action delay instead of
 * polling. Tree-hash settling is a natural follow-up once this path graduates from
 * POC status.
 */
object AxeCli {

  private const val AXE_BIN_DEFAULT = "/opt/homebrew/bin/axe"

  private val axeBin: String = System.getenv("AXE_BIN")?.takeIf { it.isNotBlank() } ?: AXE_BIN_DEFAULT

  /** Default fixed-delay settle after an interaction. */
  const val DEFAULT_SETTLE_MS: Long = 300L

  data class Result(val exitCode: Int, val stdout: String, val stderr: String) {
    val success: Boolean get() = exitCode == 0
  }

  /** Captures the Simulator's full accessibility tree as raw AXe JSON. */
  fun describeUi(udid: String, timeoutSeconds: Long = 10): Result =
    run(listOf(axeBin, "describe-ui", "--udid", udid), timeoutSeconds)

  /** Captures a screenshot. Returns the PNG path (either [outputPath] or AXe's auto-name on stdout). */
  fun screenshot(udid: String, outputPath: String? = null, timeoutSeconds: Long = 10): Result {
    val args = mutableListOf(axeBin, "screenshot", "--udid", udid)
    if (outputPath != null) {
      args += listOf("--output", outputPath)
    }
    return run(args, timeoutSeconds)
  }

  /** Tap at screen coordinates. POC uses a fixed-delay settle after dispatch. */
  fun tapXy(
    udid: String,
    x: Int,
    y: Int,
    preDelaySeconds: Double = 0.0,
    postDelaySeconds: Double = DEFAULT_SETTLE_MS / 1000.0,
    timeoutSeconds: Long = 10,
  ): Result {
    val args = listOf(
      axeBin, "tap",
      "-x", x.toString(),
      "-y", y.toString(),
      "--pre-delay", preDelaySeconds.toString(),
      "--post-delay", postDelaySeconds.toString(),
      "--udid", udid,
    )
    return run(args, timeoutSeconds)
  }

  /** Tap by accessibility identifier (set by the app as `accessibilityIdentifier`). */
  fun tapById(
    udid: String,
    id: String,
    postDelaySeconds: Double = DEFAULT_SETTLE_MS / 1000.0,
    timeoutSeconds: Long = 10,
  ): Result = run(
    listOf(
      axeBin, "tap",
      "--id", id,
      "--post-delay", postDelaySeconds.toString(),
      "--udid", udid,
    ),
    timeoutSeconds,
  )

  /** Tap by accessibility label (AXLabel). */
  fun tapByLabel(
    udid: String,
    label: String,
    postDelaySeconds: Double = DEFAULT_SETTLE_MS / 1000.0,
    timeoutSeconds: Long = 10,
  ): Result = run(
    listOf(
      axeBin, "tap",
      "--label", label,
      "--post-delay", postDelaySeconds.toString(),
      "--udid", udid,
    ),
    timeoutSeconds,
  )

  /**
   * Presses and holds a touch at ([x], [y]) for [durationMs] before releasing — the real
   * iOS long-press gesture. Uses AXe's `touch` primitive (`--down` → sleep → `--up`) rather
   * than a tap with post-delay, which just waits after a tap and does NOT trigger
   * long-press-specific UI (context menus, drag handles, etc.).
   */
  fun touchHold(
    udid: String,
    x: Int,
    y: Int,
    durationMs: Long,
    timeoutSeconds: Long = 10,
  ): Result = run(
    listOf(
      axeBin, "touch",
      "-x", x.toString(),
      "-y", y.toString(),
      "--down",
      "--up",
      "--delay", (durationMs / 1000.0).toString(),
      "--udid", udid,
    ),
    timeoutSeconds,
  )

  /** Swipe from (startX, startY) to (endX, endY). */
  fun swipe(
    udid: String,
    startX: Int,
    startY: Int,
    endX: Int,
    endY: Int,
    durationMs: Long = 400L,
    timeoutSeconds: Long = 10,
  ): Result = run(
    listOf(
      axeBin, "swipe",
      "--start-x", startX.toString(),
      "--start-y", startY.toString(),
      "--end-x", endX.toString(),
      "--end-y", endY.toString(),
      "--duration", (durationMs / 1000.0).toString(),
      "--udid", udid,
    ),
    timeoutSeconds,
  )

  /** Named gesture preset (scroll-up/scroll-down/swipe-from-left-edge/etc.). */
  fun gesture(udid: String, preset: String, timeoutSeconds: Long = 10): Result =
    run(listOf(axeBin, "gesture", preset, "--udid", udid), timeoutSeconds)

  /**
   * Types [text] into the focused field via AXe's HID keyboard. Pipes text through stdin
   * so we don't have to worry about shell escaping, then uses the same concurrent-drain
   * pattern as [run] to avoid the pipe-buffer deadlock `describe-ui` hit (though `axe type`
   * output is typically trivial, consistency is cheap).
   */
  fun type(udid: String, text: String, timeoutSeconds: Long = 30): Result {
    val proc = ProcessBuilder(axeBin, "type", "--stdin", "--udid", udid)
      .redirectErrorStream(false)
      .start()
    val drainer = Executors.newFixedThreadPool(2)
    val stdoutFuture = drainer.submit<String> { proc.inputStream.bufferedReader().readText() }
    val stderrFuture = drainer.submit<String> { proc.errorStream.bufferedReader().readText() }
    drainer.shutdown()
    try {
      proc.outputStream.use { it.write(text.toByteArray()) }
      val finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)
      if (!finished) {
        proc.destroyForcibly()
        return Result(-1, "", "axe type timed out after ${timeoutSeconds}s")
      }
      val stdout = stdoutFuture.get(5, TimeUnit.SECONDS)
      val stderr = stderrFuture.get(5, TimeUnit.SECONDS)
      return Result(exitCode = proc.exitValue(), stdout = stdout, stderr = stderr)
    } finally {
      drainer.shutdownNow()
    }
  }

  /** Presses a hardware button (home, lock, side-button, siri, apple-pay). */
  fun button(udid: String, button: String, timeoutSeconds: Long = 10): Result =
    run(listOf(axeBin, "button", button, "--udid", udid), timeoutSeconds)

  /** Presses a single HID keycode (e.g. 40 = Enter, 42 = Backspace). */
  fun key(udid: String, keycode: Int, timeoutSeconds: Long = 10): Result =
    run(listOf(axeBin, "key", keycode.toString(), "--udid", udid), timeoutSeconds)

  /**
   * Reports whether the AXe binary is available. For absolute paths, checks executability
   * directly; for bare names (e.g. `AXE_BIN=axe`) relies on PATH by attempting a
   * `--version` probe so we don't return false-negative when the binary is on PATH but not
   * at the hard-coded default location.
   *
   * Result is memoized for the JVM lifetime — AXe isn't going to be installed/uninstalled
   * mid-session, and this is called on every device-list refresh + every connect. A user
   * who installs AXe mid-daemon can restart the daemon to pick it up.
   */
  fun isAvailable(): Boolean = cachedAvailability ?: computeAvailability().also { cachedAvailability = it }

  @Volatile private var cachedAvailability: Boolean? = null

  private fun computeAvailability(): Boolean {
    if (File(axeBin).isAbsolute) {
      return File(axeBin).canExecute()
    }
    return try {
      val proc = ProcessBuilder(axeBin, "--version").redirectErrorStream(true).start()
      val finished = proc.waitFor(2, TimeUnit.SECONDS)
      if (!finished) {
        proc.destroyForcibly()
        false
      } else {
        proc.exitValue() == 0
      }
    } catch (_: Exception) {
      false
    }
  }

  /**
   * Drains stdout + stderr concurrently with the process wait. `describe-ui` on a complex UI
   * can emit well over the OS pipe buffer (~64 KB on macOS); if we called `waitFor` before
   * reading, the child would block on pipe backpressure and we'd time out spuriously.
   */
  private fun run(args: List<String>, timeoutSeconds: Long): Result {
    val proc = ProcessBuilder(args)
      .redirectErrorStream(false)
      .start()
    val drainer = Executors.newFixedThreadPool(2)
    val stdoutFuture = drainer.submit<String> { proc.inputStream.bufferedReader().readText() }
    val stderrFuture = drainer.submit<String> { proc.errorStream.bufferedReader().readText() }
    drainer.shutdown()
    try {
      val finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)
      if (!finished) {
        proc.destroyForcibly()
        return Result(exitCode = -1, stdout = "", stderr = "axe command timed out after ${timeoutSeconds}s")
      }
      val stdout = stdoutFuture.get(5, TimeUnit.SECONDS)
      val stderr = stderrFuture.get(5, TimeUnit.SECONDS)
      return Result(exitCode = proc.exitValue(), stdout = stdout, stderr = stderr)
    } finally {
      drainer.shutdownNow()
    }
  }
}
