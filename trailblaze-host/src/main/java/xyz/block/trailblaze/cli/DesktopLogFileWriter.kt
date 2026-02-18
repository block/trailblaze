package xyz.block.trailblaze.cli

import xyz.block.trailblaze.devices.TrailblazeDevicePort
import xyz.block.trailblaze.ui.TrailblazeDesktopUtil
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Tees System.out and System.err to a log file in `~/.trailblaze/desktop-logs/`.
 *
 * This ensures that `println()` output is captured regardless of how Trailblaze is
 * launched — IDE run config, JAR, or the shell wrapper script. The shell script's
 * own tee/nohup piping still works fine alongside this; the file will simply receive
 * writes from both layers (which is harmless since they are append-only).
 *
 * The log filename includes the HTTP port when a non-default port is used, matching
 * the shell script convention:
 *   - Default port → `trailblaze.log`
 *   - Custom port  → `trailblaze-{port}.log`
 *
 * Call [install] once, as early as possible in `main()`.
 */
object DesktopLogFileWriter {

  private const val LOG_DIR_NAME = "desktop-logs"
  private const val MAX_LOG_SIZE_BYTES = 10 * 1024 * 1024L // 10 MB

  private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

  /**
   * Installs a tee on System.out and System.err that also writes to a log file.
   *
   * @param httpPort The HTTP port this instance is running on. Used to choose the
   *   log filename so parallel instances don't clobber each other.
   */
  fun install(httpPort: Int = TrailblazeDevicePort.TRAILBLAZE_DEFAULT_HTTP_PORT) {
    val logDir = File(TrailblazeDesktopUtil.getDefaultAppDataDirectory(), LOG_DIR_NAME)
    logDir.mkdirs()

    val logFileName = if (httpPort == TrailblazeDevicePort.TRAILBLAZE_DEFAULT_HTTP_PORT) {
      "trailblaze.log"
    } else {
      "trailblaze-$httpPort.log"
    }
    val logFile = File(logDir, logFileName)

    rotateIfNeeded(logFile)

    val fileOut = FileOutputStream(logFile, /* append = */ true)

    System.setOut(PrintStream(TeeOutputStream(System.out, fileOut), /* autoFlush = */ true))
    System.setErr(PrintStream(TeeOutputStream(System.err, fileOut), /* autoFlush = */ true))

    println("[DesktopLogFileWriter] Logging to ${logFile.absolutePath}")
  }

  private fun rotateIfNeeded(logFile: File) {
    if (logFile.exists() && logFile.length() > MAX_LOG_SIZE_BYTES) {
      val oldFileBaseName = "${logFile.name}.old"
      val oldFile = File(logFile.parentFile, oldFileBaseName)
      if (oldFile.exists()) {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val timestampedOldFile = File(logFile.parentFile, "${oldFileBaseName}-$timestamp")
        if (!oldFile.renameTo(timestampedOldFile)) {
          oldFile.delete()
        }
      }
      if (!logFile.renameTo(oldFile)) {
        logFile.delete()
      }
    }
  }

  /**
   * An [OutputStream] that writes every byte to two underlying streams (tee).
   * The [primary] stream (original System.out/err) is always written to.
   * The [secondary] stream (log file) prepends a timestamp to each line.
   */
  private class TeeOutputStream(
    private val primary: OutputStream,
    private val secondary: OutputStream,
  ) : OutputStream() {

    // Buffer to accumulate a line so we can prepend a timestamp once per line.
    private val lineBuffer = StringBuilder()

    override fun write(b: Int) {
      primary.write(b)

      val ch = b.toChar()
      if (ch == '\n') {
        flushLine()
      } else {
        lineBuffer.append(ch)
      }
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
      primary.write(b, off, len)

      for (i in off until off + len) {
        val ch = b[i].toInt().toChar()
        if (ch == '\n') {
          flushLine()
        } else {
          lineBuffer.append(ch)
        }
      }
    }

    private fun flushLine() {
      val timestamp = LocalDateTime.now().format(timestampFormatter)
      val line = "[$timestamp] $lineBuffer\n"
      secondary.write(line.toByteArray())
      secondary.flush()
      lineBuffer.clear()
    }

    override fun flush() {
      primary.flush()
      // Don't flush partial lines to secondary — wait for newline
    }

    override fun close() {
      primary.close()
      secondary.close()
    }
  }
}
