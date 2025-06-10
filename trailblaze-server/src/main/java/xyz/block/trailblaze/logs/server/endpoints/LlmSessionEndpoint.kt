package xyz.block.trailblaze.logs.server.endpoints

import com.aallam.openai.api.chat.Content
import com.aallam.openai.api.chat.ImagePart
import com.aallam.openai.api.chat.ListContent
import com.aallam.openai.api.chat.TextContent
import com.aallam.openai.api.chat.TextPart
import io.ktor.server.freemarker.FreeMarkerContent
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import xyz.block.trailblaze.logs.client.TrailblazeLog.TrailblazeLlmRequestLog
import xyz.block.trailblaze.report.utils.LogsRepo

/**
 * Registers an endpoint to display LLM conversation as an html chat view.
 */
object LlmSessionEndpoint {

  /** Transforms an Open AI [Content] object into a [String] representation */
  private fun contentToString(openAiContent: Content?): String? = when (openAiContent) {
    is ListContent -> {
      val result = openAiContent.content.map { content ->
        when (content) {
          is ImagePart -> null
          is TextPart -> content.text
        }
      }
      result.joinToString(",") { it.toString() }
    }

    is TextContent -> openAiContent.content
    null -> null
  }

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
