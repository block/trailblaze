package xyz.block.trailblaze.logs.server.endpoints

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.report.utils.TrailblazeYamlSessionRecording.generateUnifiedRecordedYaml

object GetEndpointSessionDetail {
  fun register(routing: Routing, logsRepo: LogsRepo) = with(routing) {
    get("/api/session/{session}/logs") {
      val sessionId = call.parameters["session"]?.let { SessionId(it) }
      // Already on one timeline and in order: LogsRepo owns clock normalization so every reader
      // of a session gets the same order. Device stamps arrive marked `clock: host`, so a client
      // that normalizes again shifts nothing.
      val logEntries: List<TrailblazeLog> = logsRepo.getLogsForSession(sessionId)
      call.respond(logEntries)
    }
    get("/api/session/{session}/yaml") {
      val sessionId = call.parameters["session"]?.let { SessionId(it) }
      val yaml: String = logsRepo.getLogsForSession(sessionId).generateUnifiedRecordedYaml()
      if (yaml.isBlank()) {
        // Blank means the session can't be rendered as a trail (no device classifier to key its
        // tools under, or a shape the format can't hold) — distinct from "here is an empty trail".
        call.respond(
          HttpStatusCode.UnprocessableEntity,
          "Session ${sessionId?.value} can't be rendered as a trail: it has no device classifier " +
            "to key its recording under, or a shape a trail can't hold.",
        )
        return@get
      }
      call.respond(yaml)
    }
    get("/api/sessions") {
      val sessionIds: List<String> = logsRepo.getSessionIds().map { it.value }
      call.respond(sessionIds)
    }
  }
}
