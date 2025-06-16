package xyz.block.trailblaze.logs.server.endpoints

import freemarker.template.Configuration
import freemarker.template.Template
import freemarker.template.TemplateExceptionHandler
import io.ktor.server.freemarker.FreeMarkerContent
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import kotlinx.serialization.json.Json
import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.llm.LlmUsageAndCostExt.computeUsageSummary
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.HasAgentTaskStatus
import xyz.block.trailblaze.report.utils.LogsRepo
import java.io.StringWriter

object GetEndpointSessionDetail {
  private val basicPrettyPrintJson = Json { prettyPrint = true }

  fun register(routing: Routing, logsDirUtil: LogsRepo) = with(routing) {
    get("/session/{session}") {
      val sessionId = call.parameters["session"]
      val allLogs = logsDirUtil.getLogsForSession(sessionId)

      val logsForCards: List<TrailblazeLog> = allLogs.map {
        when (it) {
          is TrailblazeLog.TrailblazeLlmRequestLog -> {
            if (it.screenshotFile?.startsWith("https") != true) {
              it.copy(screenshotFile = it.screenshotFile?.let { fileName -> "/static/$sessionId/$fileName" })
            } else {
              it
            }
          }

          is TrailblazeLog.MaestroDriverLog -> {
            if (it.screenshotFile?.startsWith("https") != true) {
              it.copy(screenshotFile = it.screenshotFile?.let { fileName -> "/static/$sessionId/$fileName" })
            } else {
              it
            }
          }

          else -> it
        }
      }

      val agentTaskStatus = allLogs.filterIsInstance<HasAgentTaskStatus>().map { it.agentTaskStatus }.lastOrNull()
      val statusMessage = when (agentTaskStatus) {
        is AgentTaskStatus.Failure.MaxCallsLimitReached ->

          buildString {
            append("Failed, Maximum Calls Limit Reached ${agentTaskStatus.statusData.callCount}")
            append(" in ${agentTaskStatus.statusData.totalDurationMs / 1000} seconds")
          }

        is AgentTaskStatus.Failure.ObjectiveFailed -> buildString {
          append("Objective Failed after ${agentTaskStatus.statusData.callCount} Calls")
          append(" in ${agentTaskStatus.statusData.totalDurationMs / 1000} seconds")
          append(" with agent reason: \"${agentTaskStatus.llmExplanation}\"")
        }

        is AgentTaskStatus.InProgress -> "Running, ${agentTaskStatus.statusData.callCount} LLM Requests so far. "
        is AgentTaskStatus.Success.ObjectiveComplete -> buildString {
          append("Successfully Completed after ${agentTaskStatus.statusData.callCount} Calls")
          append(" in ${agentTaskStatus.statusData.totalDurationMs / 1000} seconds")
          append(" with agent reason: \"${agentTaskStatus.llmExplanation}\"")
        }

        null -> "Session Not Found"
      }

      call.respond(
        FreeMarkerContent(
          "session.ftl",
          mapOf(
            "llmUsageSummary" to allLogs.computeUsageSummary()?.debugString(),
            "statusMessage" to statusMessage,
            "inProgress" to (agentTaskStatus is AgentTaskStatus.InProgress),
            "status" to agentTaskStatus?.statusData,
            "statusJson" to basicPrettyPrintJson.encodeToString(agentTaskStatus),
            "logs" to logsForCards,
            "session" to sessionId,
          ),
        ),
        null,
      )
    }
  }

  fun renderTemplateFromResources(templatePath: String, dataModel: Map<String, Any>): String {
    val cfg = Configuration(Configuration.VERSION_2_3_34).apply {
      defaultEncoding = "UTF-8"
      templateExceptionHandler = TemplateExceptionHandler.RETHROW_HANDLER
      logTemplateExceptions = false
      wrapUncheckedExceptions = true

      // Load templates from src/main/resources
      setClassLoaderForTemplateLoading(Thread.currentThread().contextClassLoader, "")
    }

    val template: Template = cfg.getTemplate(templatePath)
    val out = StringWriter()
    template.process(dataModel, out)
    return out.toString()
  }
}
