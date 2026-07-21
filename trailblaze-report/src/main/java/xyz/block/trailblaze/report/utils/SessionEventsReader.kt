package xyz.block.trailblaze.report.utils

import java.io.BufferedReader
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import xyz.block.trailblaze.events.SessionEvents

/**
 * Reads the generic, pluggable session-events artifacts written under `<sessionDir>/events/` (see
 * `xyz.block.trailblaze.events.SessionEvents`) back into memory for the timeline / report.
 *
 * The reader is entirely producer-agnostic: it discovers every `<name>.ndjson` file, derives the
 * stream `name` from the file name, and returns the decoded payloads. It knows nothing
 * about any specific integration — adding a new event producer is purely a matter of dropping
 * correctly-named NDJSON files into the folder.
 *
 * Records are returned in **file arrival order**; each carries an epoch-millis order key
 * ([EventEntry.timeMs], extracted from `timeMs` or, for rich schemas, `timestampMs`) so the caller
 * (route/UI) can interleave streams on a single clock. The reader does not itself sort.
 *
 * Because the route polls these producer-owned files repeatedly, every read is bounded so a buggy or
 * hostile producer can't exhaust the daemon:
 * - per stream, by [maxEventsPerStream] and [maxBytesPerStream] (whichever hits first), flagged via
 *   [EventStream.truncated];
 * - per line, by [maxBytesPerStream] — a single over-cap line is never fully materialized or
 *   retained (it's read with a bounded buffer and skipped), so one giant line can't OOM the poll;
 * - per session, by [maxStreams] — at most that many event files are read per call.
 */
class SessionEventsReader(
  private val maxEventsPerStream: Int = DEFAULT_MAX_EVENTS_PER_STREAM,
  private val maxBytesPerStream: Long = DEFAULT_MAX_BYTES_PER_STREAM,
  private val maxStreams: Int = DEFAULT_MAX_STREAMS,
) {

  /** One decoded record: the order key plus the producer-specific payload. */
  data class EventEntry(val timeMs: Long, val data: JsonElement)

  /** All records for one named stream, in arrival order. */
  data class EventStream(
    val name: String,
    val count: Int,
    val truncated: Boolean,
    val events: List<EventEntry>,
  )

  /** Read every events stream under `<sessionDir>/events/`. Empty list if the folder is absent. */
  fun read(sessionDir: File): List<EventStream> {
    val eventsDir = File(sessionDir, SessionEvents.DIR_NAME)
    if (!eventsDir.isDirectory) return emptyList()
    return (eventsDir.listFiles() ?: emptyArray())
      .asSequence()
      .filter { it.isFile }
      // Parse/keep only well-formed event files BEFORE applying the cap, so junk files (e.g. a
      // leftover `.txt`) that sort early can't consume the stream budget and hide valid streams.
      .mapNotNull { file -> SessionEvents.parseFileName(file.name)?.let { file to it } }
      .sortedBy { it.first.name }
      // Bound total per-poll work: an open "drop a file" contract means a session could accumulate
      // arbitrarily many event files, and the route reads them on every poll.
      .take(maxStreams)
      .toList()
      .mapNotNull { (file, name) -> readStream(file, name) }
  }

  private fun readStream(file: File, name: String): EventStream? {
    val events = ArrayList<EventEntry>()
    var truncated = false
    file.bufferedReader(Charsets.UTF_8).use { reader ->
      // Count bytes SCANNED (every non-blank line), not just bytes accepted. Otherwise a producer
      // that writes a large malformed prefix (or an all-malformed file) would never trip the cap —
      // `events` stays empty — and every poll of this producer-owned file would scan it unbounded.
      var scannedBytes = 0L
      while (true) {
        val read = reader.readBoundedLine(maxBytesPerStream) ?: break // null at EOF
        // A line whose UTF-8 length exceeds the per-stream cap is never decoded, retained, OR fully
        // read (the bounded reader stops AT the budget without draining to the newline). It
        // truncates the stream at this point: records before it are kept, this line and everything
        // after are dropped. If it's the very first line, the stream is hidden (empty -> null).
        if (read.overflowed) {
          truncated = true
          break
        }
        val trimmed = read.text.trim()
        if (trimmed.isEmpty()) continue
        // `read.byteLen` is the line's UTF-8 size, already computed by the bounded reader, so we
        // don't re-encode here. After the first non-blank line, trip on the byte or event-count cap.
        if (scannedBytes > 0 &&
          (events.size >= maxEventsPerStream || scannedBytes + read.byteLen > maxBytesPerStream)
        ) {
          truncated = true
          break
        }
        scannedBytes += read.byteLen
        parseLine(trimmed)?.let { events.add(it) }
      }
    }
    if (events.isEmpty()) return null
    return EventStream(name = name, count = events.size, truncated = truncated, events = events)
  }

  /**
   * Parse one NDJSON line into an [EventEntry]. Handles both line shapes:
   * - the default envelope `{ timeMs, data }` — identified by the presence of [SessionEvents.TIME_FIELD]
   *   (`timeMs`); order key is `timeMs`, payload is the `data` field;
   * - a bare rich schema (e.g. `NetworkEvent`, which carries `timestampMs`) — order key falls back to
   *   `timestampMs`, and the WHOLE object is the payload.
   *
   * The envelope is gated on `timeMs` specifically (not on the mere presence of a `data` key) so a
   * rich schema that happens to carry its own top-level `data` field keeps all of its fields rather
   * than being unwrapped down to `data` alone. By the format contract, a top-level `timeMs` is
   * RESERVED for the envelope; a bare rich schema orders on its own `timestampMs` instead.
   *
   * Locked against the TS `decodeEventLine` (run-report-events.ts) by
   * `session-events-parity-fixtures.json` — see `SessionEventsParityFixturesTest`.
   */
  private fun parseLine(line: String): EventEntry? {
    val obj = runCatching { JSON.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return null
    val envelopeTime = obj[SessionEvents.TIME_FIELD]?.tryToLong()
    val timeMs = envelopeTime ?: obj[SessionEvents.LEGACY_TIME_FIELD]?.tryToLong() ?: 0L
    // Only unwrap `data` for an actual envelope (has `timeMs`); a bare rich-schema line is its own
    // payload. Fall back to the whole object if an envelope is missing its `data` field.
    val data = if (envelopeTime != null) (obj[ENVELOPE_DATA_FIELD] ?: obj) else obj
    return EventEntry(timeMs = timeMs, data = data)
  }

  private fun JsonElement.tryToLong(): Long? = runCatching { jsonPrimitive.longOrNull }.getOrNull()

  /**
   * One line read with a bounded buffer. [overflowed] is true when the line exceeded the byte
   * budget. [byteLen] is the line's UTF-8 size (valid only when not [overflowed]).
   */
  private class BoundedLine(val text: String, val overflowed: Boolean, val byteLen: Long)

  /**
   * Read a single line, accumulating at most [maxBytes] UTF-8 bytes. If adding the next character
   * would exceed the budget, the read stops IMMEDIATELY — the rest of the line is NOT consumed and
   * nothing over-budget is retained — and returns [BoundedLine.overflowed] = true. The caller stops
   * the stream on overflow, so the reader is never read from again after that. Returns null at EOF
   * with no further input. `\r` is dropped so CRLF and LF both work.
   *
   * Byte width per UTF-16 code unit is the conservative UTF-8 estimate (1 / 2 / 3 bytes; a surrogate
   * pair counts as 6 rather than its true 4, which only makes the cap stricter — never looser).
   */
  private fun BufferedReader.readBoundedLine(maxBytes: Long): BoundedLine? {
    val sb = StringBuilder()
    var byteLen = 0L
    var sawAny = false
    while (true) {
      val c = read()
      if (c == -1) {
        if (!sawAny) return null
        break
      }
      sawAny = true
      if (c == '\n'.code) break
      if (c == '\r'.code) continue
      val width = if (c < 0x80) 1 else if (c < 0x800) 2 else 3
      if (byteLen + width > maxBytes) {
        // Over budget: stop now, retaining nothing and draining nothing.
        return BoundedLine("", overflowed = true, byteLen = 0L)
      }
      byteLen += width
      sb.append(c.toChar())
    }
    return BoundedLine(sb.toString(), overflowed = false, byteLen = byteLen)
  }

  companion object {
    private const val ENVELOPE_DATA_FIELD = "data"
    const val DEFAULT_MAX_EVENTS_PER_STREAM: Int = 2_000
    const val DEFAULT_MAX_BYTES_PER_STREAM: Long = 2_000_000L
    const val DEFAULT_MAX_STREAMS: Int = 256

    private val JSON: Json = Json {
      ignoreUnknownKeys = true
      isLenient = true
    }
  }
}
