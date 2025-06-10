package xyz.block.trailblaze.logs.server.endpoints

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.post
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

      // Receive the image bytes from the body
      val imageBytes = call.receive<ByteArray>()

      val logScreenshotFile = File(logsRepo.getSessionDir(session), filename)
      logScreenshotFile.writeBytes(imageBytes)

      val relativePath = logScreenshotFile.relativeTo(logsRepo.logsDir).path

      call.respond(HttpStatusCode.OK, "Screenshot saved as $relativePath")
    }
  }
}
