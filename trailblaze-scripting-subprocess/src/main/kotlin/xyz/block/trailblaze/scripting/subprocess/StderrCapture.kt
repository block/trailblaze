package xyz.block.trailblaze.scripting.subprocess

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.ArrayDeque

/**
 * Captures stderr lines from a subprocess. Serves two purposes:
 *
 *  1. Streams every line into a per-session log file (`subprocess_stderr.log` under a
 *     caller-supplied directory) so authors can diagnose after the fact.
 *  2. Keeps the last [tailLines] lines in memory so a crash abort can include a tail snippet
 *     in the error message — authors see *why* the subprocess died, not just that it did.
 *
 * Thread-safe: all mutations guard on the internal lock. Capture is best-effort — if the log
 * file can't be opened, the in-memory tail still works and the session isn't killed.
 */
class StderrCapture(
  private val logFile: File? = null,
  private val tailLines: Int = DEFAULT_TAIL_LINES,
) {

  init {
    require(tailLines > 0) { "tailLines must be positive; got $tailLines" }
  }

  private val lock = Any()
  private val tail = ArrayDeque<String>(tailLines)
  private val writer: BufferedWriter? = try {
    logFile?.apply { parentFile?.mkdirs() }?.let { BufferedWriter(FileWriter(it, /* append = */ true)) }
  } catch (_: Exception) {
    null
  }

  @Volatile private var closed: Boolean = false

  /**
   * True once [close] has run to completion. Exposed so callers / tests can verify that
   * cleanup reached the capture — the on-disk writer flush happens inside [close] and
   * dropping that call would otherwise be silent (the file-write errors are swallowed to
   * keep in-memory capture working even on a misbehaving log path).
   */
  val isClosed: Boolean get() = closed

  fun accept(line: String): Unit = synchronized(lock) {
    while (tail.size >= tailLines) tail.removeFirst()
    tail.addLast(line)
    writer?.let {
      try {
        it.write(line)
        it.newLine()
        it.flush()
      } catch (_: Exception) {
        // Log file went away mid-session — keep in-memory capture going regardless.
      }
    }
  }

  /** Snapshot of the most recent [tailLines] (or fewer) lines, oldest first. */
  fun tailSnapshot(): List<String> = synchronized(lock) { tail.toList() }

  fun close(): Unit = synchronized(lock) {
    runCatching { writer?.flush() }
    runCatching { writer?.close() }
    closed = true
  }

  companion object {
    const val DEFAULT_TAIL_LINES: Int = 64
  }
}
