package xyz.block.trailblaze.events

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.TimeSource
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import xyz.block.trailblaze.logs.model.DeviceClockSync
import xyz.block.trailblaze.logs.model.DeviceClockTrack
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.scripting.host.TrailblazeHostFunctionException
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.trailhead.EventMatch
import xyz.block.trailblaze.trailhead.TrailEventsHostFunctions
import xyz.block.trailblaze.trailhead.TrailheadEventLog
import xyz.block.trailblaze.trailhead.TrailheadRuns

/**
 * The framework host functions behind `ctx.events` (`list`, `query`, `mark`): any tool, at any
 * point in a run, reading the session's event streams as they stand, and optionally waiting for a
 * record to land.
 *
 * Built to be cheap where it matters most, an on-device run with no host:
 *  - **A starting point is a byte offset.** Streams only grow, in arrival order, so "since this
 *    mark" is "after this many bytes". A mark costs one `stat` per stream and reads nothing.
 *  - **Lines are rejected as bytes.** [EventStreamScanner] skips every line missing a literal the
 *    match needs, so only candidates are decoded and run through [EventMatch].
 *  - **A wait is a `stat` per tick.** On a miss the scan keeps its offset and re-reads only when the
 *    file grew; host functions suspend, so the wait is a coroutine `delay`, not a script loop.
 *
 * Which streams exist is the trailmap's business: this reads whatever is under the session's
 * `events/` (plus the root `network` capture) and knows no producer by name.
 */
object SessionEventsHostFunctions {

  const val LIST: String = "trailblaze.sessionEvents.list"
  const val QUERY: String = "trailblaze.sessionEvents.query"
  const val MARK: String = "trailblaze.sessionEvents.mark"

  const val DEFAULT_LIMIT: Int = 100
  const val MAX_LIMIT: Int = 1000

  /**
   * Most record text one query returns, counted by line length. On a device the whole result lives in
   * the instrumentation heap, so a result that reaches it stops early with `truncated: true`; pass
   * its cursor back as `after` for the rest.
   */
  const val MAX_RESULT_BYTES: Long = 4_000_000

  /**
   * The longest record a query reads; a longer one is skipped unread and makes the result
   * incomplete. As large as the result budget, so any line one result can hold is read: a capture's
   * routine records (an app's sync response often runs past 1 MB) are not a loss, and memory
   * stays bounded by the budget, since a page holds at most one record past it.
   */
  const val MAX_LINE_BYTES: Int = MAX_RESULT_BYTES.toInt()
  const val MAX_WAIT_MS: Long = 300_000
  private const val FIRST_POLL_MS = 100L
  private const val MAX_POLL_MS = 1_000L

  /** Starting points the framework knows without a mark. */
  const val SINCE_SESSION: String = "session"
  const val SINCE_TRAILHEAD: String = "trailhead"
  const val SINCE_TRAIL: String = "trail"

  fun handles(name: String): Boolean = name == LIST || name == QUERY || name == MARK

  suspend fun call(name: String, args: JsonObject, context: TrailblazeToolExecutionContext): JsonElement {
    val sessionDir = SessionEventsDir.resolve(context)
    return when (name) {
      LIST -> JsonArray(TrailEventsHostFunctions.listStreams(sessionDir, context).map(::JsonPrimitive))
      MARK -> mark(sessionDir, args, context)
      else -> query(sessionDir, args, context)
    }
  }

  private fun mark(sessionDir: File?, args: JsonObject, context: TrailblazeToolExecutionContext): JsonElement {
    rejectUnknownKeys("ctx.events.mark", args, MARK_KEYS)
    val name = args.string("name")?.takeIf { it.isNotBlank() }
      ?: throw TrailblazeHostFunctionException("ctx.events.mark needs a name.")
    if (name in RESERVED_SINCE) {
      throw TrailblazeHostFunctionException("ctx.events.mark: '$name' is a built-in starting point; pick another name.")
    }
    val mark = SessionEventMarks.put(sessionIdOf(context), name, sessionDir)
    TrailheadEventLog.append(context, MARK_EVENT_TYPE) { put("name", name) }
    return buildJsonObject {
      put("name", name)
      put("timeMs", mark.timeMs)
      put("streams", mark.offsets.size)
    }
  }

  private suspend fun query(sessionDir: File?, args: JsonObject, context: TrailblazeToolExecutionContext): JsonElement {
    rejectUnknownKeys("ctx.events.query", args, QUERY_KEYS)
    val stream = args.string("stream")?.takeIf { it.isNotBlank() }
      ?: throw TrailblazeHostFunctionException("ctx.events.query needs a stream name.")
    val matchJson = when (val raw = args["match"]) {
      null -> null
      is JsonObject -> raw
      else -> throw TrailblazeHostFunctionException("ctx.events.query: `match` must be an object.")
    }
    val match = matchJson?.let {
      try {
        EventMatch.parse(it)
      } catch (e: IllegalArgumentException) {
        throw TrailblazeHostFunctionException("ctx.events.query: ${e.message}")
      }
    }
    val countOnly = (args["count"] as? JsonPrimitive)?.booleanOrNull == true
    val newest = (args["newest"] as? JsonPrimitive)?.booleanOrNull == true
    val limit = ((args["limit"] as? JsonPrimitive)?.intOrNull ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
    // A wait can't ask for more records than it returns: past the limit, the cursor would skip them.
    // A count returns no records, so its threshold is its own.
    val minCount = ((args["minCount"] as? JsonPrimitive)?.intOrNull ?: 1)
      .coerceIn(1, if (countOnly) Int.MAX_VALUE else limit)
    val waitMs = ((args["waitMs"] as? JsonPrimitive)?.longOrNull ?: 0L).coerceIn(0L, MAX_WAIT_MS)
    val after = (args["after"] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }
    val since = args.string("since") ?: SINCE_SESSION
    // Settled before the stream is looked up, so a bad `since` fails whether or not capture began.
    val startPoint = resolveSince(since, after, context)

    val scanner = EventStreamScanner(EventStreamScanner.needlesFor(matchJson), maxLineBytes = MAX_LINE_BYTES)
    val stats = EventStreamScanner.Stats()
    var events = mutableListOf<Kept>()
    var matched = 0
    var truncated = false
    var file: File? = null
    var start: Start? = null
    var clockTrack: DeviceClockTrack? = null
    var clockStamp: Pair<Long, Long>? = null
    var offset = 0L
    var seenLength = -1L
    var firstScan = true
    var pollMs = FIRST_POLL_MS
    val started = TimeSource.Monotonic.markNow()

    while (true) {
      if (file == null) {
        // A stream can appear mid-wait: its producer creates the file on its first record.
        file = sessionDir?.let { TrailEventsHostFunctions.streamFileFor(it, stream, context) }
        if (file != null) {
          start = startIn(file, startPoint)
          offset = start.offset
        }
      }
      val current = file
      // Only a file that grew since the last look is read again, so an idle wait is one stat per
      // tick even while a half-written last line holds the resume offset short of the end.
      val length = current?.length() ?: 0L
      if (current != null && length > maxOf(offset, seenLength)) {
        // The window is on the host clock, so device-stamped records are moved onto it. Re-read
        // whenever the clock file changes: a wait can outlast the first sample, or see a new one.
        sessionDir?.let { dir ->
          val clockFile = File(dir, DeviceClockSync.FILE_NAME)
          val stamp = clockFile.lastModified() to clockFile.length()
          if (stamp != clockStamp) {
            clockStamp = stamp
            clockTrack = TrailEventsHostFunctions.clockTrackFor(dir, current)
          }
        }
        val window = start!!
        val backward = newest && firstScan
        val batch = ArrayDeque<Kept>()
        var batchBytes = 0L
        val heldBytes = events.sumOf { it.bytes }
        val keep: (Kept) -> Boolean = { kept ->
          matched++
          if (countOnly) {
            true
          } else if (newest && !backward) {
            // A later newest-first pass keeps only the latest of a burst, within the limit and budget.
            batch += kept
            batchBytes += kept.bytes
            while (batch.size > 1 && (batch.size > limit || batchBytes > MAX_RESULT_BYTES)) {
              if (batch.size <= limit) truncated = true
              batchBytes -= batch.removeFirst().bytes
            }
            true
          } else {
            batch += kept
            batchBytes += kept.bytes
            // Oldest-first stops at the limit or the budget; so does the first newest-first pass,
            // read from the end. The record that crosses the budget is kept, so a query always
            // makes progress and its cursor skips nothing.
            if (heldBytes + batchBytes >= MAX_RESULT_BYTES && events.size + batch.size < limit) truncated = true
            events.size + batch.size < limit && heldBytes + batchBytes < MAX_RESULT_BYTES
          }
        }
        val visitLine: (String) -> Boolean = { line ->
          visit(line, window, match, context, clockTrack)?.let { keep(Kept(it, utf8Bytes(line))) } ?: true
        }
        offset = if (backward) {
          scanner.backward(current, offset, stats, length, visitLine)
        } else {
          scanner.forward(current, offset, stats, length, visitLine)
        }
        seenLength = length
        // Newest-first is arrival order reversed: a backward pass yields it as read, and whatever a
        // later pass reads arrived after everything already held.
        events = when {
          !newest -> (events + batch).toMutableList()
          backward -> batch.toMutableList()
          else -> withinBudget(batch.asReversed() + events, limit) { truncated = true }
        }
        firstScan = false
      }
      val remaining = waitMs - started.elapsedNow().inWholeMilliseconds
      if (matched >= minCount || truncated || remaining <= 0) break
      SessionEventsWaitPump.waitFor(sessionIdOf(context).value, minOf(pollMs, remaining))
      pollMs = minOf(pollMs * 2, MAX_POLL_MS)
    }

    // Read last, so a loss recorded during the wait still counts. A record over the line cap was
    // skipped unread, so it could have matched: that makes the result incomplete too.
    val skipped = stats.oversized.takeIf { it > 0 }?.let {
      "$it record(s) over $MAX_LINE_BYTES bytes were skipped unread"
    }
    // Only losses in the stream read count: another capture's gap says nothing about this one.
    val streamRead = file?.let { SessionEvents.parseFileName(it.name) } ?: stream
    val incomplete = listOfNotNull(sessionDir?.let { SessionCaptureHealth.failure(it, streamRead) }, skipped)
      .joinToString("; ")
      .ifEmpty { null }
    return buildJsonObject {
      put("captured", file != null)
      put("complete", incomplete == null)
      incomplete?.let { put("incompleteReason", it) }
      if (countOnly) put("count", matched) else put("events", JsonArray(events.map { it.record }))
      if (truncated) put("truncated", true)
      put("cursor", offset)
      // Where a record came from, for evidence: the stream's name hides a device's file suffix.
      if (file != null && sessionDir != null) put("file", file.relativeTo(sessionDir).invariantSeparatorsPath)
      putJsonObject("scan") {
        put("bytesRead", stats.bytesRead)
        put("lines", stats.lines)
        put("parsed", stats.candidates)
        put("oversized", stats.oversized)
        put("waitedMs", started.elapsedNow().inWholeMilliseconds)
      }
    }
  }

  /** One candidate line → the `{ timeMs, data }` record it yields, or null when it doesn't match. */
  private fun visit(
    line: String,
    start: Start,
    match: EventMatch?,
    context: TrailblazeToolExecutionContext,
    clockTrack: DeviceClockTrack?,
  ): JsonElement? {
    val record = TrailEventsHostFunctions.parseRecord(line, context, clockTrack) ?: return null
    if (record.timeMs < start.fromMs) return null
    if (match != null && !match.matches(record.data)) return null
    return record.toJson()
  }

  /** [line]'s size as UTF-8, what the budget is in, without encoding it. */
  internal fun utf8Bytes(line: String): Long {
    var bytes = 0L
    var i = 0
    while (i < line.length) {
      val c = line[i]
      bytes += when {
        c.code < 0x80 -> 1
        c.code < 0x800 -> 2
        Character.isHighSurrogate(c) && i + 1 < line.length && Character.isLowSurrogate(line[i + 1]) -> { i++; 4 }
        else -> 3
      }
      i++
    }
    return bytes
  }

  /** A record a query holds, with the size of the line it came from. */
  private class Kept(val record: JsonElement, val bytes: Long)

  /** The leading records of [kept] that fit the limit and the byte budget; at least one. */
  private fun withinBudget(kept: List<Kept>, limit: Int, onTrim: () -> Unit): MutableList<Kept> {
    val out = mutableListOf<Kept>()
    var bytes = 0L
    for (k in kept) {
      if (out.size == limit) break
      if (out.isNotEmpty() && bytes + k.bytes > MAX_RESULT_BYTES) {
        onTrim()
        break
      }
      out += k
      bytes += k.bytes
    }
    return out
  }

  /** Where a query starts, before its stream is known: a cursor, a time floor, or a mark. */
  private sealed interface StartPoint {
    class Cursor(val offset: Long) : StartPoint
    class FromTime(val fromMs: Long) : StartPoint
    class AtMark(val mark: SessionEventMarks.Mark) : StartPoint
  }

  /** Where a query starts in a file: a byte offset, plus a time floor where only a time is known. */
  private class Start(val offset: Long, val fromMs: Long)

  private fun resolveSince(since: String, after: Long?, context: TrailblazeToolExecutionContext): StartPoint {
    if (after != null) return StartPoint.Cursor(after)
    val sessionId = sessionIdOf(context)
    return when (since) {
      SINCE_SESSION -> StartPoint.FromTime(0L)
      SINCE_TRAILHEAD, SINCE_TRAIL -> {
        val run = TrailheadRuns.current(sessionId)
          ?: throw TrailblazeHostFunctionException(
            "ctx.events.query: since '$since' needs a trailhead in this trail; mark a starting point instead.",
          )
        StartPoint.FromTime(
          if (since == SINCE_TRAIL) {
            run.trailStartMs ?: throw TrailblazeHostFunctionException(
              "ctx.events.query: since 'trail' has nothing to read until the trail starts; use 'trailhead' or a mark.",
            )
          } else {
            run.trailheadStartMs
          },
        )
      }
      else -> StartPoint.AtMark(
        SessionEventMarks.get(sessionId, since)
          ?: throw TrailblazeHostFunctionException("ctx.events.query: no mark named '$since' in this session."),
      )
    }
  }

  private fun startIn(file: File, point: StartPoint): Start = when (point) {
    is StartPoint.Cursor -> Start(minOf(point.offset, file.length()), 0L)
    is StartPoint.FromTime -> Start(0L, point.fromMs)
    // A stream that did not exist at the mark began after it.
    is StartPoint.AtMark -> Start(point.mark.offsets[file.absolutePath] ?: 0L, 0L)
  }

  private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

  /** A misspelled option (`wait` for `waitMs`) would otherwise be ignored and change the query silently. */
  private fun rejectUnknownKeys(api: String, args: JsonObject, known: Set<String>) {
    val unknown = args.keys - known
    if (unknown.isNotEmpty()) {
      throw TrailblazeHostFunctionException(
        "$api: unknown option${if (unknown.size > 1) "s" else ""} ${unknown.joinToString { "'$it'" }} " +
          "(known: ${known.joinToString()}).",
      )
    }
  }

  private val QUERY_KEYS = setOf("stream", "match", "since", "after", "waitMs", "minCount", "limit", "newest", "count")
  private val MARK_KEYS = setOf("name")

  private fun sessionIdOf(context: TrailblazeToolExecutionContext): SessionId = context.sessionProvider.invoke().sessionId

  private val RESERVED_SINCE = setOf(SINCE_SESSION, SINCE_TRAILHEAD, SINCE_TRAIL)

  /** The `trailhead` stream record a mark writes, so the report shows where it was taken. */
  const val MARK_EVENT_TYPE: String = "mark"
}

/**
 * Named starting points per session: each stream file's length when the mark was taken. In memory
 * only, and [clear]ed as each trail finishes, so a later trail in the session never reads from an
 * earlier one's mark. The oldest sessions' marks are dropped.
 */
object SessionEventMarks {

  class Mark(val timeMs: Long, val offsets: Map<String, Long>)

  private const val MAX_SESSIONS = 1024
  private const val MAX_MARKS_PER_SESSION = 256

  private val bySession = object : LinkedHashMap<SessionId, ConcurrentHashMap<String, Mark>>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<SessionId, ConcurrentHashMap<String, Mark>>?) =
      size > MAX_SESSIONS
  }

  fun put(sessionId: SessionId, name: String, sessionDir: File?): Mark {
    val mark = Mark(System.currentTimeMillis(), sessionDir?.let(::snapshot).orEmpty())
    synchronized(bySession) {
      val marks = bySession.getOrPut(sessionId) { ConcurrentHashMap() }
      if (marks.size >= MAX_MARKS_PER_SESSION && !marks.containsKey(name)) {
        throw TrailblazeHostFunctionException("ctx.events.mark: a session keeps at most $MAX_MARKS_PER_SESSION marks.")
      }
      marks[name] = mark
    }
    return mark
  }

  fun get(sessionId: SessionId, name: String): Mark? = synchronized(bySession) { bySession[sessionId]?.get(name) }

  /** Forgets every mark [sessionId] took, as a trail finishes. */
  fun clear(sessionId: SessionId) {
    synchronized(bySession) { bySession.remove(sessionId) }
  }

  /** Every stream file's current length: one `stat` each, nothing read. */
  private fun snapshot(sessionDir: File): Map<String, Long> {
    val files = File(sessionDir, SessionEvents.DIR_NAME).listFiles().orEmpty().asList() +
      sessionDir.listFiles().orEmpty().filter { it.name.endsWith(".${SessionEvents.EXTENSION}") }
    return files.filter { it.isFile }.associate { it.absolutePath to it.length() }
  }
}

/**
 * Where this session's event streams are. A host run has a session dir from its runner. An
 * on-device run has none by design, so whatever captures events there registers the folder it
 * writes to, for the life of the trail, in [ActiveSessionEventsDir].
 */
object SessionEventsDir {
  fun resolve(context: TrailblazeToolExecutionContext): File? =
    TrailheadEventLog.sessionDir(context) ?: ActiveSessionEventsDir.get()
}

/**
 * The events folder of an on-device run, registered by the capture writing into it. One per
 * process, because an instrumentation process runs one trail at a time; [clear] on capture stop, so
 * a finished trail's files are never read as the next one's. Nothing registers on a host run, where
 * the runner's session dir is the only source.
 */
object ActiveSessionEventsDir {
  @Volatile private var dir: File? = null

  fun set(sessionDir: File) {
    dir = sessionDir
  }

  fun clear() {
    dir = null
  }

  fun get(): File? = dir
}
