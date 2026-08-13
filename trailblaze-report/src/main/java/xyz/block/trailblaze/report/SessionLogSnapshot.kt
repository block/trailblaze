package xyz.block.trailblaze.report

import java.io.File
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonPrimitive
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.util.Console

/**
 * An immutable, in-memory snapshot of one session's on-disk log files, captured with a SINGLE
 * read of each file. One `trailblaze report` invocation used to read + JSON-decode the same
 * session's `*.json` logs several times over (once per report leg, plus a raw re-read for the
 * interactive report's bun seam); every leg now consumes one shared snapshot instead.
 *
 * Two views of the same files, because the report legs need both:
 *
 * - [logs] — the typed, cost-enriched [TrailblazeLog] list (timestamp-sorted), exactly what
 *   [LogsRepo.getLogsForSession] returns for the same on-disk state. Consumed by the report
 *   meta/summary builders and used to pre-seed the WASM report's session-scoped [LogsRepo].
 * - [rawLogsJson] — the raw per-file records as a [JsonArray], byte-equivalent to what
 *   `RunReportGenerator` used to re-read from disk for the interactive report's `input.json`
 *   (timestamp-sorted like [logs], leniently parsed, redundant view-hierarchy fields deduped to
 *   the one the renderer reads — see `RunReportGenerator.slimViewHierarchyFields`). Kept as a
 *   separate view rather than re-serialized from [logs] so the embedded payload
 *   stays identical to the on-disk records — including files that are valid JSON but not
 *   decodable as [TrailblazeLog]. Both views share one order because the interactive report's
 *   extractor folds adjacent records into steps (tool groups by traceId, assertion bursts,
 *   objective nesting), so a non-chronological array doesn't just mis-sort the timeline — it
 *   mis-groups it.
 */
class SessionLogSnapshot(
  val sessionId: SessionId,
  val logs: List<TrailblazeLog>,
  val rawLogsJson: JsonArray,
) {
  companion object {

    private val RAW_PARSER = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * The raw-view file filter (mirrors the daemon's session-logs route): hex-prefixed
     * `*.json`. A superset of [LogsRepo]'s typed-log filter, so one enumeration serves both
     * views.
     */
    private fun isRawSessionLogFile(file: File): Boolean =
      file.extension == "json" &&
        file.name.firstOrNull()?.let { c -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' } == true

    /**
     * A record's own `timestamp`, the only ordering key that holds for BOTH log-filename
     * conventions [LogsRepo] documents: on-device logging writes ordinal prefixes (`001_`, `002_`)
     * where filename order is chronological, but the device-farm ingest writes hex-hashed prefixes
     * (`a1b2c3d4_`) where it is arbitrary. Records without a parseable timestamp sort first and
     * keep their relative filename order (`sortedBy` is stable).
     */
    private fun recordTimestamp(record: JsonElement): Instant? = runCatching {
      Instant.parse((record as JsonObject)["timestamp"]!!.jsonPrimitive.content)
    }.getOrNull()

    /**
     * Captures a snapshot of [sessionId]'s current logs under [logsDir], reading each log file
     * exactly once and decoding that one text both ways (typed via the same parse [LogsRepo]
     * performs, so [costEnricher] applies; raw via the lenient parser).
     *
     * Takes the bare directory (not a repo) so callers can capture BEFORE constructing the
     * [LogsRepo] they will seed with the result — see `SingleReadLogsRepoProvider`.
     */
    fun capture(
      logsDir: File,
      sessionId: SessionId,
      costEnricher: (TrailblazeLog) -> TrailblazeLog = { it },
    ): SessionLogSnapshot {
      // Plain File, not LogsRepo.getSessionDir — that helper mkdirs a missing session's dir as a side effect.
      val sessionDir = File(logsDir, sessionId.value)
      val files = (sessionDir.listFiles() ?: emptyArray())
        .filter { isRawSessionLogFile(it) }
        .sortedBy { it.name }

      val typedLogs = mutableListOf<TrailblazeLog>()
      val rawRecords = mutableListOf<JsonElement>()
      for (file in files) {
        val text = runCatching { file.readText() }.getOrElse { e ->
          // Skip the file but say so — a silent skip reads as "session has no logs" when
          // debugging a report (the pre-snapshot per-leg reads logged their own failures).
          Console.log("Warning: could not read log file ${file.absolutePath}: $e")
          null
        } ?: continue
        runCatching { RAW_PARSER.parseToJsonElement(text) }.getOrNull()
          ?.let { rawRecords.add(RunReportGenerator.slimViewHierarchyFields(it)) }
        if (LogsRepo.isTrailblazeLogFile(file)) {
          LogsRepo.parseTrailblazeLog(file, jsonText = text, costEnricher = costEnricher)
            ?.let { typedLogs.add(it) }
        }
      }
      return SessionLogSnapshot(
        sessionId = sessionId,
        logs = typedLogs.sortedBy { it.timestamp },
        rawLogsJson = buildJsonArray { rawRecords.sortedBy { recordTimestamp(it) }.forEach { add(it) } },
      )
    }

    /**
     * Captures a snapshot of [sessionId]'s current on-disk logs with [logsRepo]'s cost
     * enrichment.
     *
     * Deliberately reads DISK TRUTH and never consults [logsRepo]'s parsed-log cache: a cached
     * flow primed before the session's final logs landed stays stale (the behavior pinned by
     * `LogsRepoDiskTruthTest`), and the CLI captures right after a terminal-status poll that
     * reads disk — serving a lagging cache here could report a finished session as running (or
     * skip it entirely, when the cache was primed empty). Not touching the cache also means
     * capturing against the daemon's long-lived repo can't re-grow the memory its
     * ended-session eviction reclaimed.
     */
    fun capture(logsRepo: LogsRepo, sessionId: SessionId): SessionLogSnapshot =
      capture(logsRepo.logsDir, sessionId, logsRepo.costEnricher)

    fun captureAll(logsRepo: LogsRepo, sessionIds: List<SessionId>): List<SessionLogSnapshot> =
      sessionIds.map { capture(logsRepo, it) }

    fun captureAll(
      logsDir: File,
      sessionIds: List<SessionId>,
      costEnricher: (TrailblazeLog) -> TrailblazeLog = { it },
    ): List<SessionLogSnapshot> = sessionIds.map { capture(logsDir, it, costEnricher) }
  }
}
