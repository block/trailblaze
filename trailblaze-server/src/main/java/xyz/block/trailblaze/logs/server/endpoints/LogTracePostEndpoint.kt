package xyz.block.trailblaze.logs.server.endpoints

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.post
import xyz.block.trailblaze.logs.client.TrailblazeLogServerClient
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.report.utils.LogsRepo
import java.io.File
import xyz.block.trailblaze.report.otel.SessionOtelExport
import xyz.block.trailblaze.report.trace.SessionTraceFile

/**
 * Handles POST requests to the /log/trace endpoint to accept trace.json data from any process that
 * recorded part of a run — an on-device driver, and the host's own runner.
 */
object LogTracePostEndpoint {

  fun register(
    routing: Routing,
    logsRepo: LogsRepo,
  ) = with(routing) {
    post("/log/trace") {
      val session = call.request.queryParameters["session"]
      if (session == null) {
        call.respond(HttpStatusCode(HttpStatusCode.BadRequest.value, "session not provided"))
        return@post
      }

      // Validate session ID to prevent path traversal BEFORE creating directories.
      // getSessionDir() calls mkdirs(), so we must check first.
      if (session.contains("..") || session.contains("/") || session.contains("\\") || session.contains("\u0000")) {
        call.respond(HttpStatusCode.BadRequest, "Invalid session ID")
        return@post
      }
      val sessionId = SessionId(session)
      val candidateDir = File(logsRepo.logsDir, session)
      val logsDirCanonical = logsRepo.logsDir.canonicalPath
      if (!candidateDir.canonicalPath.startsWith(logsDirCanonical + File.separator) &&
        candidateDir.canonicalPath != logsDirCanonical
      ) {
        call.respond(HttpStatusCode.BadRequest, "Invalid session ID")
        return@post
      }
      val sessionDir = logsRepo.getSessionDir(sessionId)

      val traceJson = call.receiveText()

      // Merged, not written: a run is recorded in more than one process and they all upload here.
      //
      // The uploader says which clock stamped it, because this route cannot tell: the host posts its
      // own trace through it too. A batch marked device-clock is kept off the host timeline, since
      // its skew from the host clock — whole seconds, routinely — would otherwise stretch the
      // session window; doing that to the host's own spans would empty the timeline instead.
      //
      // An absent marker is an uploader older than the field, and on THIS route that could be either
      // an older host or an older runner falling back off the log socket. Read as host, because the
      // two mistakes are not equal: an unmarked device batch widens the session window, which is how
      // it looked before any of this, while a host batch mistaken for a device one takes the host's
      // spans off the timeline entirely. The log socket reads absence the other way — see
      // `LogWebSocketEndpoint`, which only ever carries device traffic.
      val onDeviceClock = call.request.queryParameters[TrailblazeLogServerClient.CLOCK_PARAM] ==
        TrailblazeLogServerClient.DEVICE_CLOCK
      val traceFile = File(sessionDir, SessionTraceFile.FILE_NAME)
      SessionTraceFile.merge(traceFile, traceJson, onDeviceClock = onDeviceClock)

      // Persisted, so the upload has succeeded; the export is a separate, best-effort concern that
      // must not be able to make a stored upload look failed. The sender allows two seconds for this
      // response — exporting inline would blow past that against a collector that stalls, and the
      // sender would then retry an upload already on disk.
      SessionOtelExport.pushInBackgroundIfConfigured(sessionId.value, traceJson)

      val relativePath = traceFile.relativeTo(logsRepo.logsDir).path

      call.respond(HttpStatusCode.OK, "Trace saved as $relativePath")
    }
  }
}
