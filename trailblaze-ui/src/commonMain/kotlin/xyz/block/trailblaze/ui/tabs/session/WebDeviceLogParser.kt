package xyz.block.trailblaze.ui.tabs.session

/**
 * Browser-console log parsing used by [DeviceLogSource] for the panel render path on web
 * sessions — the web counterpart to [AndroidDeviceLogParser] (logcat) and [IosDeviceLogParser]
 * (iOS Simulator system log).
 *
 * Web captures (`WebConsoleCapture` in `trailblaze-playwright`) append one line per console
 * message to `device.log`:
 * ```
 * 2026-06-22 14:23:45.678 [error] Failed to load resource: net::ERR_FAILED
 * ```
 *
 * The timestamp prefix is the same fixed-width `yyyy-MM-dd HH:mm:ss.SSS` shape the iOS compact
 * format uses, so the rough relative-epoch math here mirrors [IosDeviceLogParser] — see its
 * KDoc for why it's a deliberate approximation (no `java.time` in the WASM panel; useful only
 * for relative scroll positioning within one session, never host-clock math). The bracketed
 * `[type]` tag carries the browser console level.
 */
internal object WebDeviceLogParser {

  /**
   * Tries to extract a rough epoch (see class KDoc) from [line]. Returns null if the line
   * doesn't begin with the `yyyy-MM-dd HH:mm:ss` timestamp prefix.
   */
  fun parseEpochMs(line: String): Long? {
    val trimmed = line.trimStart()
    if (!hasTimestampPrefix(trimmed)) return null
    return try {
      val year = trimmed.substring(0, 4).toInt()
      val month = trimmed.substring(5, 7).toInt()
      val day = trimmed.substring(8, 10).toInt()
      val hour = trimmed.substring(11, 13).toInt()
      val minute = trimmed.substring(14, 16).toInt()
      val second = trimmed.substring(17, 19).toInt()
      // The web format is `yyyy-MM-dd HH:mm:ss.SSS` — WebConsoleCapture's formatter always emits
      // exactly three millisecond digits after the `.` at offset 19, so include them for
      // sub-second resolution (unlike the iOS parser, which faces variable-width micros and drops
      // them). Tolerate a missing/short fractional part defensively.
      val millis =
        if (trimmed.length >= 23 && trimmed[19] == '.') {
          trimmed.substring(20, 23).toInt()
        } else {
          0
        }
      // Rough epoch — see class KDoc. Only useful for relative ordering inside one session.
      val daysSinceEpoch = (year - 1970) * 365L + (month - 1) * 30L + day
      ((daysSinceEpoch * 86400 + hour * 3600 + minute * 60 + second) * 1000) + millis
    } catch (_: NumberFormatException) {
      null
    }
  }

  /**
   * If [line] begins with the timestamp prefix, returns `(timestampPrefix, contentWithoutPrefix)`;
   * otherwise null so a different parser can take a turn. The content is the `[type] message`
   * remainder.
   */
  fun splitTimestamp(line: String): Pair<String, String>? {
    val trimmed = line.trimStart()
    if (!hasTimestampPrefix(trimmed)) return null
    // Timestamp is `yyyy-MM-dd HH:mm:ss.SSS` — date and time separated by one space, then a
    // space before the `[type]` tag. Split after that second space.
    val firstSpace = trimmed.indexOf(' ')
    val secondSpace = if (firstSpace >= 0) trimmed.indexOf(' ', firstSpace + 1) else -1
    return if (secondSpace > 0) {
      trimmed.substring(0, secondSpace) to trimmed.substring(secondSpace).trimStart()
    } else {
      null
    }
  }

  /**
   * Maps the leading `[type]` tag (Playwright `ConsoleMessage.type()`) to the universal
   * [LogLevel] vocabulary. Returns null only when [content] has no `[...]` tag at all (so an
   * Android-style heuristic parser can be tried instead). A well-formed tag we don't recognize
   * (e.g. `[table]`, `[group]`) maps to [LogLevel.UNKNOWN] — the line *did* come from the web
   * console, so there's no point falling through to the Android heuristic.
   */
  fun parseLogLevel(content: String): LogLevel? {
    if (!content.startsWith("[")) return null
    val close = content.indexOf(']')
    if (close < 1) return null
    return when (content.substring(1, close).lowercase()) {
      "error", "assert" -> LogLevel.ERROR
      "warning", "warn" -> LogLevel.WARN
      "debug" -> LogLevel.DEBUG
      "info", "log" -> LogLevel.INFO
      "trace" -> LogLevel.VERBOSE
      else -> LogLevel.UNKNOWN
    }
  }

  /** `yyyy-MM-dd HH:mm:ss` prefix shape: digits with `-`,`-`,` `,`:` at fixed offsets. */
  private fun hasTimestampPrefix(trimmed: String): Boolean =
    trimmed.length >= 19 &&
      trimmed[4] == '-' &&
      trimmed[7] == '-' &&
      trimmed[10] == ' ' &&
      trimmed[13] == ':' &&
      trimmed[16] == ':'
}
