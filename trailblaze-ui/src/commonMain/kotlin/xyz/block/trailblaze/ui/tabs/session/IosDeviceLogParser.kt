package xyz.block.trailblaze.ui.tabs.session

/**
 * iOS Simulator system-log parsing helpers used by [DeviceLogSource] for the panel render path.
 *
 * iOS captures use the `xcrun simctl spawn log stream --style compact` format:
 * ```
 * 2026-03-10 14:23:45.678901-0700  MyApp[12345]: (subsystem) message
 * ```
 *
 * **Important — this is the panel-side parser, not the canonical one.** The accurate
 * absolute-epoch parser lives in `LogcatParser` (in `trailblaze-capture`, JVM-only) and is
 * used for the MCP `session(action=DEVICE_LOGS)` slicer where correct UTC math matters.
 * This parser is shared with the Compose Multiplatform panel which also targets WASM, so
 * `java.time.DateTimeFormatter` is unavailable. The epoch math here is a deliberately rough
 * approximation (30-day months, 365-day years, no timezone) used **only for relative scroll
 * positioning** within the panel — never compare it to host-clock millis or use it for
 * cross-session math.
 *
 * If you need accurate iOS timestamps in commonMain, the path forward is to add a
 * `kotlinx-datetime`-based parser in this util and have both `LogcatParser` and the panel
 * delegate to it.
 */
internal object IosDeviceLogParser {

  /**
   * Tries to extract an iOS-format epoch (rough approximation, see class KDoc) from [line].
   * Returns null if the line doesn't begin with the iOS compact timestamp format.
   */
  fun parseEpochMs(line: String): Long? {
    val trimmed = line.trimStart()
    if (trimmed.length < 26 || trimmed[4] != '-' || trimmed[7] != '-' || trimmed[10] != ' ') {
      return null
    }
    return try {
      val year = trimmed.substring(0, 4).toInt()
      val month = trimmed.substring(5, 7).toInt()
      val day = trimmed.substring(8, 10).toInt()
      val hour = trimmed.substring(11, 13).toInt()
      val minute = trimmed.substring(14, 16).toInt()
      val second = trimmed.substring(17, 19).toInt()
      // Rough epoch — see class KDoc. Only useful for relative ordering inside one session.
      val daysSinceEpoch = (year - 1970) * 365L + (month - 1) * 30L + day
      ((daysSinceEpoch * 86400 + hour * 3600 + minute * 60 + second) * 1000)
    } catch (_: NumberFormatException) {
      null
    }
  }

  /**
   * If [line] begins with the iOS compact timestamp prefix, returns
   * `(timestampPrefix, contentWithoutPrefix)`; otherwise returns null so a different parser
   * can take a turn.
   */
  fun splitTimestamp(line: String): Pair<String, String>? {
    val trimmed = line.trimStart()
    if (trimmed.length < 26 ||
      trimmed[4] != '-' ||
      trimmed[7] != '-' ||
      trimmed[10] != ' ' ||
      trimmed[13] != ':'
    ) {
      return null
    }
    val microEnd = trimmed.indexOf(' ', 20)
    return if (microEnd > 0) {
      trimmed.substring(0, microEnd) to trimmed.substring(microEnd).trimStart()
    } else {
      null
    }
  }

  /**
   * Parses iOS compact format two-letter type codes from [content].
   * `Ft`/`Er`/`Db`/`In`/`Df`/`Nt` map to the universal [LogLevel] vocabulary.
   * Returns null when the line doesn't begin with one of the recognized codes so an
   * Android-style level parser can be tried instead.
   */
  fun parseLogLevel(content: String): LogLevel? {
    if (content.length < 2) return null
    val code = content.substring(0, 2)
    return when {
      code.equals("Ft", ignoreCase = true) -> LogLevel.FATAL
      code.equals("Er", ignoreCase = true) -> LogLevel.ERROR
      code.equals("Db", ignoreCase = true) -> LogLevel.DEBUG
      code.equals("In", ignoreCase = true) -> LogLevel.INFO
      code.equals("Df", ignoreCase = true) -> LogLevel.INFO // Default ≈ Info
      code.equals("Nt", ignoreCase = true) -> LogLevel.INFO // Notice ≈ Info
      else -> null
    }
  }
}
