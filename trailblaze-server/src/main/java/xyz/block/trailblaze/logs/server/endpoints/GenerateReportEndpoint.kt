package xyz.block.trailblaze.logs.server.endpoints

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import xyz.block.trailblaze.report.ReportTemplateResolver
import xyz.block.trailblaze.report.WasmReport
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.util.Console
import java.io.File

/**
 * Endpoint that generates a WASM report on-demand for all current sessions
 * and serves it as HTML. Useful for viewing results remotely (e.g., via Blox proxy).
 *
 * Uses relative image URLs so screenshots load from the server's /static endpoint
 * rather than being embedded in the HTML (keeps generation fast and report size small).
 */
object GenerateReportEndpoint {

  private var cachedReportFile: File? = null

  fun register(routing: Routing, logsRepo: LogsRepo) = with(routing) {
    get("/report") {
      try {
        val sessionIds = logsRepo.getSessionIds()
        if (sessionIds.isEmpty()) {
          call.respondText(
            "No sessions found. Run a trail first.",
            ContentType.Text.Plain,
            HttpStatusCode.NotFound,
          )
          return@get
        }

        Console.log("[Report] Generating WASM report for ${sessionIds.size} session(s)...")

        val reportFile = generateReport(logsRepo)
        if (reportFile == null) {
          call.respondText(
            "Report template not found. Ensure trailblaze_report_template.html is available " +
              "(bundled in JAR or at git root).",
            ContentType.Text.Plain,
            HttpStatusCode.InternalServerError,
          )
          return@get
        }

        Console.log("[Report] Serving report: ${reportFile.absolutePath} (${reportFile.length() / 1024}KB)")
        call.respondFile(reportFile)
      } catch (e: Exception) {
        Console.error("[Report] Error generating report: ${e.message}")
        e.printStackTrace()
        call.respondText(
          "Error generating report: ${e.message}",
          ContentType.Text.Plain,
          HttpStatusCode.InternalServerError,
        )
      }
    }
  }

  private fun generateReport(logsRepo: LogsRepo): File? {
    val templateFile = ReportTemplateResolver.resolveTemplate() ?: return null
    Console.log("[Report] Using template: ${templateFile.absolutePath}")

    val trailblazeUiDir = ReportTemplateResolver.findTrailblazeUiDir() ?: logsRepo.logsDir

    // Write report to the reports directory inside logsDir
    val reportsDir = File(logsRepo.logsDir, "reports")
    reportsDir.mkdirs()
    val outputFile = File(reportsDir, "trailblaze_live_report.html")

    // Clean up previous cached report
    cachedReportFile?.let { if (it.exists() && it != outputFile) it.delete() }

    WasmReport.generate(
      logsRepo = logsRepo,
      trailblazeUiProjectDir = trailblazeUiDir,
      outputFile = outputFile,
      reportTemplateFile = templateFile,
      useRelativeImageUrls = true,
    )

    cachedReportFile = outputFile
    return outputFile
  }
}
