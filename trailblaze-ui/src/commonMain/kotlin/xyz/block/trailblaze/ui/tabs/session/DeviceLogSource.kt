package xyz.block.trailblaze.ui.tabs.session

import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.ui.utils.FormattingUtils

/**
 * [SessionLogSource] for whatever was captured to `device.log` during a session — Android
 * logcat or iOS Simulator system logs (and, eventually, web console output) depending on the
 * session's platform.
 *
 * Use [DeviceLogSource.forPlatform] when the session's platform is known up front (preferred,
 * since it routes straight to the right format-specific parser). Use [DeviceLogSource.AutoDetect]
 * for legacy or platform-agnostic call sites — it tries Android first, falls back to iOS, and
 * gives up if neither matches.
 */
data class DeviceLogSource private constructor(
  /**
   * Platform hint controlling which parser to dispatch to. Null = auto-detect both formats.
   *
   * `data class` is intentional: `SessionLogsPanel` keys its `remember(source, rawLogContent)`
   * cache on this instance, so identity-based equality would invalidate the cache on every
   * recomposition (forPlatform(ANDROID) returns a new instance each call). With value
   * equality, `forPlatform(ANDROID) == forPlatform(ANDROID)` and the parsed lines stay cached.
   */
  private val platform: TrailblazeDevicePlatform?,
) : SessionLogSource {

  override val id: String = "device"
  override val displayName: String = "Device Logs"

  override val filterableLevels: List<LogLevel> =
    listOf(
      LogLevel.VERBOSE,
      LogLevel.DEBUG,
      LogLevel.INFO,
      LogLevel.WARN,
      LogLevel.ERROR,
      LogLevel.FATAL,
      LogLevel.UNKNOWN,
    )

  override fun parse(rawText: String): ParsedLog {
    val allLines = rawText.lines()
    // Keep the most recent lines when capping — for high-volume iOS captures the user
    // cares about what just happened, not the start of the session.
    val rawLines = allLines.takeLast(MAX_DISPLAY_LINES)
    val firstEpoch = rawLines.firstNotNullOfOrNull { parseEpochMs(it) }
    val parsedLines =
      rawLines.map { line ->
        val epochMs = parseEpochMs(line)
        val content = stripTimestamp(line)
        val relativeMs =
          if (epochMs != null && firstEpoch != null) epochMs - firstEpoch else null
        ParsedLogLine(
          raw = line,
          timestampDisplay = relativeMs?.let { FormattingUtils.formatRelativeTimeWithMillis(it) },
          content = content,
          epochMs = epochMs,
          level = parseLogLevel(content),
        )
      }
    return ParsedLog(
      lines = parsedLines,
      totalRawLineCount = allLines.size,
      truncated = allLines.size > MAX_DISPLAY_LINES,
    )
  }

  /** Routes to the right per-platform epoch parser. */
  private fun parseEpochMs(line: String): Long? = when (platform) {
    TrailblazeDevicePlatform.ANDROID -> AndroidDeviceLogParser.parseEpochMs(line)
    TrailblazeDevicePlatform.IOS -> IosDeviceLogParser.parseEpochMs(line)
    // Auto-detect: Android format is unambiguous, try it first; fall back to iOS.
    else -> AndroidDeviceLogParser.parseEpochMs(line) ?: IosDeviceLogParser.parseEpochMs(line)
  }

  /** Routes to the right per-platform timestamp stripper, returning the line unchanged on no match. */
  private fun stripTimestamp(line: String): String = when (platform) {
    TrailblazeDevicePlatform.ANDROID -> AndroidDeviceLogParser.splitTimestamp(line)?.second
    TrailblazeDevicePlatform.IOS -> IosDeviceLogParser.splitTimestamp(line)?.second
    else ->
      AndroidDeviceLogParser.splitTimestamp(line)?.second
        ?: IosDeviceLogParser.splitTimestamp(line)?.second
  } ?: line.trimStart()

  /**
   * Routes to the right per-platform level parser. Android always returns *some* level
   * (UNKNOWN if it can't tell); iOS returns null when it can't recognize the type code,
   * which is when we fall back to Android-style heuristics.
   */
  private fun parseLogLevel(content: String): LogLevel = when (platform) {
    TrailblazeDevicePlatform.ANDROID -> AndroidDeviceLogParser.parseLogLevel(content)
    TrailblazeDevicePlatform.IOS ->
      IosDeviceLogParser.parseLogLevel(content) ?: AndroidDeviceLogParser.parseLogLevel(content)
    else ->
      IosDeviceLogParser.parseLogLevel(content) ?: AndroidDeviceLogParser.parseLogLevel(content)
  }

  companion object {
    /** Cap rendered lines so the LazyColumn stays responsive on huge captures. */
    private const val MAX_DISPLAY_LINES = 10_000

    /** Backwards-compatible singleton for callers that don't know the platform. */
    val AutoDetect: DeviceLogSource = DeviceLogSource(platform = null)

    /**
     * Returns a parser routed to [platform]'s log format. Pass `null` to use [AutoDetect].
     */
    fun forPlatform(platform: TrailblazeDevicePlatform?): DeviceLogSource =
      if (platform == null) AutoDetect else DeviceLogSource(platform)
  }
}
