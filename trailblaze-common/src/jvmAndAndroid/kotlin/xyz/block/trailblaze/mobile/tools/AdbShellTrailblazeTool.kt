package xyz.block.trailblaze.mobile.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
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
 * Marked `requiresHost = false` so the on-device runner registers it alongside the host
 * one. A scripted tool that composes only `adbShell` and other dual-mode tools (e.g.
 * [AndroidSendBroadcastTrailblazeTool]) does **not** need `requiresHost: true` on its
 * descriptor — both ends of the matrix can serve the call.
 *
 * ### Exit-code detection via sentinel echo
 *
 * The underlying [xyz.block.trailblaze.device.AndroidDeviceCommandExecutor.executeShellCommand]
 * returns only the combined stdout — no exit-code channel. To make scripted-tool composition
 * fail-loud on non-zero exits (so `client.callTool("adbShell", { command: "am force-stop
 * bogus.pkg" })` raises `isError` instead of silently succeeding with `Error: package
 * 'bogus.pkg' not installed` in stdout), the implementation appends `; echo
 * __TBZ_ADBSHELL_EXIT__$?` to the user's command, runs the wrapped command via the
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
 * machine's PATH). `adbShell` runs in the *device's shell* — `pm`, `am`, `setprop`,
 * `dumpsys`, `input keyevent`, `getprop`. The two solve different problems and live on
 * different sides of the host/device boundary.
 *
 * ### Argv form
 *
 * The underlying `executeShellCommand` contract is a single shell string evaluated by
 * the device's shell (`sh -c <cmd>` semantically). We expose [command] as a plain string
 * to match. Authors who need argv safety against templated parameters should construct
 * the command with appropriate escaping themselves, or run the broadcast equivalent via
 * [AndroidSendBroadcastTrailblazeTool] which is structurally argv-safe.
 *
 * If a future need lands for argv-form `adbShell`, add an `argv: List<String>?` field
 * mutually exclusive with [command] and join with shell-quote escapes at the boundary.
 */
@Serializable
@TrailblazeToolClass(
  name = "adbShell",
  isForLlm = false,
  isRecordable = false,
)
@LLMDescription("Executes a shell command in the Android device's shell environment. Returns combined stdout/stderr.")
data class AdbShellTrailblazeTool(
  /**
   * Shell command to evaluate in the device's shell. Standard shell semantics — pipes,
   * redirects, globs, multi-statement, all work as they would at an `adb shell` prompt.
   * Authors interpolating untrusted input must sanitize/quote it before placing it here;
   * the underlying transport does no escaping.
   *
   * Empty / blank commands are rejected at execute time (the device's `sh -c ""` would
   * succeed silently, which is rarely what the author intended).
   */
  val command: String,
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

  override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult {
    if (toolExecutionContext.trailblazeDeviceInfo.platform != TrailblazeDevicePlatform.ANDROID) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "adbShell is only supported on Android devices " +
          "(got platform: ${toolExecutionContext.trailblazeDeviceInfo.platform}).",
      )
    }
    if (command.isBlank()) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "adbShell requires a non-blank command.",
        command = this,
      )
    }
    val executor = toolExecutionContext.androidDeviceCommandExecutor
      ?: return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "AndroidDeviceCommandExecutor is not provided",
      )
    return try {
      val wrapped = wrapWithExitSentinel(command)
      val rawOutput = if (runAs != null) {
        executor.executeShellCommandAs(runAs, wrapped)
      } else {
        executor.executeShellCommand(wrapped)
      }
      val parsed = parseExitSentinel(rawOutput)
      when {
        parsed.exitCode == 0 -> TrailblazeToolResult.Success(message = parsed.output)
        parsed.exitCode == EXIT_CODE_SENTINEL_MISSING -> TrailblazeToolResult.Error.ExceptionThrown(
          errorMessage = buildString {
            append("adbShell could not detect the exit code for command '${command.take(200)}' ")
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
            append("adbShell command exited with ${parsed.exitCode}: ${command.take(200)}")
            if (parsed.output.isNotEmpty()) {
              append('\n')
              append(parsed.output)
            }
          },
          command = this,
        )
      }
    } catch (e: CancellationException) {
      // Propagate cancellation so structured-concurrency teardown isn't silently swallowed.
      // Precedent: ListInstalledAppsTrailblazeTool.execute and RunCommandTrailblazeTool.execute
      // catch the same trap explicitly.
      throw e
    } catch (e: Exception) {
      TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "Failed to run adbShell command '${command.take(200)}': ${e.message}",
        command = this,
        stackTrace = e.stackTraceToString(),
      )
    }
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
     * Wraps [command] so the device shell echoes a final line like
     * `__TBZ_ADBSHELL_EXIT__0` carrying the exit code of the user's last statement.
     *
     * Uses `;` (not `&&`) so the echo runs regardless of the user command's exit status.
     * Wrapping with `{ ... ; }` would buy nothing — `;`-separated lists already evaluate
     * left-to-right and the right-hand `echo` sees `$?` of the previous statement.
     */
    internal fun wrapWithExitSentinel(command: String): String =
      "$command; echo $EXIT_SENTINEL_TOKEN\$?"

    /**
     * Splits [rawOutput] into the user-facing command output and the captured exit code.
     *
     * The sentinel must appear on its own trailing line (`\\s*$` anchor) for safety
     * against the rare case where the user's command output happens to contain a literal
     * `__TBZ_ADBSHELL_EXIT__N` substring earlier in the stream. If the regex finds no
     * match, returns the raw output verbatim with [EXIT_CODE_SENTINEL_MISSING] so the
     * caller can surface an error.
     *
     * Pure function for testability — callers don't need a real
     * [xyz.block.trailblaze.device.AndroidDeviceCommandExecutor] to exercise the parser.
     */
    internal fun parseExitSentinel(rawOutput: String): ParsedExit {
      val match = EXIT_SENTINEL_REGEX.find(rawOutput)
        ?: return ParsedExit(output = rawOutput, exitCode = EXIT_CODE_SENTINEL_MISSING)
      val exitCode = match.groupValues[1].toIntOrNull()
        ?: return ParsedExit(output = rawOutput, exitCode = EXIT_CODE_SENTINEL_MISSING)
      val cleanedOutput = rawOutput.removeRange(match.range).trimEnd()
      return ParsedExit(output = cleanedOutput, exitCode = exitCode)
    }
  }
}
