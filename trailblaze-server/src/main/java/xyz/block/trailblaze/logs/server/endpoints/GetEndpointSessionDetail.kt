package xyz.block.trailblaze.logs.server.endpoints

import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.report.utils.TrailblazeYamlSessionRecording.generateRecordedYaml

object GetEndpointSessionDetail {
  fun register(routing: Routing, logsRepo: LogsRepo) = with(routing) {
    get("/api/session/{session}/logs") {
      val sessionId = call.parameters["session"]
      val logEntries: List<TrailblazeLog> = logsRepo.getLogsForSession(sessionId)
        .sortedBy { it.timestamp }
      call.respond(logEntries)
    }
    get("/api/session/{session}/yaml") {
      val sessionId = call.parameters["session"]
      val yaml: String = logsRepo.getLogsForSession(sessionId)
        .sortedBy { it.timestamp }.generateRecordedYaml()
      call.respond(yaml)
    }
    get("/api/sessions") {
      val sessionIds: List<String> = logsRepo.getSessionIds()
      call.respond(sessionIds)
    }
  }
}
