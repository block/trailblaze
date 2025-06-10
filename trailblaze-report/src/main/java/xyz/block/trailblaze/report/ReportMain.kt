package xyz.block.trailblaze.report

import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.llm.LlmUsageAndCostExt.computeUsageSummary
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.HasAgentTaskStatus
import xyz.block.trailblaze.logs.model.HasScreenshot
import xyz.block.trailblaze.report.models.LogsSummary
import xyz.block.trailblaze.report.utils.LogsRepo
import java.io.File

fun main(args: Array<String>) {
  val logsDir = File(args[0])
  println("logsDir: ${logsDir.canonicalPath}")
  val logsRepo = LogsRepo(logsDir)

  // Move the files into session directories.  This is needed after an adb pull
  moveJsonFilesToSessionDirs(logsDir)

  val standaloneFileReport = true
  val logsSummaryEvents = renderSummary(logsRepo, standaloneFileReport)
  val logsSummaryJson = TrailblazeJsonInstance.encodeToString(LogsSummary.serializer(), logsSummaryEvents)
  val summaryJsonFile = File(logsDir, "summary.json")
  summaryJsonFile.writeText(logsSummaryJson)

  val trailblazeReportHtmlFile = File(logsDir, "trailblaze_report.html")

  val html = ReportRenderer.renderTemplateFromResources(
    "trailblaze_report.ftl",
    mutableMapOf<String, Any>(
      "summaryJson" to logsSummaryJson,
    ),
  )
  println("file://${trailblazeReportHtmlFile.absolutePath}")
  trailblazeReportHtmlFile.writeText(html)

  logsRepo.getSessionIds().forEach { sessionId ->
    processSession(logsRepo, sessionId)
  }
}

fun moveJsonFilesToSessionDirs(logsDir: File) {
  val jsonFilesInLogsDir = logsDir.listFiles()?.filter { it.extension == "json" } ?: emptyList()
  jsonFilesInLogsDir.forEach { downloadedJsonFile ->
    try {
      val log: TrailblazeLog = TrailblazeJsonInstance.decodeFromString<TrailblazeLog>(
        downloadedJsonFile.readText(),
      )
      downloadedJsonFile.delete()

      val sessionId = log.session
      val sessionDir = File(logsDir, sessionId)
      sessionDir.mkdirs()

      if (log is HasScreenshot) {
        log.screenshotFile?.let { screenshotFile ->
          val currentScreenshotFileBytes = File(logsDir, screenshotFile).readBytes()
          sessionDir.delete()
          val destScreenshotFile = File(sessionDir, screenshotFile)
          destScreenshotFile.writeBytes(currentScreenshotFileBytes)
        }
        val screenshotFileInSessionDirPath = "$sessionId/${log.screenshotFile}"
        when (log) {
          is TrailblazeLog.MaestroDriverLog -> log.copy(
            screenshotFile = screenshotFileInSessionDirPath,
          )
          is TrailblazeLog.TrailblazeLlmRequestLog -> log.copy(
            screenshotFile = screenshotFileInSessionDirPath,
          )
        }
      }

      val outputFile = File(
        sessionDir,
        downloadedJsonFile.nameWithoutExtension + "${log::class.java.simpleName}.json",
      )

      outputFile.writeText(TrailblazeJsonInstance.encodeToString(log))
      println("Deleting ${downloadedJsonFile.canonicalPath}")
    } catch (e: Exception) {
      println("Error processing ${downloadedJsonFile.absolutePath}: ${e.message}")
    }
  }
}

fun processSession(logsRepo: LogsRepo, sessionId: String) {
  val allLogs = logsRepo.getLogsForSession(sessionId)

  val agentTaskStatus = allLogs.filterIsInstance<HasAgentTaskStatus>().map { it.agentTaskStatus }.lastOrNull()

  println("Processing $sessionId")
  val html = ReportRenderer.renderTemplateFromResources(
    "standalone.ftl",
    mutableMapOf<String, Any>(
      "statusMessage" to getStatusMessage(agentTaskStatus),
      "inProgress" to (agentTaskStatus is AgentTaskStatus.InProgress),
      "statusJson" to TrailblazeJsonInstance.encodeToString(agentTaskStatus),
      "logs" to allLogs,
      "session" to sessionId,
    ).apply {
      agentTaskStatus?.statusData?.let { this.put("status", it) }
      allLogs.computeUsageSummary()?.debugString()?.let { this.put("llmUsageSummary", it) }
    },
  )
  val sessionDir = logsRepo.getSessionDir(sessionId)
  val outputFile = File(sessionDir, "trailblaze_$sessionId.html").also {
    println("file://${it.absolutePath}")
    it.writeText(html)
  }
}

fun renderSummary(logsRepo: LogsRepo, isStandaloneFileReport: Boolean): LogsSummary {
  val map = logsRepo.getSessionIds().associateWith { logsRepo.getLogsForSession(it) }
  val logsSummary = LogsSummary.fromLogs(map, isStandaloneFileReport)
  return logsSummary
}

fun getStatusMessage(agentTaskStatus: AgentTaskStatus?): String = when (agentTaskStatus) {
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
