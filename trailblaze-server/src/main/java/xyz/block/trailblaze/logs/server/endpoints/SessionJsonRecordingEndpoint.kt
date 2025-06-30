package xyz.block.trailblaze.logs.server.endpoints

import io.ktor.server.freemarker.FreeMarkerContent
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.serializers.TrailblazeToolToCodeSerializer

/**
 * Registers an endpoint to display LLM conversation as an html chat view.
 */
object SessionJsonRecordingEndpoint {

  fun register(routing: Routing, logsRepo: LogsRepo) = with(routing) {
    get("/recording/kotlin/{session}") {
      // Only save the llm request logs for now
      val sessionId = this.call.parameters["session"]

      val logs = logsRepo.getLogsForSession(sessionId)
      val toolCommandLogs = logs.filterIsInstance<TrailblazeLog.TrailblazeToolLog>()

      val recordingKotlin =
        TrailblazeToolToCodeSerializer().serializeToCode(mapOf("" to toolCommandLogs.map { it.command }))

      call.respond(
        FreeMarkerContent(
          "recording_kotlin.ftl",
          mapOf(
            "session" to sessionId,
            "kotlinCode" to recordingKotlin,
          ),
        ),
        null,
      )
    }
  }
}
