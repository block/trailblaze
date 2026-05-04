package xyz.block.trailblaze.capture.logcat

import java.io.File
import java.io.IOException
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoField
import xyz.block.trailblaze.capture.model.CaptureFilenames
import xyz.block.trailblaze.util.Console

/**
 * A single parsed logcat line with an extracted epoch timestamp.
 */
data class LogcatLine(
  /** Epoch milliseconds extracted from the log line, or null if unparseable. */
  val epochMs: Long?,
  /** The raw log line text. */
  val text: String,
)

/**
 * Parses and slices device log files by time range.
 *
 * Supports both Android epoch format (`1772846521.234`) and iOS datetime format
 * (`2026-03-10 14:23:45.678901-0700`). iOS log capture is supported at the capture-session
 * level when its toggle is enabled (off by default — high log volume); the parser also
 * handles previously-captured or imported device-log files regardless of the current toggle.
 */
object LogcatParser {

  /**
   * Returns true if the given filename looks like a device log file.
   * Matches the canonical [CaptureFilenames.DEVICE_LOG] plus legacy filenames
   * (`logcat.txt`, `system_log.txt`, etc.) for backward compatibility with older session
   * folders. Same filename is used for Android, iOS, and (eventually) web — content format
   * varies per platform but the filename is normalized.
   */
  fun isDeviceLogFile(fileName: String): Boolean {
    val lower = fileName.lowercase()
    return lower == CaptureFilenames.DEVICE_LOG ||
      lower.contains("logcat") ||
      lower.contains("system_log")
  }

  /**
   * Finds the first device log file in a directory, or null if none exists.
   */
  fun findDeviceLogFile(dir: File): File? {
    if (!dir.exists()) return null
    return dir.listFiles()?.firstOrNull { isDeviceLogFile(it.name) }
  }

  // Android epoch: "1772846521.234  5432  5432 D ..." — allow optional leading whitespace
  // (logcat occasionally indents wrapped/continuation lines).
  private val ANDROID_EPOCH_REGEX = Regex("""^\s*(\d{10}\.\d{3})\s""")

  // iOS compact: "2026-03-10 14:23:45.678901-0700  MyApp[12345]: ..." — allow optional
  // leading whitespace, and accept any width of fractional seconds (xcrun simctl trims
  // trailing zeros, so the fraction is not always exactly 6 digits).
  private val IOS_DATETIME_REGEX =
    Regex("""^\s*(\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d+[+-]\d{4})\s""")

  /**
   * Variable-width fractional-seconds formatter — matches anywhere from 1 to 9 digits
   * after the dot. The fixed `"SSSSSSxx"` pattern fails on real iOS output that drops
   * trailing zeros (e.g., `…45.123-0700`).
   */
  private val IOS_FORMATTER =
    DateTimeFormatterBuilder()
      .appendPattern("yyyy-MM-dd HH:mm:ss")
      .appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true)
      .appendPattern("xx")
      .toFormatter()

  /**
   * Parses a logcat line and extracts its epoch timestamp in milliseconds.
   */
  fun parseLine(line: String): LogcatLine {
    // Try Android epoch format first (most common)
    ANDROID_EPOCH_REGEX.find(line)?.let { match ->
      val epochSeconds = match.groupValues[1].toDoubleOrNull()
      if (epochSeconds != null) {
        return LogcatLine(epochMs = (epochSeconds * 1000).toLong(), text = line)
      }
    }

    // Try iOS datetime format
    IOS_DATETIME_REGEX.find(line)?.let { match ->
      try {
        val odt = OffsetDateTime.parse(match.groupValues[1], IOS_FORMATTER)
        return LogcatLine(epochMs = odt.toInstant().toEpochMilli(), text = line)
      } catch (_: DateTimeParseException) {
        // Fall through
      }
    }

    return LogcatLine(epochMs = null, text = line)
  }

  /**
   * Reads a logcat file and returns all parsed lines. Returns an empty list (and logs to
   * the daemon log) when the file cannot be read — callers should not crash on permission
   * errors or transient I/O failures during a live capture.
   */
  fun parseFile(logcatFile: File): List<LogcatLine> {
    if (!logcatFile.exists() || logcatFile.length() == 0L) return emptyList()
    return try {
      logcatFile.readLines().map { parseLine(it) }
    } catch (e: IOException) {
      Console.log("LogcatParser: unable to read ${logcatFile.absolutePath}: ${e.message}")
      emptyList()
    }
  }

  /**
   * Extracts logcat lines that fall within a time window.
   *
   * @param logcatFile the device log file (`device.log`)
   * @param startMs start of the window (epoch millis, inclusive). Must be ≤ [endMs].
   * @param endMs end of the window (epoch millis, inclusive)
   * @param paddingMs extra padding on each side of the window (default 500ms)
   * @return lines within the time range, preserving order
   */
  fun sliceByTimeRange(
    logcatFile: File,
    startMs: Long,
    endMs: Long,
    paddingMs: Long = 500,
  ): List<LogcatLine> {
    require(startMs <= endMs) { "startMs ($startMs) must be <= endMs ($endMs)" }
    val lines = parseFile(logcatFile)
    val windowStart = startMs - paddingMs
    val windowEnd = endMs + paddingMs

    return lines.filter { line ->
      val ts = line.epochMs ?: return@filter false
      ts in windowStart..windowEnd
    }
  }

  /**
   * Extracts logcat lines that fall within a time window, returning raw text.
   */
  fun sliceTextByTimeRange(
    logcatFile: File,
    startMs: Long,
    endMs: Long,
    paddingMs: Long = 500,
  ): String = sliceByTimeRange(logcatFile, startMs, endMs, paddingMs).joinToString("\n") { it.text }
}
