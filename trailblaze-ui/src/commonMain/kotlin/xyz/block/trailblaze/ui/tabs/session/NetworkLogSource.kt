package xyz.block.trailblaze.ui.tabs.session

import xyz.block.trailblaze.network.NetworkEvent
import xyz.block.trailblaze.network.Phase
import xyz.block.trailblaze.ui.utils.FormattingUtils
import xyz.block.trailblaze.ui.utils.JsonDefaults
import xyz.block.trailblaze.util.Console

/**
 * [SessionLogSource] for `<session-dir>/network.ndjson` — one [ParsedLogLine] per
 * captured [NetworkEvent].
 *
 * Format: `<METHOD> <STATUS|→|FAILED> [<duration>ms] <urlPath>`. REQUEST_START shows
 * the arrow; RESPONSE_END shows the status code; FAILED shows the literal token.
 *
 * Severity mapping:
 * - 4xx / 5xx response codes and FAILED → [LogLevel.ERROR]
 * - everything else → [LogLevel.INFO]
 *
 * [filterableLevels] is empty so the V/D/I/W/E/F chips don't render — those are
 * a logcat concept and would be noise for network traffic.
 *
 * Malformed NDJSON lines (e.g. a torn trailing line from a JVM crash mid-write, or
 * one whose schema doesn't decode) are skipped but counted into
 * [ParsedLog.malformedLineCount] and a sample of the first few are logged via
 * [Console] — same observability pattern as `WebReadNetworkEventsTool`.
 */
object NetworkLogSource : SessionLogSource {
  override val id: String = "network"
  override val displayName: String = "Network"
  override val filterableLevels: List<LogLevel> = emptyList()

  override fun parse(rawText: String): ParsedLog {
    val rawLines = rawText.lineSequence().filter { it.isNotBlank() }.toList()
    // Keep the most recent events when capping — for chatty pages the user cares
    // about what just happened, not the start of the session.
    val keptLines = rawLines.takeLast(MAX_DISPLAY_LINES)
    var malformedCount = 0
    val parsedLines = keptLines.mapNotNull { line ->
      val event = runCatching { JsonDefaults.LENIENT.decodeFromString(NetworkEvent.serializer(), line) }
        .getOrNull()
      if (event == null) {
        // Cap the per-session log spam: surface the first few so a developer can debug
        // a corrupt file, then suppress to keep stdout from drowning on a 50% torn file.
        if (malformedCount < MALFORMED_LOG_LIMIT) {
          Console.log("NetworkLogSource: skipped malformed NDJSON line: ${line.take(120)}")
        }
        malformedCount++
        return@mapNotNull null
      }
      line to event
    }
    val firstEpoch = parsedLines.firstOrNull()?.second?.timestampMs
    val rendered = parsedLines.map { (raw, event) ->
      val relativeMs = if (firstEpoch != null) event.timestampMs - firstEpoch else null
      ParsedLogLine(
        raw = raw,
        timestampDisplay = relativeMs?.let { FormattingUtils.formatRelativeTimeWithMillis(it) },
        content = formatEvent(event),
        epochMs = event.timestampMs,
        level = mapLevel(event),
      )
    }
    return ParsedLog(
      lines = rendered,
      totalRawLineCount = rawLines.size,
      truncated = rawLines.size > MAX_DISPLAY_LINES,
      malformedLineCount = malformedCount,
    )
  }

  /** Render an event as a single line: `POST 204 [90ms] /v1/cdp/batch`. */
  internal fun formatEvent(event: NetworkEvent): String = buildString {
    append(event.method)
    append(' ')
    when (event.phase) {
      Phase.REQUEST_START -> append('→')
      Phase.RESPONSE_END -> append(event.statusCode ?: "?")
      Phase.FAILED -> append("FAILED")
    }
    event.durationMs?.let {
      append(" [")
      append(it)
      append("ms]")
    }
    append(' ')
    append(event.urlPath.ifEmpty { event.url })
  }

  /**
   * Maps the event onto the panel's universal severity vocabulary.
   * 4xx/5xx and FAILED are surfaced as ERROR so they pop in the colored bar; everything
   * else is INFO. UNKNOWN is reserved for entries the source can't classify at all.
   */
  internal fun mapLevel(event: NetworkEvent): LogLevel = when (event.phase) {
    Phase.FAILED -> LogLevel.ERROR
    Phase.REQUEST_START -> LogLevel.INFO
    Phase.RESPONSE_END -> {
      val status = event.statusCode
      if (status != null && status >= 400) LogLevel.ERROR else LogLevel.INFO
    }
  }

  /**
   * Cap rendered lines so the LazyColumn stays responsive on chatty pages.
   *
   * Lower than [DeviceLogSource]'s 10_000 because each line here is much wider — a full
   * `NetworkEvent` JSON line is ~1–2 KB once you include URLs and headers, vs ~150 B for
   * a typical logcat line. 5_000 events ≈ 5–10 MB of in-memory string, comparable to
   * 10_000 logcat lines, which is the responsive-LazyColumn ceiling we're targeting.
   *
   * For long sessions (e.g. a 30-minute test on a chatty SPA) the most-recent-N cap can
   * silently drop earlier traffic from the view. Lazy/streaming/windowed strategies are
   * tracked in https://github.com/block/trailblaze/issues/126 — out of scope for the
   * initial wiring; pick up there.
   */
  private const val MAX_DISPLAY_LINES: Int = 5_000

  /** Cap per-session malformed-line warnings so a corrupt file can't flood stdout. */
  private const val MALFORMED_LOG_LIMIT: Int = 5
}
