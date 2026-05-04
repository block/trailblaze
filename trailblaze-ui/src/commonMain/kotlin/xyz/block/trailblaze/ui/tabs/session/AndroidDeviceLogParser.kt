package xyz.block.trailblaze.ui.tabs.session

/**
 * Android logcat parsing helpers used by [DeviceLogSource] for the panel render path.
 *
 * Android captures use the epoch-prefixed logcat format produced by
 * `adb logcat -v epoch`:
 * ```
 * 1772846521.234  5432  5432 D MyTag : message
 * ```
 *
 * Android timestamps are unambiguous (seconds-since-epoch + millis), so the same math here
 * matches what `LogcatParser` does on the server side — there is no rough/accurate divergence
 * for Android the way there is for iOS.
 */
internal object AndroidDeviceLogParser {

  /**
   * Tries to extract the Android logcat epoch from [line]. Returns null if the line doesn't
   * begin with a 10-digit-seconds + dot + 3-digit-millis prefix.
   */
  fun parseEpochMs(line: String): Long? {
    val trimmed = line.trimStart()
    val spaceIndex = trimmed.indexOf(' ')
    if (spaceIndex < 10) return null
    val epochStr = trimmed.substring(0, spaceIndex)
    val dotIndex = epochStr.indexOf('.')
    if (dotIndex != 10) return null
    val seconds = epochStr.substring(0, dotIndex).toLongOrNull() ?: return null
    val millis = epochStr.substring(dotIndex + 1).take(3).padEnd(3, '0').toLongOrNull()
      ?: return null
    return seconds * 1000 + millis
  }

  /**
   * If [line] begins with an Android epoch prefix, returns `(epochPrefix, content)`;
   * otherwise returns null.
   */
  fun splitTimestamp(line: String): Pair<String, String>? {
    val trimmed = line.trimStart()
    val spaceIndex = trimmed.indexOf(' ')
    if (spaceIndex < 10) return null
    val candidate = trimmed.substring(0, spaceIndex)
    if (candidate.indexOf('.') != 10) return null
    return candidate to trimmed.substring(spaceIndex).trimStart()
  }

  /**
   * Parses the Android logcat single-letter priority (V/D/I/W/E/F) from [content].
   * Falls back to keyword heuristics ("Exception", "ANR", "FATAL") when the priority
   * letter isn't found in canonical position.
   */
  fun parseLogLevel(content: String): LogLevel {
    if (content.contains(" F ") || content.contains(" F/") || content.contains("FATAL")) {
      return LogLevel.FATAL
    }
    if (content.contains(" E ") || content.contains(" E/") ||
      content.contains("Exception") || content.contains("ANR")
    ) {
      return LogLevel.ERROR
    }
    if (content.contains(" W ") || content.contains(" W/")) return LogLevel.WARN
    if (content.contains(" I ") || content.contains(" I/")) return LogLevel.INFO
    if (content.contains(" D ") || content.contains(" D/")) return LogLevel.DEBUG
    if (content.contains(" V ") || content.contains(" V/")) return LogLevel.VERBOSE
    return LogLevel.UNKNOWN
  }
}
