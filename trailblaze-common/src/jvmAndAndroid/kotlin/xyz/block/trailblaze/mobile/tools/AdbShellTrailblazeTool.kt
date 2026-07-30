package xyz.block.trailblaze.mobile.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.android.tools.shellEscape
import xyz.block.trailblaze.device.AndroidDeviceCommandExecutor
import xyz.block.trailblaze.device.wrapShellPipelineForTransport
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * Executes a shell command in the Android device's shell environment, regardless of how
 * the daemon reaches it.
 *
 * This is the dual-mode counterpart to host-only [xyz.block.trailblaze.toolcalls.commands.ExecTrailblazeTool]:
 *
 *  - **Host-side dispatch** — [xyz.block.trailblaze.device.AndroidDeviceCommandExecutor.executeShellCommand]'s
 *    JVM `actual` routes the command through the dadb wire protocol (no `adb` binary
 *    subprocess). Same path the rest of the host-side framework already uses.
 *  - **On-device dispatch** — the Android `actual` runs the command directly inside the
 *    instrumentation process via `UiAutomation.executeShellCommand`. No host round-trip,
 *    no adb wire involved (the `adb` prefix in this tool's name reflects the *colloquial*
 *    `adb shell <cmd>` semantics, not the transport).
 *
 * Same contract either way: stdout returned as the success message, **non-zero exit /
 * I/O failure surfaced as [TrailblazeToolResult.Error.ExceptionThrown]**. Authors compose
 * this tool from `.ts` scripted-tool bodies and the same composition works whether the
 * tool is dispatched on the daemon JVM or on the device's QuickJS bundle path.
 *
 * ### Transport-aware dispatch (native shell vs. trampolined shell)
 *
 * The two transports are NOT shell-equivalent, and this tool dispatches differently for each based
 * on [AndroidDeviceCommandExecutor.usesShellInterpreter] — but both routes end in a real device-side
 * `sh` evaluating the same shell-escaped command + `$?` exit sentinel, so the fail-loud contract is
 * identical:
 *
 *  - **Host (dadb → `adbd`): has a shell.** `adbd` runs the command line via `sh -c`, so the
 *    sentinel-wrapped shell string is sent as-is.
 *  - **On-device (`UiAutomationConnection.executeShellCommand` → [Runtime.exec]): NO shell of its
 *    own.** The string is whitespace-split and exec'd directly, so the sentinel-wrapped shell
 *    string cannot be sent as-is — quoting and `$?` would ride along as literal characters. Instead
 *    it is base64-packed into a single whitespace-free `sh -c` token via the shared
 *    [wrapShellPipelineForTransport] trampoline ([buildShellTrampolineCommand]); the device-side
 *    `sh` decodes and evaluates it, restoring both shell semantics (whitespace-bearing tokens,
 *    which the pre-trampoline raw-argv dispatch had to reject) and the exit sentinel. Before this,
 *    the raw-argv dispatch had no exit-code channel at all, so a failing command silently reported
 *    Success — the classic false green: a session-seeding `cp` that failed left the app launching
 *    signed-out while the trail marched on.
 *
 * **Timeout.** The on-device transport's exec runs in the separate UiAutomation process, where a
 * failure "cannot cross the Binder" — a wedged exec leaves the caller blocked on the result pipe.
 * The on-device path therefore bounds each dispatch with [ON_DEVICE_SHELL_TIMEOUT_MS] on an
 * interruptible IO dispatcher, so a wedged command fails fast rather than hanging until the
 * on-device RPC cap ([xyz.block.trailblaze.llm.OnDeviceRpcTimeouts.HANDLER_AWAIT_CAP_MS], 15 min)
 * or the session inactivity watchdog (~13 min). The host path is unbounded here but has its own
 * `TRAILBLAZE_ADB_TIMEOUT_MS` env var (see `CLAUDE.md` "ADB Configuration"). Both transports return
 * the full stdout buffered in memory — no streaming — so commands with very large output (e.g.
 * `dumpsys`, `logcat -d`) are bounded by JVM heap on whichever side runs the actual.
 *
 * Marked `requiresHost = false` so the on-device runner registers it alongside the host
 * one. A scripted tool that composes only `android_adbShell` and other dual-mode tools (e.g.
 * [AndroidSendBroadcastTrailblazeTool]) does **not** need `requiresHost: true` on its
 * descriptor — both ends of the matrix can serve the call.
 *
 * ### Exit-code detection via sentinel echo
 *
 * Applies to BOTH transports: the host transport evaluates the sentinel in `adbd`'s `sh -c`, the
 * on-device transport in the trampolined `sh` (see the dispatch section).
 *
 * The underlying [xyz.block.trailblaze.device.AndroidDeviceCommandExecutor.executeShellCommand]
 * returns only the combined stdout — no exit-code channel. To make scripted-tool composition
 * fail-loud on non-zero exits (so `client.callTool("android_adbShell", { command: ["am", "force-stop",
 * "bogus.pkg"] })` raises `isError` instead of silently succeeding with `Error: package
 * 'bogus.pkg' not installed` in stdout), the implementation appends `; echo
 * __TBZ_ADBSHELL_EXIT__$?` to the joined command, runs the wrapped command via the
 * executor, and parses the sentinel line out of the trailing output.
 *
 * Trade-offs documented for future maintainers:
 *  - **The sentinel uses `;`, not `&&`.** `; echo $?` runs unconditionally; `&& echo $?`
 *    would skip on non-zero exits and we'd lose the exit code. The `;` form gives us
 *    `$?` of the user's last statement regardless of success.
 *  - **Output of the user command can theoretically contain a literal
 *    `__TBZ_ADBSHELL_EXIT__N` line.** We anchor the regex to `\s*$` so only a trailing
 *    occurrence on the last line counts; an internal occurrence is treated as part of
 *    the command's stdout. This trades a vanishingly rare false-positive (a command
 *    whose final line is a stray `__TBZ_ADBSHELL_EXIT__123$`) for the right behaviour
 *    on every realistic input.
 *  - **If the sentinel line is missing entirely** (the user's command did `exec` and
 *    replaced the shell, or `kill -9 $$`'d, or the shell was killed mid-command), we
 *    treat that as exit code `-1` and surface an error — better to fail loud than to
 *    silently report Success when we can't actually tell.
 *
 * ### Why this isn't [xyz.block.trailblaze.toolcalls.commands.ExecTrailblazeTool]
 *
 * `exec` runs a process in the *host JVM* environment — fundamentally host-bound, useful
 * for host-side scripts (peripheral activation, build steps, anything in the dev
 * machine's PATH). `android_adbShell` runs in the *device's shell* — `pm`, `am`, `setprop`,
 * `dumpsys`, `input keyevent`, `getprop`. The two solve different problems and live on
 * different sides of the host/device boundary.
 *
 * ### Command shape: `List<String>` (argv-shaped, injection-safe)
 *
 * The underlying `executeShellCommand` contract is a single shell string evaluated by
 * the device's shell (`sh -c <cmd>` semantically) — there is no argv-form at the device
 * boundary. We expose [command] as `List<String>` (argv-shaped) and shell-quote-escape
 * each element via the shared [xyz.block.trailblaze.android.tools.shellEscape] helper
 * before joining with spaces and handing to the device shell. Inside the resulting
 * single-quote wrappers every shell metacharacter (`$`, `` ` ``, `;`, `&&`, newlines,
 * `*`, `~`) is literal, so callers can safely interpolate untrusted parameters as
 * separate list elements without writing their own escape logic.
 *
 * Element 0 is the program (or builtin like `am`, `pm`, `setprop`); subsequent elements
 * are its arguments. Mirrors the shape of `java.lang.ProcessBuilder.command(List<String>)`
 * and the recently-migrated `McpServerConfig.command: List<String>` (see PR #2344) —
 * one mental model across all tool configs in the framework: anywhere you see
 * `command:` it's a `List<String>`.
 *
 * Note: this is argv-*shaped*, not argv-*native* — the device shell has no argv entry
 * point. The join-and-quote happens at the boundary, then `sh -c <joined>` runs as
 * usual. Safety lives in the shell-escape, not in the field type.
 */
@Serializable
@TrailblazeToolClass(
  name = "android_adbShell",
  surfaceToLlm = false,
  isRecordable = false,
)
@LLMDescription("Executes a shell command in the Android device's shell environment. Returns combined stdout/stderr.")
data class AdbShellTrailblazeTool(
  /**
   * Argv-shaped command. Element 0 is the program (or shell builtin like `am`, `pm`,
   * `setprop`, `dumpsys`); subsequent elements are its arguments. Each element is
   * single-quote-wrapped via the shared [xyz.block.trailblaze.android.tools.shellEscape]
   * helper (POSIX `'\''` escape for embedded single quotes) and joined with spaces
   * before handing to the device shell. Inside the wrapping quotes every shell
   * metacharacter (`$`, `` ` ``, `;`, `&&`, newlines, `*`, `~`) is literal, so callers
   * can safely interpolate untrusted parameters as separate list elements without
   * writing their own escape logic.
   *
   * Must be non-empty (enforced by the `init` block). The empty case is rejected at
   * construction time — the device's `sh -c ""` would succeed silently, which is
   * rarely what the author intended.
   *
   * Naming and shape match `java.lang.ProcessBuilder.command(List<String>)` and
   * `McpServerConfig.command: List<String>` — one mental model across the framework.
   */
  val command: List<String>,
  /**
   * Optional Android package id to run the command as via `run-as <appId>`. When set,
   * the command runs with the target app's UID — useful for reading/writing files in
   * the app's private data directory, querying its preferences, etc.
   *
   * **Requires the target app's APK to be marked `android:debuggable="true"`** in its
   * manifest. `run-as` is gated on debuggable APKs by the Android platform; release
   * builds will fail with `run-as: package not debuggable`. Root is **not** required.
   *
   * The package name format is validated before invocation — any value containing shell
   * metacharacters (spaces, `;`, `&`, `|`, quotes, backticks) is rejected because it
   * would smuggle through the `run-as` shell wrapper. See
   * [xyz.block.trailblaze.device.validateRunAsArgs] for the exact contract.
   */
  val runAs: String? = null,
) : ExecutableTrailblazeTool {

  init {
    // Argv-form is structurally injection-safe but a zero-element list is still
    // degenerate (would produce an empty `sh -c ""` that succeeds silently — almost
    // certainly an authoring slip rather than intent). Reject at construction so the
    // failure is visible at the call site, not at execute-time.
    require(command.isNotEmpty()) {
      "AdbShellTrailblazeTool requires a non-empty `command:` list (got 0 elements)"
    }
  }

  override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult {
    if (toolExecutionContext.trailblazeDeviceInfo.platform != TrailblazeDevicePlatform.ANDROID) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "android_adbShell is only supported on Android devices " +
          "(got platform: ${toolExecutionContext.trailblazeDeviceInfo.platform}).",
      )
    }
    val effectiveCommand = joinCommandAsShellString(command)
    val executor = toolExecutionContext.androidDeviceCommandExecutor
      ?: return TrailblazeToolResult.Error.ExceptionThrown(
        // Include `effectiveCommand` so the missing-executor error explains *what*
        // would have run — important for debugging mis-wired test contexts, and also
        // pins via test that `execute()` is using the argv-derived string and not
        // some other code path.
        errorMessage = "AndroidDeviceCommandExecutor is not provided " +
          "(would have run: '${effectiveCommand.take(200)}')",
        command = this,
      )
    return try {
      // Pick the path by transport: a shell-backed transport (host/dadb→adbd) can take the
      // shell-escaped string + `$?` exit sentinel directly; a shell-less transport (on-device
      // UiAutomation→Runtime.exec) gets the same sentinel-wrapped shell string base64-packed into
      // a single `sh -c` token (the wrapShellPipelineForTransport trampoline), so both routes end
      // in a real device-side shell and the exit code is observable either way.
      if (executor.usesShellInterpreter) {
        executeViaShellInterpreter(executor, effectiveCommand)
      } else {
        executeViaShellTrampoline(executor, effectiveCommand)
      }
    } catch (e: CancellationException) {
      // Propagate cancellation so structured-concurrency teardown isn't silently swallowed.
      // Precedent: ListInstalledAppsTrailblazeTool.execute and RunCommandTrailblazeTool.execute
      // catch the same trap explicitly.
      throw e
    } catch (e: Exception) {
      TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "Failed to run android_adbShell command '${effectiveCommand.take(200)}': ${e.message}",
        command = this,
        stackTrace = e.stackTraceToString(),
      )
    }
  }

  /**
   * Shell-backed transport (host: dadb → `adbd` runs `sh -c`). The shell-escaped [effectiveCommand]
   * and the appended `$?` exit sentinel both work, so a non-zero program exit is recovered from
   * the sentinel and surfaced as an error. This is the original `android_adbShell` behavior.
   */
  private fun executeViaShellInterpreter(
    executor: AndroidDeviceCommandExecutor,
    effectiveCommand: String,
  ): TrailblazeToolResult {
    val wrapped = wrapWithExitSentinel(effectiveCommand)
    val rawOutput = if (runAs != null) {
      executor.executeShellCommandAs(runAs, wrapped)
    } else {
      executor.executeShellCommand(wrapped)
    }
    return resultFromSentinelOutput(effectiveCommand, rawOutput)
  }

  /**
   * Resolves the sentinel-bearing [rawOutput] of either transport into the tool result: Success on
   * exit 0, an error naming the exit code otherwise, and a fail-loud error when the sentinel line
   * is missing (we can't tell success from failure, so we refuse to report Success).
   */
  private fun resultFromSentinelOutput(
    effectiveCommand: String,
    rawOutput: String,
  ): TrailblazeToolResult {
    val parsed = parseExitSentinel(rawOutput)
    return when {
      parsed.exitCode == 0 -> TrailblazeToolResult.Success(message = parsed.output)
      parsed.exitCode == EXIT_CODE_SENTINEL_MISSING -> TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = buildString {
          append("android_adbShell could not detect the exit code for command '${effectiveCommand.take(200)}' ")
          append("— sentinel line was missing from the output. The command may have invoked ")
          append("`exec`, terminated the shell, or produced output that displaced the trailing ")
          append("sentinel. Treating as failure to avoid silently reporting Success.")
          if (parsed.output.isNotEmpty()) {
            append("\nOutput:\n")
            append(parsed.output)
          }
        },
        command = this,
      )
      else -> TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = buildString {
          append("android_adbShell command exited with ${parsed.exitCode}: ${effectiveCommand.take(200)}")
          if (parsed.output.isNotEmpty()) {
            append('\n')
            append(parsed.output)
          }
        },
        command = this,
      )
    }
  }

  /**
   * Shell-less transport (on-device: `UiAutomationConnection.executeShellCommand` → [Runtime.exec]).
   * [Runtime.exec] whitespace-splits its string and execs the tokens with no shell, so the
   * sentinel-wrapped shell string cannot be dispatched as-is. Instead it rides the shared
   * [wrapShellPipelineForTransport] trampoline ([buildShellTrampolineCommand]): base64-packed into
   * a single whitespace-free token that survives the split, then decoded and evaluated by a real
   * device-side `sh`. That restores shell semantics (the shell-escaped tokens are honored, so
   * whitespace-bearing tokens work — the raw-argv dispatch this replaces had to reject them) AND
   * the `$?` exit sentinel, closing the false-green gap where a failing command silently reported
   * Success.
   *
   * The exec runs in the separate UiAutomation process, where a failure "cannot cross the Binder"
   * back to us — a wedged command leaves the result-pipe read blocked indefinitely; without a bound
   * the agent hangs until the session's ~13-minute inactivity watchdog kills it. So the call runs
   * on an interruptible IO dispatcher bounded by [ON_DEVICE_SHELL_TIMEOUT_MS] — a timeout fails
   * fast with a clear error.
   */
  private suspend fun executeViaShellTrampoline(
    executor: AndroidDeviceCommandExecutor,
    effectiveCommand: String,
  ): TrailblazeToolResult {
    val transportCommand = buildShellTrampolineCommand(effectiveCommand)
    val rawOutput = withTimeoutOrNull(ON_DEVICE_SHELL_TIMEOUT_MS) {
      runInterruptible(Dispatchers.IO) {
        if (runAs != null) {
          executor.executeShellCommandAs(runAs, transportCommand)
        } else {
          executor.executeShellCommand(transportCommand)
        }
      }
    } ?: return TrailblazeToolResult.Error.ExceptionThrown(
      errorMessage = "android_adbShell command did not return within ${ON_DEVICE_SHELL_TIMEOUT_MS}ms " +
        "on the on-device transport: '${effectiveCommand.take(200)}'. A wedged command leaves the " +
        "UiAutomation result pipe blocked; failing fast instead of hanging until the session " +
        "inactivity watchdog fires.",
      command = this,
    )
    return resultFromSentinelOutput(effectiveCommand, rawOutput)
  }

  /**
   * Holder for [parseExitSentinel] — keeps the parsing logic itself a pure function with
   * a typed result instead of a `Pair<String, Int>` whose ordering callers have to
   * remember. [exitCode] is [EXIT_CODE_SENTINEL_MISSING] when no sentinel was found.
   */
  internal data class ParsedExit(val output: String, val exitCode: Int)

  internal companion object {

    /**
     * Distinctive token used to demarcate the exit-code sentinel line we append to the
     * user's command. Includes a `__TBZ_` prefix to avoid colliding with realistic
     * command output. The chosen token has no special shell meaning (no `$`, no
     * backticks, no quotes), so it round-trips through `sh -c` unchanged.
     */
    internal const val EXIT_SENTINEL_TOKEN: String = "__TBZ_ADBSHELL_EXIT__"

    /**
     * Returned in [ParsedExit.exitCode] when the sentinel line could not be located in
     * the raw output. Distinct from any plausible real exit code (Android shells
     * follow POSIX `0..255`); we surface this as an error rather than silently coercing
     * to a non-zero status — better to fail loud than to mis-report.
     */
    internal const val EXIT_CODE_SENTINEL_MISSING: Int = -1

    private val EXIT_SENTINEL_REGEX: Regex = Regex(
      "(?m)^${Regex.escape(EXIT_SENTINEL_TOKEN)}(\\d+)\\s*$",
    )

    /**
     * Wraps [command] so the device shell emits a final line like
     * `__TBZ_ADBSHELL_EXIT__0` carrying the exit code of the user's last statement.
     *
     * Uses `;` (not `&&`) so the sentinel is emitted regardless of the user command's
     * exit status. The printf format starts with `\n` so the sentinel always begins on a
     * fresh line even when the command's stdout doesn't end in a newline (e.g.
     * `printf foo`) — without it the sentinel glues onto the last output line
     * (`foo__TBZ_ADBSHELL_EXIT__0`), the line-anchored [EXIT_SENTINEL_REGEX] misses, and
     * a successful command is misreported as sentinel-missing. A single `printf` (not
     * `echo; echo …$?`) is load-bearing: an intermediate command would reset `$?` before
     * it's read.
     */
    internal fun wrapWithExitSentinel(command: String): String =
      "$command; printf '\\n$EXIT_SENTINEL_TOKEN%s\\n' \$?"

    /**
     * Joins [command] into a single shell string by single-quote-wrapping each element via
     * the shared [shellEscape] helper and separating with spaces. The wrapping makes
     * every shell metacharacter inside an element literal (no `$` expansion, no backtick
     * eval, no glob) — this is the load-bearing safety property of the argv-shaped API.
     *
     * `internal` for companion-object test access only; not part of the public API.
     */
    internal fun joinCommandAsShellString(command: List<String>): String =
      command.joinToString(separator = " ") { it.shellEscape() }

    /**
     * Renders the sentinel-wrapped [effectiveCommand] for a transport with **no shell interpreter**
     * (on-device: `UiAutomationConnection.executeShellCommand` → [Runtime.exec]): the shared
     * [wrapShellPipelineForTransport] trampoline base64-packs the whole shell expression into a
     * single whitespace-free `sh -c` token, so it survives [Runtime.exec]'s whitespace split and is
     * decoded + evaluated by a real device-side `sh`. Both the shell-escaping of the argv tokens
     * and the `$?` exit sentinel are honored there, exactly as on the host transport.
     *
     * Pure function for testability — a test can base64-decode the payload back out and pin that
     * the device-side `sh` evaluates the same sentinel-wrapped command the host transport gets.
     */
    internal fun buildShellTrampolineCommand(effectiveCommand: String): String =
      wrapShellPipelineForTransport(
        usesShellInterpreter = false,
        innerCommand = wrapWithExitSentinel(effectiveCommand),
      )

    /**
     * Upper bound for a single on-device (shell-less) `android_adbShell` dispatch. A failed
     * `Runtime.exec` raises an exception in the separate UiAutomation process that cannot cross the
     * Binder, leaving the result-pipe read blocked; without this bound the agent would hang until
     * the session's ~13-minute inactivity watchdog. 60s is generous for a real shell-out (`pm`,
     * `am`, `dumpsys`) on a slow CI emulator while still failing fast on a wedged exec. Does not
     * apply to the host transport, which has its own `TRAILBLAZE_ADB_TIMEOUT_MS` bound.
     */
    internal const val ON_DEVICE_SHELL_TIMEOUT_MS: Long = 60_000L

    /**
     * Splits [rawOutput] into the user-facing command output and the captured exit code.
     *
     * The sentinel must appear on its own line (`\\s*$` anchor) for safety against the
     * rare case where the user's command output happens to contain a literal
     * `__TBZ_ADBSHELL_EXIT__N` substring earlier in the stream, and the LAST such line
     * wins — the real sentinel is always the final thing the wrapped command prints, so
     * even a user output line that is exactly a sentinel-shaped line can't hijack the
     * exit code. If the regex finds no match, returns the raw output verbatim with
     * [EXIT_CODE_SENTINEL_MISSING] so the caller can surface an error.
     *
     * Pure function for testability — callers don't need a real
     * [xyz.block.trailblaze.device.AndroidDeviceCommandExecutor] to exercise the parser.
     */
    internal fun parseExitSentinel(rawOutput: String): ParsedExit {
      val match = EXIT_SENTINEL_REGEX.findAll(rawOutput).lastOrNull()
        ?: return ParsedExit(output = rawOutput, exitCode = EXIT_CODE_SENTINEL_MISSING)
      val exitCode = match.groupValues[1].toIntOrNull()
        ?: return ParsedExit(output = rawOutput, exitCode = EXIT_CODE_SENTINEL_MISSING)
      val cleanedOutput = rawOutput.removeRange(match.range).trimEnd()
      return ParsedExit(output = cleanedOutput, exitCode = exitCode)
    }
  }
}
