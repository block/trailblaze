package xyz.block.trailblaze.ui.tabs.session

import androidx.compose.ui.graphics.Color

/**
 * A timeline-synced log source that can be plugged into [SessionLogsPanel].
 *
 * Add a new source by implementing this interface — for example: Android logcat
 * (currently the only one), iOS Simulator system logs, network traffic, or analytics
 * events. The panel renders a single source today and uses [displayName] in its header;
 * once a second source is registered we'll add a tab strip / source selector.
 *
 * **Note on placement:** this contract lives in `trailblaze-ui` because today's only
 * implementer ([DeviceLogSource]) is consumed exclusively by the Compose panel and
 * because [LogLevel] currently carries a UI [androidx.compose.ui.graphics.Color]. When
 * a server-side source needs to implement this (e.g., a network-capture parser in
 * `trailblaze-server` that produces `ParsedLog` from an NDJSON feed), split out the
 * pure data tier ([ParsedLog], [ParsedLogLine], a colorless severity bucket) into
 * `trailblaze-models` or `trailblaze-common` and keep the UI mapping
 * (`LogLevel.color`, `LogLevel.label`) in `trailblaze-ui`.
 */
interface SessionLogSource {
  /** Stable id (e.g., "device", "network", "analytics"). */
  val id: String

  /** Header label, e.g., "Device Logs", "Network", "Analytics". */
  val displayName: String

  /**
   * Severity buckets shown as filter chips in the panel header. Empty (the default)
   * means the source has no level concept — chips are hidden for it. Logcat declares
   * V/D/I/W/E/F; sources like Network or Analytics typically leave this empty.
   */
  val filterableLevels: List<LogLevel> get() = emptyList()

  /**
   * Parses raw text into structured log lines. Each [ParsedLogLine] carries an
   * optional [ParsedLogLine.epochMs] used by the panel for timeline sync and active-event
   * highlighting.
   */
  fun parse(rawText: String): ParsedLog
}

/**
 * Result of parsing a raw log payload. Carries the rendered lines plus enough metadata
 * for the panel to show a "showing first N of M" indicator when capped, and to surface
 * a parse-failure count when a structured-format source dropped malformed lines.
 */
data class ParsedLog(
  val lines: List<ParsedLogLine>,
  /** Total raw line count before truncation. */
  val totalRawLineCount: Int,
  /** True when [lines] was capped below [totalRawLineCount]. */
  val truncated: Boolean,
  /**
   * Number of raw lines that the parser tried to interpret and could not (e.g. a torn
   * trailing JSON line from a crash mid-write, or a line whose schema doesn't match).
   * Always 0 for free-text sources that accept every line; non-zero for structured
   * sources like NDJSON that reject unparseable input. Surfaced in the panel header so
   * "100 events captured but 30 unparseable" doesn't present as silently missing data.
   */
  val malformedLineCount: Int = 0,
)

/** A single rendered log entry. */
data class ParsedLogLine(
  /** The full original line text (used for free-text filter matching). */
  val raw: String,
  /** Formatted relative timestamp for display (e.g. "0:05.387"), or null if untimed. */
  val timestampDisplay: String?,
  /** Display content with the raw timestamp prefix stripped. */
  val content: String,
  /** Epoch millis from the line; drives timeline sync + activeEvent highlighting. */
  val epochMs: Long?,
  /** Severity bucket — sources without a level concept emit [LogLevel.UNKNOWN]. */
  val level: LogLevel,
)

/**
 * One source's raw content as fed into [SessionLogsPanel]. [rawContent] is nullable so
 * callers can pass entries whose data may not exist for the current session
 * (web-only sessions have no `device.log`; mobile sessions have no `network.ndjson`).
 * The panel filters entries with null/blank content out of the tab strip before rendering.
 *
 * Replaces a prior pair-of-parameters API (`sources: List<…>` + `rawContentBySourceId: Map<…>`)
 * — single-list keeps the source/content pairing explicit and unambiguous.
 */
data class SessionLogEntry(
  val source: SessionLogSource,
  val rawContent: String?,
)

/**
 * Universal severity vocabulary across sources. Sources can map their own concepts
 * onto this (network: 4xx/5xx → ERROR; analytics: any event → UNKNOWN; logcat: V/D/I/W/E/F).
 */
enum class LogLevel(val label: String, val color: Color) {
  VERBOSE("V", Color(0xFF607D8B)),
  DEBUG("D", Color(0xFF4FC3F7)),
  INFO("I", Color(0xFF66BB6A)),
  WARN("W", Color(0xFFFFA726)),
  ERROR("E", Color(0xFFEF5350)),
  FATAL("F", Color(0xFFD32F2F)),
  UNKNOWN("?", Color(0xFF78909C)),
}
