package xyz.block.trailblaze.logs.server.endpoints

import io.ktor.server.freemarker.FreeMarkerContent
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.temp.flattenTrailblazeJson
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.serializers.TrailblazeToolToCodeSerializer
import xyz.block.trailblaze.toolcalls.TrailblazeTool

/**
 * Registers an endpoint to display LLM conversation as an html chat view.
 */
object SessionJsonRecordingEndpoint {

  fun register(routing: Routing, logsRepo: LogsRepo) = with(routing) {
    get("/recording/json/{session}") {
      // Only save the llm request logs for now
      val sessionId = this.call.parameters["session"]

      val logs = logsRepo.getLogsForSession(sessionId)
      val toolCommandLogs = logs.filterIsInstance<TrailblazeLog.TrailblazeToolLog>()
      val instructionMap: Map<String, List<TrailblazeTool>> = toolCommandLogs
        .filter { it.successful }
        .groupBy { it.instructions }
        .mapValues { mapEntry ->
          mapEntry.value.map {
            it.command
          }
        }

      val normalJson = TrailblazeJsonInstance.encodeToString(instructionMap)
      val recordingJson = flattenTrailblazeJson(normalJson)

      val recordingKotlin = TrailblazeToolToCodeSerializer().serializeToCode(instructionMap)

      call.respond(
        FreeMarkerContent(
          "recording_json.ftl",
          mapOf(
            "session" to sessionId,
            "json" to recordingJson,
            "kotlinCode" to recordingKotlin,
          ),
        ),
        null,
      )
    }
  }
}
