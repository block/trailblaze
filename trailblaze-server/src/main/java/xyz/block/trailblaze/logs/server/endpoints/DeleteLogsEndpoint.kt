package xyz.block.trailblaze.logs.server.endpoints

import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Routing
import io.ktor.server.routing.post
import xyz.block.trailblaze.report.utils.LogsRepo

object DeleteLogsEndpoint {
  fun register(routing: Routing, logsRepo: LogsRepo) = with(routing) {
    post("/logs/delete") {
      logsRepo.clearLogs()

      call.respondRedirect("/")
    }
  }
}
