package xyz.block.trailblaze.util

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/**
 * The one way Trailblaze runs `simctl`. [argv] builds the command line for callers that manage a
 * long-running process themselves (log stream, video recording); [run] and [runSuspending] run a
 * command to completion for everyone else.
 *
 * [argv] uses the tool's own path when `xcrun` can find it, otherwise `xcrun simctl`.
 *
 * `xcrun simctl` looks the tool up on every call, about 50ms before simctl starts (median 0.32s vs
 * 0.27s for `simctl list devices booted` on a developer Mac). The device scan runs simctl for every
 * command that has to resolve a device, and the iOS tools run it on every launch, terminate and
 * pasteboard write, so the lookup is done once per process instead.
 *
 * The path is looked up again if it stops existing (Xcode moved or deleted). Switching Xcode with
 * `xcode-select` while a daemon runs keeps the old one until the daemon restarts.
 */
object SimctlCommand {

  private val locator = SimctlLocator(lookup = ::findSimctlWithXcrun, isExecutable = { File(it).canExecute() })

  /** `simctl` followed by [args], ready for a `ProcessBuilder`. */
  fun argv(vararg args: String): List<String> = locator.prefix() + args

  /** `simctl` followed by [args], ready for a `ProcessBuilder`. */
  fun argv(args: List<String>): List<String> = locator.prefix() + args

  /** What a finished (or abandoned) `simctl` command left behind. */
  data class Result(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    /** True when the command was killed for running past its timeout; [exitCode] is then -1. */
    val timedOut: Boolean = false,
    /** True when it printed more than the caller's cap and was killed; [stdout] holds the first bytes. */
    val outputCapped: Boolean = false,
  ) {
    val success: Boolean get() = exitCode == 0

    /** stdout then stderr, trimmed: for error messages and for wording simctl prints on either stream. */
    val output: String get() = listOf(stdout.trim(), stderr.trim()).filter { it.isNotEmpty() }.joinToString("\n")
  }

  /**
   * Runs `simctl` with [args] (the subcommand and its arguments) and waits up to [timeoutSeconds].
   * [stdin] is written to the command and closed; without it stdin is closed at once, so nothing
   * simctl starts can wait on it. Past the timeout the command and its children are killed and the
   * result is [Result.timedOut]. An interrupt kills them too and propagates, so a cancelled run
   * never leaves a simctl write landing under the run that replaced it. [environment] is added to
   * the daemon's; simctl forwards `SIMCTL_CHILD_<NAME>` to what it launches or spawns as `<NAME>`.
   * Past [maxOutputBytes] on either stream the command is killed and the result is [Result.outputCapped].
   */
  fun run(
    args: List<String>,
    timeoutSeconds: Long,
    stdin: String? = null,
    environment: Map<String, String> = emptyMap(),
    maxOutputBytes: Int? = null,
  ): Result = runCommand(argv(args), timeoutSeconds, stdin, environment, maxOutputBytes)

  /**
   * [run] with the whole command line given: any short host command that sits beside simctl
   * (`plutil`), and tests that run it without Xcode.
   */
  fun runCommand(
    command: List<String>,
    timeoutSeconds: Long,
    stdin: String? = null,
    environment: Map<String, String> = emptyMap(),
    maxOutputBytes: Int? = null,
  ): Result {
    val proc = ProcessBuilder(command).redirectErrorStream(false)
      .apply { environment().putAll(environment) }
      .start()
    // Read while it runs: output larger than the pipe buffer (`list devices -j` on a busy Mac)
    // would otherwise block the child until the timeout.
    val capped = AtomicBoolean(false)
    val overflow = {
      capped.set(true)
      proc.destroyTreeForcibly()
    }
    val stdout = drain(proc.inputStream, maxOutputBytes, overflow)
    val stderr = drain(proc.errorStream, maxOutputBytes, overflow)
    // Written on its own thread so the timeout and an interrupt still apply to a command that never
    // reads a large input. A command that exits without reading closes the pipe; its exit says why.
    thread(isDaemon = true, name = "simctl-input") {
      runCatching { proc.outputStream.use { input -> if (stdin != null) input.write(stdin.toByteArray()) } }
    }
    if (!waitForOrKill(proc, timeoutSeconds)) {
      proc.destroyTreeForcibly()
      return Result(-1, "", "${toolName(command)} timed out after ${timeoutSeconds}s", timedOut = true)
    }
    // Output that could not be read fails the command: callers use stdout as a path to delete.
    val output = try {
      stdout.get(OUTPUT_DRAIN_SECONDS, TimeUnit.SECONDS) to stderr.get(OUTPUT_DRAIN_SECONDS, TimeUnit.SECONDS)
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
      throw e
    } catch (e: Exception) {
      return Result(-1, "", "${toolName(command)} exited but its output could not be read: $e")
    }
    if (capped.get()) {
      return Result(-1, output.first, "${toolName(command)} printed more than $maxOutputBytes bytes and was stopped", outputCapped = true)
    }
    return Result(proc.exitValue(), output.first, output.second)
  }

  /** `simctl` for a simctl command however it is launched (`xcrun simctl` or the tool's own path), else the program's name. */
  internal fun toolName(command: List<String>): String =
    command.map { it.substringAfterLast('/') }.let { names -> names.firstOrNull { it == "simctl" } ?: names.first() }

  /** [run] for coroutines: cancelling the caller kills the command. */
  suspend fun runSuspending(
    args: List<String>,
    timeoutSeconds: Long,
    stdin: String? = null,
    environment: Map<String, String> = emptyMap(),
    maxOutputBytes: Int? = null,
  ): Result =
    // argv inside the IO block: the first call's `xcrun --find` must not block the caller's thread.
    runCommandSuspending({ argv(args) }, timeoutSeconds, stdin, environment, maxOutputBytes)

  /** [runSuspending] with the command line given, so tests can run it without Xcode. */
  internal suspend fun runCommandSuspending(
    command: () -> List<String>,
    timeoutSeconds: Long,
    stdin: String? = null,
    environment: Map<String, String> = emptyMap(),
    maxOutputBytes: Int? = null,
  ): Result = runInterruptible(Dispatchers.IO) { runCommand(command(), timeoutSeconds, stdin, environment, maxOutputBytes) }

  /**
   * [Process.waitFor] that kills the child when the wait is interrupted, as it does on a timeout,
   * rather than leave it running: a cancelled run's language write or SpringBoard stop must not
   * land under the run that replaced it. The interrupt still propagates.
   */
  fun waitForOrKill(proc: Process, timeoutSeconds: Long): Boolean =
    try {
      proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)
    } catch (e: InterruptedException) {
      proc.destroyTreeForcibly()
      // The interrupt is consumed by the throw, so this short reap is not cut short again.
      runCatching { proc.waitFor(KILL_REAP_SECONDS, TimeUnit.SECONDS) }
      Thread.currentThread().interrupt()
      throw e
    }

  /** Reads [stream] to its end, or to [maxBytes], where it calls [onOverflow] and stops. */
  private fun drain(stream: InputStream, maxBytes: Int? = null, onOverflow: () -> Unit = {}): FutureTask<String> =
    FutureTask {
      stream.use { input ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
          val n = input.read(buffer)
          if (n < 0) break
          if (maxBytes != null && out.size() + n > maxBytes) {
            out.write(buffer, 0, maxBytes - out.size())
            onOverflow()
            break
          }
          out.write(buffer, 0, n)
        }
        out.toString(Charsets.UTF_8)
      }
    }.also { thread(isDaemon = true, name = "simctl-output") { it.run() } }

  /** How long a child that left the pipe open (a process it started) may hold up the result. */
  private const val OUTPUT_DRAIN_SECONDS = 5L

  private const val KILL_REAP_SECONDS = 2L

  /**
   * An interrupt (a cancelled [runSuspending]) kills the lookup and propagates instead of counting as
   * "not found": [SimctlLocator] would remember that answer for the life of the process, and the
   * cancelled command would go on to run.
   */
  private fun findSimctlWithXcrun(): String? = try {
    val process = ProcessBuilder("xcrun", "--find", "simctl").redirectErrorStream(false).start()
    process.outputStream.close()
    val finished = waitForOrKill(process, LOOKUP_TIMEOUT_SECONDS)
    if (!finished) {
      process.destroyForcibly()
      null
    } else if (process.exitValue() != 0) {
      null
    } else {
      process.inputStream.bufferedReader().use { it.readText() }.trim().takeIf { it.isNotEmpty() }
    }
  } catch (e: InterruptedException) {
    throw e
  } catch (_: Exception) {
    null
  }

  /**
   * `xcrun --find` answers in about 50ms. The caller with a tight budget (`AxeCli.type`)
   * starts its clock before building argv, so it counts against it; other callers pay it on top of their own timeout, so it is kept short. Past it, `xcrun`
   * itself is stuck and `xcrun simctl` would be too.
   */
  private const val LOOKUP_TIMEOUT_SECONDS = 2L
}

/**
 * The resolution rule behind [SimctlCommand], with the lookup and the file check passed in so it
 * can be exercised without Xcode.
 *
 * A failed lookup is remembered too: on a host without Xcode every call would otherwise pay the
 * failing `xcrun --find` before falling back to an `xcrun simctl` that fails the same way.
 */
internal class SimctlLocator(
  private val lookup: () -> String?,
  private val isExecutable: (String) -> Boolean,
) {
  private sealed interface Resolution {
    data class Found(val path: String) : Resolution
    data object NotFound : Resolution
  }

  @Volatile
  private var resolution: Resolution? = null

  fun prefix(): List<String> = cached(resolution) ?: resolve()

  /** Callers that miss together (the parallel device scan) share one lookup instead of each spawning `xcrun`. */
  @Synchronized
  private fun resolve(): List<String> {
    val seen = resolution
    cached(seen)?.let { return it }
    // Only a vanished path gets here with a resolution; the lookup answers again for it.
    val found = lookup()?.takeIf { File(it).isAbsolute && isExecutable(it) }
    resolution = found?.let { Resolution.Found(it) } ?: Resolution.NotFound
    return found?.let { listOf(it) } ?: XCRUN_SIMCTL
  }

  private fun cached(current: Resolution?): List<String>? = when {
    current is Resolution.Found && isExecutable(current.path) -> listOf(current.path)
    current == Resolution.NotFound -> XCRUN_SIMCTL
    else -> null
  }

  companion object {
    val XCRUN_SIMCTL: List<String> = listOf("xcrun", "simctl")
  }
}
