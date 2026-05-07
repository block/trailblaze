package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.toolcalls.HostLocalExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.TrailblazeProcessBuilderUtils
import xyz.block.trailblaze.util.isWindows
import java.io.File

internal const val RUN_COMMAND_TOOL_NAME = "runCommand"

/**
 * Runs a shell command on the host and returns its output.
 *
 * Intentionally hidden from the LLM (`isForLlm = false`) — this is a building block for
 * higher-level tools that wrap specific CLIs. Callers are responsible for sanitizing any
 * untrusted input that flows into [command], since the string is evaluated by `sh -c` on
 * Unix and `cmd /c` on Windows.
 *
 * Implements [HostLocalExecutableTrailblazeTool] so that [xyz.block.trailblaze.BaseTrailblazeAgent]
 * short-circuits execution to the host JVM instead of trying to route the call through a
 * device / browser / cloud driver (none of which know what a generic shell command is).
 *
 * stdout and stderr are combined via [TrailblazeProcessBuilderUtils.createProcessBuilder]'s
 * `redirectErrorStream(true)`. Wrappers that need to distinguish the two streams will need
 * a different mechanism.
 *
 * Note: the full shell argv (including [command]) is logged via
 * [TrailblazeProcessBuilderUtils.createProcessBuilder]'s `Starting process: ...` line, and
 * [command] is also echoed back in [TrailblazeToolResult.Error.ExceptionThrown.errorMessage]
 * on failure. Wrapper tools that embed secrets (tokens, credentials, signed URLs) in
 * [command] should inject them via environment variables instead of inlining them.
 */
@Serializable
@TrailblazeToolClass(
  name = RUN_COMMAND_TOOL_NAME,
  isForLlm = false,
  isRecordable = false,
  requiresHost = true,
)
@LLMDescription("Runs a shell command on the host JVM and returns its combined stdout/stderr.")
data class RunCommandTrailblazeTool(
  /**
   * The shell command to run. Evaluated by `sh -c` on Unix or `cmd /c` on Windows, so
   * pipes, redirects, globs, and quoting all work as they would at a prompt. Wrapper
   * tools are responsible for sanitizing any untrusted input that flows in here.
   */
  val command: String,
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

  override val advertisedToolName: String get() = RUN_COMMAND_TOOL_NAME

  override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult {
    return try {
      val shellArgs = if (isWindows()) {
        listOf("cmd", "/c", command)
      } else {
        listOf("sh", "-c", command)
      }
      val processBuilder = TrailblazeProcessBuilderUtils
        .createProcessBuilder(shellArgs, workingDir?.let(::File))
      val (result, timedOut) = with(TrailblazeProcessBuilderUtils) {
        processBuilder.runProcessWithTimeout(timeoutSeconds)
      }

      when {
        timedOut -> TrailblazeToolResult.Error.ExceptionThrown(
          errorMessage = buildString {
            append("Command timed out after ${timeoutSeconds}s: $command")
            if (result.outputLines.isNotEmpty()) {
              append('\n')
              append(result.fullOutput)
            }
          },
          command = this@RunCommandTrailblazeTool,
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
            append("Command exited with ${result.exitCode} (expected $expectedExitCode): $command")
            if (result.outputLines.isNotEmpty()) {
              append('\n')
              append(result.fullOutput)
            }
          },
          command = this@RunCommandTrailblazeTool,
        )
      }
    } catch (e: CancellationException) {
      // Propagate cancellation so structured-concurrency teardown (agent abort, driver
      // disconnect, session shutdown) isn't silently converted into a normal tool error.
      // Precedent: SubprocessTrailblazeTool.execute catches this same trap explicitly.
      throw e
    } catch (e: Exception) {
      TrailblazeToolResult.Error.ExceptionThrown.fromThrowable(e, this)
    }
  }

}
