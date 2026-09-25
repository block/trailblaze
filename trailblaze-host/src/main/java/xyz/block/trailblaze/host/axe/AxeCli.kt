package xyz.block.trailblaze.host.axe

import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.IosHostSimctlUtils

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

  private const val LEFT_COMMAND_KEYCODE = 227
  private const val V_KEYCODE = 25
  private const val PROCESS_CLEANUP_MAX_MS = 250L
  private val keypadDigitHidCodes = mapOf(
    '0' to 98,
    '1' to 89,
    '2' to 90,
    '3' to 91,
    '4' to 92,
    '5' to 93,
    '6' to 94,
    '7' to 95,
    '8' to 96,
    '9' to 97,
    '/' to 84,
  )

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
   * Inserts [text] into the focused field without layout-sensitive character remapping.
   *
   * AXe's `type` command converts characters to US-keyboard HID chords. iOS then interprets those
   * physical chords through the active hardware-keyboard layout, so symbols change under localized
   * layouts (`@` becomes `"` on Spanish, for example), and characters outside the US layout cannot
   * be entered at all. Staging the UTF-8 text with `simctl pbcopy` and sending Cmd+V preserves the
   * string independently of the active keyboard language. Numeric strings (including `/`
   * separators) use USB numeric-
   * keypad usages instead, preserving support for protected payment fields that disable paste.
   *
   * The staged text intentionally remains on the simulator pasteboard. AXe only tells us that the
   * Cmd+V event was dispatched, not that UIKit consumed it; restoring after a fixed delay can make
   * the field receive the old clipboard value while this action reports success. `simctl`
   * `pbcopy`/`pbpaste` also cannot round-trip rich pasteboard items. Preserving deterministic text
   * entry is therefore preferable to claiming unsafe or lossy clipboard restoration.
   * As with every AXe HID action, success means the input event was dispatched; apps that reject
   * paste for a non-numeric field need a follow-up field assertion because AXe does not expose a
   * focused-field value or set-value API.
   */
  fun type(udid: String, text: String, timeoutSeconds: Long = 30): Result {
    if (text.isEmpty()) return Result(0, "", "")

    keypadDigitKeycodes(text)?.let { keycodes ->
      return typeViaKeypad(udid, keycodes, timeoutSeconds)
    }

    val budget = TimeoutBudget(timeoutSeconds)
    val safetyReserveMillis = DEFAULT_SETTLE_MS + PROCESS_CLEANUP_MAX_MS
    return withPasteboardLock(budget, safetyReserveMillis) {
      typeViaPasteboard(
        udid = udid,
        text = text,
        writePasteboard = { deviceId, value ->
          budget.run("simctl pbcopy", safetyReserveMillis) { remaining ->
            writePasteboard(deviceId, value, remaining)
          }
        },
        paste = { deviceId ->
          budget.run("axe key-combo", safetyReserveMillis) { remaining ->
            pasteFromPasteboard(deviceId, remaining)
          }
        },
        waitForSettle = ::settleAfterPaste,
      )
    }
  }

  /** Cancellation must not release the clipboard lock while a dispatched paste is still pending. */
  private fun settleAfterPaste() {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DEFAULT_SETTLE_MS)
    var interrupted = Thread.interrupted()
    while (true) {
      val remaining = deadline - System.nanoTime()
      if (remaining <= 0) break
      try {
        TimeUnit.NANOSECONDS.sleep(remaining)
      } catch (_: InterruptedException) {
        interrupted = true
      }
    }
    if (interrupted) throw InterruptedException("Paste settling was interrupted")
  }

  /**
   * Payment fields such as card expiration reject paste but accept hardware-keyboard digits.
   * Numeric-keypad HID usages are layout-independent, unlike AXe's US number-row mapping (`1`
   * becomes `&` on French AZERTY), so this path works for both protected and paste-capable fields.
   */
  internal fun keypadDigitKeycodes(text: String): List<Int>? =
    text.takeIf { it.isNotEmpty() }?.map { keypadDigitHidCodes[it] ?: return null }

  private fun typeViaKeypad(udid: String, keycodes: List<Int>, timeoutSeconds: Long): Result =
    run(
      typeViaKeypadArgs(udid, keycodes),
      timeoutSeconds,
      timeoutDescription = "axe key-sequence",
    )

  internal fun typeViaKeypadArgs(udid: String, keycodes: List<Int>): List<String> =
    listOf(axeBin, "key-sequence", "--keycodes", keycodes.joinToString(","), "--udid", udid)

  internal fun withPasteboardLock(
    budget: TimeoutBudget,
    reserveMillis: Long = 0,
    operation: () -> Result,
  ): Result {
    val remainingMillis = budget.remainingMillis(reserveMillis)
    if (remainingMillis <= 0) return budget.timeoutResult("pasteboard lock")
    return IosHostSimctlUtils.withPasteboardLock(
      timeoutMillis = remainingMillis,
      onTimeout = { budget.timeoutResult("pasteboard lock") },
      action = operation,
    )
  }

  internal fun typeViaPasteboard(
    udid: String,
    text: String,
    writePasteboard: (String, String) -> Result,
    paste: (String) -> Result,
    waitForSettle: () -> Unit = {},
  ): Result {
    val stageResult = writePasteboard(udid, text)
    if (!stageResult.success) return stageResult

    // A timed-out AXe process may already have posted Cmd+V, so settling belongs to every attempt.
    val pasteAttempt = runCatching { paste(udid) }
    val settleAttempt = runCatching { waitForSettle() }
    val pasteFailure = pasteAttempt.exceptionOrNull()
    val settleFailure = settleAttempt.exceptionOrNull()

    if (pasteFailure != null) {
      settleFailure?.let(pasteFailure::addSuppressed)
      if (pasteFailure is InterruptedException || settleFailure is InterruptedException) {
        Thread.currentThread().interrupt()
      }
      throw pasteFailure
    }
    if (settleFailure is InterruptedException) {
      Thread.currentThread().interrupt()
      return Result(
        -1,
        pasteAttempt.getOrThrow().stdout,
        "Interrupted while waiting for the paste event to settle",
      )
    }
    if (settleFailure != null) throw settleFailure
    return pasteAttempt.getOrThrow()
  }

  private fun writePasteboard(udid: String, text: String, timeoutMillis: Long): Result =
    runWithTimeoutMillis(
      writePasteboardArgs(udid),
      timeoutMillis,
      stdin = text,
      timeoutDescription = "simctl pbcopy",
    )

  internal fun writePasteboardArgs(udid: String): List<String> =
    listOf("xcrun", "simctl", "pbcopy", udid)

  private fun pasteFromPasteboard(udid: String, timeoutMillis: Long): Result = runWithTimeoutMillis(
    pasteFromPasteboardArgs(udid),
    timeoutMillis,
  )

  internal fun pasteFromPasteboardArgs(udid: String): List<String> =
    listOf(
      axeBin,
      "key-combo",
      "--modifiers", LEFT_COMMAND_KEYCODE.toString(),
      "--key", V_KEYCODE.toString(),
      "--udid", udid,
    )

  internal class TimeoutBudget(
    private val timeoutSeconds: Long,
    private val nanoTime: () -> Long = System::nanoTime,
  ) {
    private val deadlineNanos = nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)

    internal fun remainingMillis(reserveMillis: Long = 0): Long {
      val remainingNanos = deadlineNanos - nanoTime() - TimeUnit.MILLISECONDS.toNanos(reserveMillis)
      if (remainingNanos <= 0) return 0
      val nanosPerMilli = TimeUnit.MILLISECONDS.toNanos(1)
      return (remainingNanos + nanosPerMilli - 1) / nanosPerMilli
    }

    internal fun timeoutResult(description: String): Result =
      Result(-1, "", "$description exceeded the ${timeoutSeconds}s inputText timeout")

    fun run(
      description: String,
      reserveMillis: Long = 0,
      operation: (Long) -> Result,
    ): Result {
      val remainingMillis = remainingMillis(reserveMillis)
      if (remainingMillis <= 0) return timeoutResult(description)
      val result = operation(remainingMillis)
      return if (remainingMillis(reserveMillis) <= 0) timeoutResult(description) else result
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
   *
   * [stdin] is optional for commands such as `simctl pbcopy`; keeping those payloads off argv
   * preserves whitespace/symbols and avoids exposing typed values in the process list.
   */
  private fun run(
    args: List<String>,
    timeoutSeconds: Long,
    stdin: String? = null,
    timeoutDescription: String = "axe command",
  ): Result = runWithTimeoutMillis(
    args = args,
    timeoutMillis = TimeUnit.SECONDS.toMillis(timeoutSeconds),
    stdin = stdin,
    timeoutDescription = timeoutDescription,
  )

  internal fun runWithTimeoutMillis(
    args: List<String>,
    timeoutMillis: Long,
    stdin: String? = null,
    timeoutDescription: String = "axe command",
  ): Result {
    if (timeoutMillis <= 0) {
      return Result(-1, "", "$timeoutDescription timed out after ${timeoutMillis}ms")
    }

    val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    val cleanupDeadlineNanos = deadlineNanos + TimeUnit.MILLISECONDS.toNanos(PROCESS_CLEANUP_MAX_MS)
    val proc = ProcessBuilder(args)
      .redirectErrorStream(false)
      .start()
    val executor = Executors.newFixedThreadPool(if (stdin == null) 2 else 3) { task ->
      Thread(task, "axe-process-io").apply { isDaemon = true }
    }
    val stdoutFuture = executor.submit<String> { proc.inputStream.bufferedReader(Charsets.UTF_8).readText() }
    val stderrFuture = executor.submit<String> { proc.errorStream.bufferedReader(Charsets.UTF_8).readText() }
    val stdinFuture = stdin?.let { value ->
      executor.submit<Unit> {
        proc.outputStream.use { it.write(value.toByteArray(Charsets.UTF_8)) }
      }
    }
    executor.shutdown()

    try {
      val finished = proc.waitFor(remainingMillis(deadlineNanos), TimeUnit.MILLISECONDS)
      if (!finished) {
        terminateProcess(proc, cleanupDeadlineNanos)
        return Result(-1, "", "$timeoutDescription timed out after ${timeoutMillis}ms")
      }

      val stdout = await(stdoutFuture, deadlineNanos)
      val stderr = await(stderrFuture, deadlineNanos)
      stdinFuture?.let { await(it, deadlineNanos) }
      return Result(exitCode = proc.exitValue(), stdout = stdout, stderr = stderr)
    } catch (_: TimeoutException) {
      terminateProcess(proc, cleanupDeadlineNanos)
      return Result(-1, "", "$timeoutDescription timed out after ${timeoutMillis}ms")
    } catch (e: ExecutionException) {
      terminateProcess(proc, cleanupDeadlineNanos)
      val cause = e.cause ?: e
      return Result(-1, "", "$timeoutDescription failed: ${cause.message ?: cause.javaClass.simpleName}")
    } catch (_: InterruptedException) {
      terminateProcess(proc, cleanupDeadlineNanos)
      Thread.currentThread().interrupt()
      return Result(-1, "", "$timeoutDescription was interrupted")
    } finally {
      executor.shutdownNow()
    }
  }

  private fun remainingMillis(deadlineNanos: Long): Long {
    val remainingNanos = deadlineNanos - System.nanoTime()
    if (remainingNanos <= 0) return 0
    val nanosPerMilli = TimeUnit.MILLISECONDS.toNanos(1)
    return (remainingNanos + nanosPerMilli - 1) / nanosPerMilli
  }

  private fun <T> await(future: Future<T>, deadlineNanos: Long): T {
    val remainingMillis = remainingMillis(deadlineNanos)
    if (remainingMillis <= 0) throw TimeoutException()
    return future.get(remainingMillis, TimeUnit.MILLISECONDS)
  }

  private fun terminateProcess(proc: Process, deadlineNanos: Long) {
    // Closing stdin before killing can wait on the blocked writer's stream monitor.
    // Kill first so pipe backpressure cannot defeat the process deadline.
    val handles = proc.descendants().toList().asReversed() + proc.toHandle()
    handles.filter { it.isAlive }.forEach { it.destroyForcibly() }

    var interrupted = false
    for (handle in handles) {
      if (!handle.isAlive) continue
      val remainingMillis = minOf(PROCESS_CLEANUP_MAX_MS, remainingMillis(deadlineNanos))
      if (remainingMillis <= 0) break
      try {
        handle.onExit().get(remainingMillis, TimeUnit.MILLISECONDS)
      } catch (_: ExecutionException) {
        // The process is already being discarded; a failed exit future has no useful result.
      } catch (_: TimeoutException) {
        break
      } catch (_: InterruptedException) {
        interrupted = true
        break
      }
    }
    handles.filter { it.isAlive }.forEach { it.destroyForcibly() }
    if (interrupted) Thread.currentThread().interrupt()
  }
}
