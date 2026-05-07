package xyz.block.trailblaze.util

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import xyz.block.trailblaze.util.Console

object TrailblazeProcessBuilderUtils {

  /**
   * Note: All System Environment Variables will be passed along to the target process
   */
  fun createProcessBuilder(
    args: List<String>,
    workingDir: File? = null,
  ): ProcessBuilder = try {
    val processBuilder = ProcessBuilder(args)
      .redirectErrorStream(true)

    if (workingDir != null) {
      processBuilder.directory(workingDir)
    }

    // Get the environment map
    System.getenv().keys.forEach { envVar ->
      val value = System.getenv(envVar)
      if (value != null) {
        processBuilder.environment()[envVar] = value
      } else {
        Console.log("Warning: $envVar is not set in the environment")
      }
    }

    Console.log(
      buildString {
        append("Starting process: ")
        append(processBuilder.command().joinToString(" "))
      },
    )

    processBuilder
  } catch (e: Exception) {
    throw RuntimeException("Failed to start process for $args. Error: ${e.message}", e)
  }

  /**
   * Checks if a command is available on the system PATH. Uses `where` on Windows and `which` on
   * other platforms.
   */
  fun isCommandAvailable(command: String): Boolean {
    return try {
      val whichCommand = if (isWindows()) "where" else "which"
      val result = createProcessBuilder(listOf(whichCommand, command)).runProcess {}
      val found = result.exitCode == 0
      if (found) {
        Console.log("$command installation is available on the PATH")
      } else {
        Console.log("$command installation is not available on the PATH")
      }
      found
    } catch (e: Throwable) {
      Console.log("$command installation not found")
      false
    }
  }

  /**
   * Coroutine-aware companion to [runProcess] that adds optional wall-clock timeouts
   * and cooperative-cancellation support.
   *
   * Differs from the simpler [runProcess] extension below in three load-bearing ways:
   *
   *  - **Cooperative cancellation.** `process.waitFor()` is wrapped in [runInterruptible],
   *    so coroutine cancellation (agent abort, driver disconnect, session shutdown)
   *    translates into thread interruption and the subprocess gets [Process.destroyForcibly]
   *    in the `finally`. The simple extension blocks indefinitely on `waitFor()` and
   *    leaves a hung subprocess running.
   *  - **Optional wall-clock timeout.** When [timeoutSeconds] is non-null, [Process.waitFor]
   *    with a timeout is used; on expiry the subprocess is forcibly killed and the result
   *    pair carries `timedOut = true` so the caller can format a tool-specific timeout error.
   *  - **Async stream reader.** A `launch { ... }` reader job collects combined
   *    stdout/stderr into [CommandProcessResult.outputLines] in parallel with the wait,
   *    so callers don't deadlock on a producer-fills-pipe-buffer scenario when the
   *    process emits more output than the OS pipe can hold before the wait completes.
   *
   * @return `(result, timedOut)` — the [CommandProcessResult] with collected output and
   *   the actual exit code (or `-1` on timeout), plus a boolean flagging the timeout
   *   path so callers can construct a per-tool timeout error message without re-checking
   *   the exit code.
   *
   * Used by [xyz.block.trailblaze.toolcalls.commands.RunCommandTrailblazeTool] (shell
   * `sh -c` form) and [xyz.block.trailblaze.toolcalls.commands.ExecTrailblazeTool] (argv
   * form). Both tools want identical subprocess-lifecycle behaviour; only the per-tool
   * error-message construction differs and lives in their `execute` method.
   */
  suspend fun ProcessBuilder.runProcessWithTimeout(
    timeoutSeconds: Long? = null,
  ): Pair<CommandProcessResult, Boolean> = withContext(Dispatchers.IO) {
    val processBuilder = this@runProcessWithTimeout
    processBuilder.redirectOutput(ProcessBuilder.Redirect.PIPE)
    val process = processBuilder.start()
    val outputLines = mutableListOf<String>()
    val readerJob = launch {
      try {
        process.inputStream.bufferedReader().use { reader ->
          var line: String?
          while (reader.readLine().also { line = it } != null) {
            line?.let { outputLines.add(it) }
          }
        }
      } catch (e: IOException) {
        // Subprocess closing stdout mid-read (destroyForcibly on timeout/cancel) can
        // surface as IOException on readLine. Partial output up to that point is still
        // kept in [outputLines]; log for observability and let the reader exit.
        Console.log("runProcessWithTimeout reader stopped: ${e.message}")
      }
    }
    try {
      val finished = runInterruptible {
        if (timeoutSeconds != null) {
          process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        } else {
          process.waitFor()
          true
        }
      }
      if (!finished) {
        process.destroyForcibly()
      }
      // The reader exits on its own once the subprocess closes stdout (normal exit or
      // forced kill). Joining guarantees [outputLines] is fully populated before read.
      readerJob.join()
      CommandProcessResult(
        outputLines = outputLines.toList(),
        exitCode = if (finished) process.exitValue() else -1,
      ) to !finished
    } finally {
      if (process.isAlive) process.destroyForcibly()
    }
  }

  fun ProcessBuilder.runProcess(
    outputLineCallback: (String) -> Unit,
  ): CommandProcessResult {
    val processBuilder = this
    processBuilder.redirectOutput(ProcessBuilder.Redirect.PIPE)
    val process = processBuilder.start()
    val outputLines = mutableListOf<String>()

    // Read output line by line and call callback for each line
    process.inputStream.bufferedReader().use { reader ->
      var line: String?
      while (reader.readLine().also { line = it } != null) {
        line?.let {
          outputLines.add(it)
          outputLineCallback(it)
        }
      }
    }

    val exitCode = process.waitFor()

    if (exitCode != 0) {
      return CommandProcessResult(
        outputLines,
        exitCode,
        "Command failed with exit code $exitCode: $outputLines.joinToString(\"\\n\")",
      )
    }

    return CommandProcessResult(outputLines, exitCode)
  }
}
