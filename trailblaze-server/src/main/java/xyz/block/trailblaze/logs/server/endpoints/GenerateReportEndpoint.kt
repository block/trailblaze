package xyz.block.trailblaze.logs.server.endpoints

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.report.ReportTemplateResolver
import xyz.block.trailblaze.report.WasmReportRequest
import xyz.block.trailblaze.report.generateWasmReport
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.util.Console
import java.io.File
import java.security.MessageDigest

/**
 * Endpoint that generates a WASM report on-demand and serves it as HTML.
 *
 * - `/report` generates a report containing every session in [logsRepo].
 * - `/report?session=<id>` generates a report containing only the requested
 *   session. The single-session bundle auto-advances to the session detail
 *   view in the WASM UI, so per-session links land directly on the trail.
 *
 * Uses relative image URLs so screenshots load from the server's /static endpoint
 * rather than being embedded in the HTML (keeps generation fast and report size small).
 */
object GenerateReportEndpoint {

  fun register(routing: Routing, logsRepo: LogsRepo) = with(routing) {
    get("/report") {
      try {
        val requestedSession = call.request.queryParameters["session"]?.takeIf { it.isNotBlank() }
        val allSessionIds = logsRepo.getSessionIds()

        if (allSessionIds.isEmpty()) {
          call.respondText(
            "No sessions found. Run a trail first.",
            ContentType.Text.Plain,
            HttpStatusCode.NotFound,
          )
          return@get
        }

        val filteredSessionIds = if (requestedSession != null) {
          val sessionId = SessionId(requestedSession)
          if (sessionId !in allSessionIds) {
            call.respondText(
              "Session '$requestedSession' not found.",
              ContentType.Text.Plain,
              HttpStatusCode.NotFound,
            )
            return@get
          }
          listOf(sessionId)
        } else {
          allSessionIds
        }

        Console.log("[Report] Generating WASM report for ${filteredSessionIds.size} session(s)...")

        val reportFile = generateReport(logsRepo, filteredSessionIds, requestedSession)
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

  /**
   * Generates a report for [sessionIds]. When the request is filtered to a single
   * session, [generateWasmReport] runs the WASM report over a temporary logs repo of
   * symlinks pointing only at that session's data, so the report contains just one
   * session — the same scoping the CLI's report path uses.
   *
   * The output filename is keyed by [sessionFilterKey] so the all-sessions report
   * and any per-session reports can coexist on disk.
   *
   * Deliberately WASM-only (no interactive leg): this endpoint's contract is to serve
   * exactly one HTML file as the HTTP response, and that file has always been the
   * WASM report. The interactive artifact has no consumer here — the Trail Runner web app
   * renders sessions live and exports interactive HTML through its own Share path.
   */
  private fun generateReport(
    logsRepo: LogsRepo,
    sessionIds: List<SessionId>,
    sessionFilterKey: String?,
  ): File? {
    // Resolve the git root ONCE and share it across the template + ui-dir lookups —
    // each resolution forks a `git rev-parse` subprocess.
    val gitRoot = ReportTemplateResolver.getGitRoot()
    val templateFile = ReportTemplateResolver.resolveTemplate(gitRoot) ?: return null
    Console.log("[Report] Using template: ${templateFile.absolutePath}")

    val trailblazeUiDir = ReportTemplateResolver.findTrailblazeUiDir(gitRoot) ?: logsRepo.logsDir

    val reportsDir = File(logsRepo.logsDir, "reports")
    reportsDir.mkdirs()
    val outputFile = File(
      reportsDir,
      if (sessionFilterKey != null) "trailblaze_live_report_${shortHash(sessionFilterKey)}.html"
      else "trailblaze_live_report.html",
    )

    return generateWasmReport(
      logsRepo = logsRepo,
      // Use the unfiltered repo directly (keeping its parse cache) when no filter is
      // requested; otherwise scope the WASM report to just the requested session(s).
      sessionIds = if (sessionFilterKey != null) sessionIds else null,
      request = WasmReportRequest(
        outputFile = outputFile,
        templateFile = templateFile,
        trailblazeUiProjectDir = trailblazeUiDir,
        useRelativeImageUrls = true,
      ),
    )
  }

  /**
   * Hashes [input] to a fixed-length hex string suitable for use in a filename.
   * Keeps `trailblaze_live_report_<hash>.html` under common 255-byte filename
   * limits regardless of how long the session id is (externally-sourced ids in
   * particular can be very long).
   */
  private fun shortHash(input: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
    return buildString(16) {
      for (i in 0 until 8) {
        append(((bytes[i].toInt() ushr 4) and 0xF).toString(16))
        append((bytes[i].toInt() and 0xF).toString(16))
      }
    }
  }
}
