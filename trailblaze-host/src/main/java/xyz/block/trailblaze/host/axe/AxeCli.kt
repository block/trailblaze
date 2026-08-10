package xyz.block.trailblaze.host.axe

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import xyz.block.trailblaze.util.Console

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

  /**
   * Earliest verified-good `axe` version. 1.5.2 was observed returning incomplete accessibility
   * trees on iOS 26 (missing toolbar buttons — empirically verified: 16 nodes vs 131 for the
   * same screen against 1.8.0); intermediate versions are untested, so the gate floors at the
   * earliest version verified to produce complete trees rather than risk silently driving off
   * a partial one.
   */
  internal const val MIN_VERSION = "1.8.0"

  /** Default fixed-delay settle after an interaction. */
  const val DEFAULT_SETTLE_MS: Long = 300L

  /**
   * `describe-ui` flag that also returns content rendered by another process (`WKWebView` and
   * `SFSafariViewController` page content). iOS renders web content in a separate WebContent
   * process, which the in-process accessibility walk cannot reach at all, so without this the
   * whole page is simply absent from the tree (a web view appears as an empty scroll area).
   */
  private const val WEB_CONTENT_FLAG = "--include-web-content"

  /**
   * Grid spacing for the hit-test probes that find web content. Pinned rather than left to AXe's
   * default so our tree doesn't shift under us if that default is ever retuned upstream.
   *
   * 25pt is the coarsest spacing measured to find every element on a page of ordinary web controls:
   * at 50pt the ~17pt bands between probe rows swallowed a standard-height link while the tree still
   * looked complete. Cost grows with the square of the probe count, so this is also the cheapest
   * spacing that is actually correct.
   */
  private const val WEB_CONTENT_GRID_STEP = "25"

  /**
   * Runaway guard on the probe count, not a tuning knob: a phone screen needs ~500 probes at
   * [WEB_CONTENT_GRID_STEP] and the largest iPad ~2,120, so no device Trailblaze drives reaches this
   * cap. It exists so an implausibly large screen can't turn one capture into an unbounded stall.
   *
   * Kept deliberately far above real usage because the cap truncates *spatially*: probing stops
   * partway down the screen, which would silently drop the bottom of a page rather than degrade
   * resolution evenly. Lower the grid step, never this, to trade accuracy for speed.
   */
  private const val WEB_CONTENT_MAX_POINTS = "6000"

  data class Result(val exitCode: Int, val stdout: String, val stderr: String) {
    val success: Boolean get() = exitCode == 0
  }

  /** Captures the Simulator's full accessibility tree as raw AXe JSON. */
  fun describeUi(udid: String, timeoutSeconds: Long = 10): Result =
    run(describeUiArgs(udid), timeoutSeconds)

  /**
   * Builds the `describe-ui` argv, appending the web-content descent when the installed AXe
   * supports it and the kill-switch is unset. Separated from [describeUi] so the routing decision
   * is unit-testable without spawning a process.
   */
  internal fun describeUiArgs(
    udid: String,
    webContentSupported: Boolean = supportsWebContentDescent(),
    webContentDisabled: Boolean = isWebContentDescentDisabled(),
  ): List<String> {
    val base = listOf(axeBin, "describe-ui", "--udid", udid)
    if (!webContentSupported || webContentDisabled) return base
    return base + listOf(
      WEB_CONTENT_FLAG,
      "--web-content-grid-step", WEB_CONTENT_GRID_STEP,
      "--web-content-max-points", WEB_CONTENT_MAX_POINTS,
    )
  }

  /**
   * Reads the `TRAILBLAZE_DISABLE_AXE_WEB_CONTENT` kill-switch on every capture (not memoized) so
   * it can be flipped against a running daemon. Set it to `1`/`true` to restore the exact argv
   * Trailblaze emitted before the descent existed.
   */
  internal fun isWebContentDescentDisabled(): Boolean =
    System.getenv("TRAILBLAZE_DISABLE_AXE_WEB_CONTENT")?.trim()?.lowercase() in setOf("1", "true")

  /**
   * Whether the installed `axe` accepts [WEB_CONTENT_FLAG], detected by looking for the flag in
   * `describe-ui --help`.
   *
   * Deliberately a capability probe rather than another version floor: the descent has not shipped
   * in a release yet, so a version gate would have to guess the release number and would silently
   * mis-fire if that guess were wrong in either direction. Asking the binary what it supports is
   * exact, and an older `axe` (or a build where the flag was renamed) correctly reads as
   * unsupported and keeps the previous behavior.
   *
   * Memoized for the JVM lifetime for the same reason as [isAvailable]: this is consulted on
   * every screen capture, and AXe isn't upgraded mid-session. Restart the daemon after upgrading AXe.
   */
  internal fun supportsWebContentDescent(): Boolean =
    cachedWebContentSupport ?: computeWebContentSupport().also { cachedWebContentSupport = it }

  @Volatile private var cachedWebContentSupport: Boolean? = null

  private fun computeWebContentSupport(): Boolean {
    if (!isAvailable()) return false
    val help = run(listOf(axeBin, "describe-ui", "--help"), timeoutSeconds = 5)
    if (!help.success) return false
    val supported = (help.stdout + help.stderr).contains(WEB_CONTENT_FLAG)
    if (!supported) {
      Console.log(
        "[AxeCli] this axe build has no $WEB_CONTENT_FLAG, so WKWebView and SFSafariViewController " +
          "page content will be missing from the accessibility tree. Run: brew upgrade axe",
      )
    }
    return supported
  }

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
   * Reports whether the AXe binary is available AND meets [MIN_VERSION]. For absolute paths,
   * checks executability directly; for bare names (e.g. `AXE_BIN=axe`) relies on PATH. Either
   * way, an executable binary below [MIN_VERSION] still reports unavailable (see
   * [computeAvailability]) since it drives off incomplete accessibility trees.
   *
   * Result is memoized for the JVM lifetime — AXe isn't going to be installed/uninstalled or
   * upgraded mid-session, and this is called on every device-list refresh + every connect. A
   * user who installs or upgrades AXe mid-daemon can restart the daemon to pick it up.
   */
  fun isAvailable(): Boolean = cachedAvailability ?: computeAvailability().also { cachedAvailability = it }

  @Volatile private var cachedAvailability: Boolean? = null

  private fun computeAvailability(): Boolean {
    if (File(axeBin).isAbsolute && !File(axeBin).canExecute()) {
      return false
    }
    val versionOutput = probeVersionOutput() ?: return false
    val found = parseAxeVersion(versionOutput)
    if (found == null || compareVersions(found, MIN_VERSION) < 0) {
      Console.log(
        "[AxeCli] axe version too old (found ${found ?: "unrecognized"}, requires >= $MIN_VERSION) " +
          "— older versions return incomplete accessibility trees. Run: brew upgrade axe",
      )
      return false
    }
    return true
  }

  private fun probeVersionOutput(): String? = try {
    val proc = ProcessBuilder(axeBin, "--version").redirectErrorStream(true).start()
    val finished = proc.waitFor(2, TimeUnit.SECONDS)
    if (!finished) {
      proc.destroyForcibly()
      null
    } else if (proc.exitValue() != 0) {
      null
    } else {
      proc.inputStream.bufferedReader().readText()
    }
  } catch (_: Exception) {
    null
  }

  /**
   * Extracts a dotted version number (e.g. "1.8.0") from raw `axe --version` output, tolerating
   * a leading "v" and surrounding text (e.g. "axe version v1.8.0"). Null when no version-shaped
   * token is found — callers treat that as too old to trust.
   */
  internal fun parseAxeVersion(rawOutput: String): String? =
    Regex("""v?(\d+(?:\.\d+){1,2})""").find(rawOutput)?.groupValues?.get(1)

  /**
   * Component-wise comparison of two dotted version strings, tolerating a 2- or 3-segment
   * mismatch by padding the shorter one with zeros. Negative if [a] < [b], zero if equal,
   * positive if [a] > [b].
   */
  internal fun compareVersions(a: String, b: String): Int {
    val aParts = a.split(".").map { it.toIntOrNull() ?: 0 }
    val bParts = b.split(".").map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(aParts.size, bParts.size)) {
      val diff = aParts.getOrElse(i) { 0 } - bParts.getOrElse(i) { 0 }
      if (diff != 0) return diff
    }
    return 0
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
