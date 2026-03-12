package xyz.block.trailblaze.logs.server.endpoints

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.post
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.report.utils.LogsRepo
import java.io.File

/**
 * Handles POST requests to the /log/trace endpoint to accept trace.json data from on-device runs.
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

      val traceFile = File(sessionDir, "trace.json")
      traceFile.writeText(traceJson)

      val relativePath = traceFile.relativeTo(logsRepo.logsDir).path

      call.respond(HttpStatusCode.OK, "Trace saved as $relativePath")
    }
  }
}
