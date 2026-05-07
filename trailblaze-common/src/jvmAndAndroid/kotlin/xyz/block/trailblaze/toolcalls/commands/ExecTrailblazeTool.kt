package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.toolcalls.HostLocalExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.TrailblazeProcessBuilderUtils
import java.io.File

internal const val EXEC_TOOL_NAME = "exec"

/**
 * Executes a process on the host JVM via direct argv invocation (no shell).
 *
 * The argv-form companion to [RunCommandTrailblazeTool]. Both run a process on the host;
 * the difference is the boundary between argv and shell:
 *
 *  - **`exec`** — `ProcessBuilder(argv).start()`. Each argv element becomes a literal
 *    process argument. No shell metacharacter interpretation. Templated parameter values
 *    can never be re-interpreted as `;`/`&&`/quotes/backticks/redirects, even when the
 *    value contains those characters. Default for any case where parameters are
 *    interpolated from authored input.
 *  - **`runCommand`** — `sh -c "<command>"`. Pipes, globs, redirects, multi-statement
 *    work as they would at a prompt, with the cost that any unsanitized parameter
 *    value can change shell semantics.
 *
 * Authors who want shell features should opt in explicitly:
 * `exec({ argv: ["sh", "-c", "./gen.sh | grep ready | head -1"] })`. That keeps the
 * argv form mechanically correct (the shell-string is one argv element from
 * `ProcessBuilder`'s perspective) while making the shell invocation visible in source.
 *
 * Implements [HostLocalExecutableTrailblazeTool] so that
 * [xyz.block.trailblaze.BaseTrailblazeAgent] short-circuits execution to the host JVM
 * instead of routing through a device / browser / cloud driver. `exec` is fundamentally
 * host-only: it forks a process inside the daemon JVM, which doesn't exist on-device.
 *
 * stdout and stderr are combined via [TrailblazeProcessBuilderUtils.createProcessBuilder]'s
 * `redirectErrorStream(true)`. Wrappers that need to distinguish the two streams will need
 * a different mechanism.
 *
 * Note: the resolved argv (including [argv]) is logged via
 * [TrailblazeProcessBuilderUtils.createProcessBuilder]'s `Starting process: ...` line, and
 * [argv] is also echoed back in [TrailblazeToolResult.Error.ExceptionThrown.errorMessage]
 * on failure. Wrapper tools that embed secrets (tokens, credentials, signed URLs) in
 * [argv] should inject them via environment variables instead of inlining them.
 */
@Serializable
@TrailblazeToolClass(
  name = EXEC_TOOL_NAME,
  isForLlm = false,
  isRecordable = false,
  requiresHost = true,
)
@LLMDescription("Executes a process on the host JVM via argv (no shell). Returns combined stdout/stderr.")
data class ExecTrailblazeTool(
  /**
   * Process arguments. The first element is the executable; subsequent elements are
   * arguments. Each element is passed verbatim to `ProcessBuilder` — no shell evaluation,
   * so spaces, quotes, semicolons, and other shell metacharacters inside an element are
   * treated as literal characters of that argument, not as shell syntax.
   *
   * Empty argv is rejected at execute time (no executable to launch).
   */
  val argv: List<String>,
  /**
   * Working directory the subprocess starts in, as a filesystem path. When `null` (the
   * default), the subprocess inherits the parent JVM's current working directory — i.e.
   * whatever directory the host entrypoint (the `trailblaze` CLI, the desktop app, a
   * Gradle task, etc.) was launched from. Set this explicitly when the command is
   * path-sensitive and you can't rely on the caller's CWD.
   */
  val workingDir: String? = null,
  /**
   * Exit code that maps to [TrailblazeToolResult.Success]. Defaults to `0`. Any other
   * exit code maps to [TrailblazeToolResult.Error.ExceptionThrown] with the (unfiltered)
   * combined stdout/stderr attached for debugging.
   */
  val expectedExitCode: Int = 0,
  /**
   * Optional Kotlin [Regex] pattern applied per line to trim the success payload —
   * semantically like `grep`. When set, only lines where `containsMatchIn` is true are
   * retained in the [TrailblazeToolResult.Success] message. Intentionally ignored on
   * the error path so a too-restrictive filter can't hide the lines needed to debug a
   * failure.
   */
  val outputFilterRegex: String? = null,
  /**
   * Optional wall-clock cap, in seconds, before the subprocess is forcibly killed and
   * the call resolves to [TrailblazeToolResult.Error.ExceptionThrown]. `null` (the
   * default) means wait indefinitely — only appropriate when the caller knows the
   * command terminates on its own. Strongly recommended for any command invoked from
   * an agent loop where a hung process would otherwise stall the whole session.
   */
  val timeoutSeconds: Long? = null,
) : HostLocalExecutableTrailblazeTool {

  override val advertisedToolName: String get() = EXEC_TOOL_NAME

  override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult {
    if (argv.isEmpty()) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "exec requires a non-empty argv (the first element is the executable to run).",
        command = this,
      )
    }
    return try {
      val processBuilder = TrailblazeProcessBuilderUtils
        .createProcessBuilder(argv, workingDir?.let(::File))
      val (result, timedOut) = with(TrailblazeProcessBuilderUtils) {
        processBuilder.runProcessWithTimeout(timeoutSeconds)
      }

      when {
        timedOut -> TrailblazeToolResult.Error.ExceptionThrown(
          errorMessage = buildString {
            append("Command timed out after ${timeoutSeconds}s: ${argv.joinToString(" ")}")
            if (result.outputLines.isNotEmpty()) {
              append('\n')
              append(result.fullOutput)
            }
          },
          command = this@ExecTrailblazeTool,
        )

        result.exitCode == expectedExitCode -> {
          val filteredOutput = outputFilterRegex
            ?.let { pattern ->
              val regex = Regex(pattern)
              result.outputLines.filter { regex.containsMatchIn(it) }.joinToString("\n")
            }
            ?: result.fullOutput
          TrailblazeToolResult.Success(message = filteredOutput)
        }

        else -> TrailblazeToolResult.Error.ExceptionThrown(
          // Unfiltered output on purpose — a misconfigured [outputFilterRegex] must not
          // hide the lines needed to diagnose the failure.
          errorMessage = buildString {
            append("Command exited with ${result.exitCode} (expected $expectedExitCode): ${argv.joinToString(" ")}")
            if (result.outputLines.isNotEmpty()) {
              append('\n')
              append(result.fullOutput)
            }
          },
          command = this@ExecTrailblazeTool,
        )
      }
    } catch (e: CancellationException) {
      // Propagate cancellation so structured-concurrency teardown (agent abort, driver
      // disconnect, session shutdown) isn't silently converted into a normal tool error.
      // Precedent: SubprocessTrailblazeTool.execute and RunCommandTrailblazeTool.execute
      // both catch this same trap explicitly.
      throw e
    } catch (e: Exception) {
      TrailblazeToolResult.Error.ExceptionThrown.fromThrowable(e, this)
    }
  }

}
