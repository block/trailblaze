package xyz.block.trailblaze.logs.server.endpoints

import io.ktor.server.freemarker.FreeMarkerContent
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import xyz.block.trailblaze.logs.client.TrailblazeLog.TrailblazeLlmRequestLog
import xyz.block.trailblaze.report.utils.LogsRepo

/**
 * Registers an endpoint to display LLM conversation as an html chat view.
 */
object LlmSessionEndpoint {

  fun register(routing: Routing, logsRepo: LogsRepo) = with(routing) {
    get("/llm/{session}") {
      val sessionId = call.parameters["session"]
      val logEntries: List<TrailblazeLlmRequestLog> =
        logsRepo.getLogsForSession(sessionId).filterIsInstance<TrailblazeLlmRequestLog>()
          .sortedBy { it.timestamp }

      val lastLlmRequestLog = logEntries.lastOrNull()
      call.respond(
        FreeMarkerContent(
          "llm.ftl",
          mapOf(
            "session" to sessionId,
            "llmMessages" to lastLlmRequestLog?.llmMessages?.map { it.copy(message = it.message) },
          ),
        ),
        null,
      )
    }
  }
}
