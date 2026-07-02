package xyz.block.trailblaze.trailrunner

import io.ktor.server.routing.Route
import io.ktor.server.sse.heartbeat
import io.ktor.server.sse.sse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.util.Console
import java.io.File
import kotlin.time.Duration.Companion.seconds

/**
 * SPIKE 1 — live agent-event streaming.
 *
 * Replaces the UI's 2.5s `GET /api/session/{id}/logs` poll with a server push channel: as the
 * Trailblaze agent writes each log file (LLM request, tool call, tool result, screen state) into a
 * session's log dir, this endpoint pushes it to the browser over a single held SSE connection. The
 * client does ZERO polling — it opens one `EventSource` and receives events as they land.
 *
 * The SSE payload is the RAW on-disk log JSON (identical bytes to what `GET /api/session/{id}/logs`
 * already serves), so the frontend log parser needs no changes.
 *
 * Event protocol:
 *  - `event: log`   `data:` = one raw log-file JSON object; `id:` = its 0-based index
 *  - `event: done`  session reached a terminal ([SessionStatus.Ended]) status; stream closes
 *  - `event: error` `data:` = `{"error": "..."}`; stream closes (e.g. unresolvable session id)
 *  - `: heartbeat`  comment keepalive (every 15s) so idle connections survive Ktor's timeout
 *
 * ## Why a server-side poll loop, not the file watcher
 *
 * The obvious tap is [xyz.block.trailblaze.report.utils.LogsRepo.getSessionLogsFlow] (a reactive
 * `StateFlow` kept current by a filesystem watcher). The spike measured that path on macOS and found
 * the JDK `WatchService` (PollingWatchService on macOS — no native FSEvents) delivers updates with
 * ~2s latency AND drops rapid sequential changes, so live events were late and `done` was missed.
 * A bounded server-side poll of the session dir ([POLL_INTERVAL_MS]) is reliable and sub-second, and
 * the cost is one `listFiles()` per tick on one small dir per open stream.
 *
 * The production path (Spike's full build) should instead tap the in-process `LogEmitter` chokepoint
 * (`TrailblazeLogger`) for true zero-latency, zero-miss push; this poll loop is the spike's
 * dependency-free stand-in that proves the SSE transport + UI end-to-end.
 *
 * The SSE plugin itself is already installed on the daemon server by `TrailblazeMcpServer`.
 */
private const val POLL_INTERVAL_MS = 250L

internal fun Route.sessionStreamRoutes(deps: TrailRunnerDeps) {
  sse("$PATH_BASE/api/session/{id}/stream") {
    val id = call.parameters["id"]?.trim().orEmpty()
    val sessionDir = resolveSafeSessionDir(deps.logsRepo.logsDir, id)
    if (sessionDir == null) {
      // No clean HTTP 404 inside an SSE handler — signal via an error event, then return to close.
      send(event = "error", data = """{"error":"session not found: $id"}""")
      return@sse
    }

    heartbeat {
      period = 15.seconds
    }

    // The hex-prefixed *.json log files, sorted by name — the same selection/order the polling
    // `/logs` endpoint serves. Re-listed each tick so newly-flushed files are picked up.
    fun logFiles(): List<File> = (sessionDir.listFiles() ?: emptyArray())
      .filter { f ->
        f.extension == "json" &&
          f.name.firstOrNull()?.let { c -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' } == true
      }
      .sortedBy { it.name }

    var sent = 0
    suspend fun flushNew() {
      val files = withContext(Dispatchers.IO) { logFiles() }
      while (sent < files.size) {
        val text = withContext(Dispatchers.IO) { runCatching { files[sent].readText() }.getOrNull() }
        if (text != null) {
          send(event = "log", id = sent.toString(), data = text)
        }
        sent++
      }
    }

    // Terminal detection: parse the latest session-status-change log file (cheap — only the status
    // files, never the screenshots) and check for an Ended status. Returns false if none parse yet.
    suspend fun isEnded(): Boolean = withContext(Dispatchers.IO) {
      val statusFile = logFiles().lastOrNull { it.name.contains("SessionStatusChangeLog") } ?: return@withContext false
      val text = runCatching { statusFile.readText() }.getOrNull() ?: return@withContext false
      runCatching {
        val log = TrailblazeJsonInstance.decodeFromString(TrailblazeLog.serializer(), text)
        (log as? TrailblazeLog.TrailblazeSessionStatusChangeLog)?.sessionStatus is SessionStatus.Ended
      }.getOrDefault(false)
    }

    try {
      // Emit the backlog on connect, then tail live until the session ends or the client disconnects.
      while (true) {
        flushNew()
        if (isEnded()) {
          flushNew() // catch any final logs written alongside the terminal status
          send(event = "done", data = """{"sessionId":"$id"}""")
          break
        }
        delay(POLL_INTERVAL_MS)
      }
    } catch (e: Throwable) {
      // Client disconnects surface as a cancellation/IO error here — expected, just log at debug.
      Console.log("[TrailRunnerEndpoint] SSE stream for session $id closed: ${e.message}")
    }
  }
}
