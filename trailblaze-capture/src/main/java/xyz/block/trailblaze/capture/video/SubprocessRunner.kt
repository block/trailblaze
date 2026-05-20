package xyz.block.trailblaze.capture.video

import java.util.concurrent.TimeUnit

/**
 * Outcome of [runSubprocessWithTimeout]. `exitCode == 0` means the process exited successfully;
 * non-zero means it ran to completion but failed. A `null` return from the runner itself signals
 * "could not run the binary at all, or it timed out and was destroyed" — distinct from a
 * non-zero exit.
 */
internal data class SubprocessResult(val exitCode: Int, val output: String)

/**
 * Runs a child process with a timeout, drains its combined stdout/stderr on a daemon thread, and
 * cleans up on timeout. The daemon-drain is load-bearing: `readText()`/`forEachLine` on the
 * inputStream blocks until EOF — which only happens when the subprocess exits — so doing the
 * drain inline before [Process.waitFor] would make a wedged subprocess unkillable (the timeout
 * branch would be unreachable). Read on a side thread, and the caller can actually time out.
 *
 * On timeout:
 *  - Closes the inputStream explicitly so a kernel-blocked reader gets EOF (some JVM/OS
 *    combinations don't unblock the reader on `destroyForcibly()` alone — leaving an orphan
 *    daemon thread holding the file handle until JVM exit).
 *  - Calls [Process.destroyForcibly] to reap the subprocess.
 *  - Joins the drain thread with a short cap so we don't block the caller indefinitely if the
 *    reader is wedged in native code.
 *
 * Returns `null` when the binary couldn't start at all, when the process timed out, or when an
 * unexpected exception interrupted the run. Returns a [SubprocessResult] with the exit code and
 * the captured combined stdout/stderr otherwise — callers inspect `exitCode` to distinguish
 * success from a clean failure.
 *
 * **Why this lives here.** Three sites in `trailblaze-capture` (this file's
 * `probeDurationAndFrameCount`, `PlaywrightVideoCapture.transcodeWebmToMp4`, and
 * `MuxToMp4Consumer.concatSegments` / `wrapSingleSegment`) all want the same shape: timeboxed
 * subprocess, captured output for diagnostics, no orphans on the failure path. Before this
 * helper they each open-coded variations; the inconsistencies — readText-before-waitFor in some,
 * missing destroyForcibly in others — have surfaced as real bugs (see codex/copilot review on
 * PR #3087). One helper, one tested implementation.
 */
internal fun runSubprocessWithTimeout(
  command: List<String>,
  timeoutSeconds: Long,
): SubprocessResult? {
  require(command.isNotEmpty()) { "command must include at least the binary path" }
  require(timeoutSeconds > 0) { "timeoutSeconds must be positive, got $timeoutSeconds" }
  val process =
    try {
      ProcessBuilder(command).redirectErrorStream(true).start()
    } catch (_: Exception) {
      // Binary missing, no execute permission, etc.
      return null
    }
  val drained = StringBuilder()
  val drainThread =
    Thread {
        try {
          process.inputStream.bufferedReader().use { reader ->
            reader.forEachLine { drained.appendLine(it) }
          }
        } catch (_: Exception) {
          // Expected when the process is force-killed mid-read.
        }
      }
      .apply {
        isDaemon = true
        start()
      }
  val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
  if (!finished) {
    // Close the stream first so a reader blocked in native read() unblocks promptly — some
    // JVM/OS combinations don't deliver EOF to the reader on destroyForcibly() alone, which
    // would leave an orphan daemon thread holding the file handle until JVM exit.
    runCatching { process.inputStream.close() }
    process.destroyForcibly()
    drainThread.join(DRAIN_JOIN_TIMEOUT_MS)
    return null
  }
  // Process exited cleanly → stdout is at EOF → drain thread is guaranteed to terminate.
  // Use an unbounded join (no timeout) so a slow OS context-switch can't truncate the tail
  // of `drained` and leave the caller reading partial output. The bounded join above is
  // only needed on the timeout-and-destroyForcibly path where the drain might be wedged in
  // native code.
  drainThread.join()
  return SubprocessResult(exitCode = process.exitValue(), output = drained.toString())
}

/**
 * Renders subprocess stdout/stderr safely into a log line: collapses control characters
 * (notably ANSI escape codes) into `?`, and truncates so a verbose process can't bloat a
 * single log entry. Newlines survive because multi-line context is often the point of the
 * log — only out-of-band control characters get filtered.
 */
internal fun sanitizeSubprocessOutputForLog(output: String, maxChars: Int = 400): String {
  if (output.isEmpty()) return "(empty)"
  val sanitized = output.replace(SUBPROCESS_CONTROL_CHARS, "?")
  return if (sanitized.length <= maxChars) sanitized
  else sanitized.substring(0, maxChars) + "…(truncated, ${sanitized.length - maxChars} more chars)"
}

/** Control characters except `\n` and `\t` — the same shape `ConsoleAppender` filters out. */
private val SUBPROCESS_CONTROL_CHARS = Regex("[\\p{Cntrl}&&[^\\n\\t]]")

private const val DRAIN_JOIN_TIMEOUT_MS: Long = 1_000L
