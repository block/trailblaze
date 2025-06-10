package xyz.block.trailblaze.logs.server.endpoints

import io.ktor.server.freemarker.FreeMarkerContent
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.report.models.LogsSummary
import xyz.block.trailblaze.report.renderSummary
import xyz.block.trailblaze.report.utils.LogsRepo

/**
 * Handles POST requests to the /agentlog endpoint to accept `TrailblazeLog` requests.
 */
object SinglePageReportEndpoint {

  fun register(routing: Routing, logsRepo: LogsRepo) = with(routing) {
    get("/report") {
      try {
        val logsSummaryEvents: LogsSummary = renderSummary(logsRepo, isStandaloneFileReport = false)
        val logsSummaryJson = TrailblazeJsonInstance.encodeToString(LogsSummary.serializer(), logsSummaryEvents)
        call.respond(
          FreeMarkerContent(
            "trailblaze_report.ftl",
            mutableMapOf<String, Any>(
              "summaryJson" to logsSummaryJson,
            ),
          ),
          null,
        )
      } catch (e: Exception) {
        call.respondText("Error: ${e.message}")
      }
    }
  }
}
