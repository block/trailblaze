package xyz.block.trailblaze.logs.server.endpoints

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.post
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.report.utils.LogsRepo
import java.io.File

/**
 * Handles POST requests to the /agentlog endpoint to accept `TrailblazeLog` requests.
 */
object LogScreenshotPostEndpoint {

  fun register(
    routing: Routing,
    logsRepo: LogsRepo,
  ) = with(routing) {
    post("/log/screenshot") {
      // Get the filename from the query parameter
      val filename = call.request.queryParameters["filename"]
      if (filename == null) {
        call.respond(HttpStatusCode(HttpStatusCode.BadRequest.value, "filename not provided"))
        return@post
      }
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

      // Validate filename to prevent path traversal (e.g., "../../etc/cron.d/malicious")
      if (filename.contains("..") || filename.contains("/") || filename.contains("\\") || filename.contains("\u0000")) {
        call.respond(HttpStatusCode.BadRequest, "Invalid filename")
        return@post
      }

      // Receive the image bytes from the body
      val imageBytes = call.receive<ByteArray>()

      val logScreenshotFile = File(sessionDir, filename)
      // Defense-in-depth: verify resolved path stays within session directory
      if (!logScreenshotFile.canonicalPath.startsWith(sessionDir.canonicalPath + File.separator) &&
        logScreenshotFile.canonicalPath != sessionDir.canonicalPath
      ) {
        call.respond(HttpStatusCode.BadRequest, "Invalid filename")
        return@post
      }
      logScreenshotFile.writeBytes(imageBytes)

      val relativePath = logScreenshotFile.relativeTo(logsRepo.logsDir).path

      call.respond(HttpStatusCode.OK, "Screenshot saved as $relativePath")
    }
  }
}
