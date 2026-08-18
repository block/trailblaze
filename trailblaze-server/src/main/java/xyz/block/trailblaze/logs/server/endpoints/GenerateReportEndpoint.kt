package xyz.block.trailblaze.logs.server.endpoints

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.report.RunReportGenerator
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.util.Console
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Endpoint that generates the interactive Trailblaze report on-demand and serves it as HTML.
 *
 * - `/report` generates a report over the [DEFAULT_SESSION_LIMIT] most recent sessions.
 * - `/report?limit=<n>` widens or narrows that; `?limit=all` (or `0`) covers every session.
 * - `/report?session=<id>` generates a report containing only the requested session, which
 *   opens straight on that run's detail view. `limit` doesn't apply to it.
 *
 * The artifact is the same interactive report `trailblaze report` writes and the Trail Runner
 * "Share as HTML" button produces — the step timeline, screenshots, LLM transcript, recorded YAML,
 * and the UI Inspector. It replaced the legacy WASM report here, so the daemon no longer keeps that
 * generator alive. One difference: this report LINKS its screenshots to the daemon's own `/static/`
 * route rather than embedding them ([STATIC_IMAGE_BASE_URL]), which is what keeps a many-session
 * report small — at the cost of it only rendering while served by a daemon that can still see those
 * logs. `trailblaze report` writes the portable, fully-embedded artifact.
 *
 * **`bun` is a hard requirement** ([RunReportGenerator] shells out to it). A daemon without
 * bun on its PATH answers `503` with a page naming the cause and the fix instead of quietly
 * handing back a different, worse artifact.
 */
object GenerateReportEndpoint {

  /**
   * How many sessions the unfiltered `/report` covers by default, most recent first.
   *
   * The cap was sized when this endpoint's report inlined every screenshot as base64 (re-encoding
   * the ones over 100KB through ffmpeg), which made its cost scale with screenshots rather than
   * sessions: a screenshot-dense Android session alone produced 2.4MB from 45 shots, so an
   * uncapped report over a logs dir with no retention ran to hundreds of MB, and on an iOS corpus
   * (PNGs over the recompress floor) to thousands of serial ffmpeg spawns — past
   * [RunReportGenerator]'s 120s subprocess timeout, and past what a browser will `JSON.parse`
   * briskly even if it finished.
   *
   * [STATIC_IMAGE_BASE_URL] removes that term: images are now referenced off `/static/`, so what
   * remains per session is its logs, transcript and hierarchies. The cap stays as a bound on THAT
   * — re-measure against a long-lived logs dir before raising it, since the image cost it was
   * originally sized for is no longer the binding one.
   */
  const val DEFAULT_SESSION_LIMIT: Int = 25

  /**
   * Where this endpoint's reports point their `<img>` tags: the daemon's own
   * `staticFiles("/static", logsRepo.logsDir)` route (see `ServerEndpoints`), which serves exactly
   * the `<sessionId>/<file>` layout [RunReportGenerator] emits for a linked-image report.
   *
   * Root-relative on purpose — it resolves against whatever host:port the daemon is reached on, so
   * the report works over an SSH tunnel or from another machine without being re-generated. The
   * trade is that this report is NOT portable: saved to disk and reopened over `file://`, its
   * images 404. `trailblaze report` still writes the self-contained artifact for that.
   */
  internal const val STATIC_IMAGE_BASE_URL: String = "/static/"

  /**
   * How this endpoint builds the report. Injected so tests can drive the HTTP contract —
   * served content type, the sessions generation was asked for, the bun-missing page, the
   * failure page — without a bun subprocess.
   */
  interface InteractiveReportSource {
    /** Whether the report can be generated at all (i.e. `bun` resolved). */
    val isAvailable: Boolean

    /** Generates the report for [sessionIds], or null when generation failed. */
    fun generate(logsRepo: LogsRepo, sessionIds: List<SessionId>): File?
  }

  fun register(
    routing: Routing,
    logsRepo: LogsRepo,
    reportSource: InteractiveReportSource = RunReportSource(),
  ) = with(routing) {
    val generations = ReportGenerations()
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

        val requestedLimit = parseLimit(call.request.queryParameters["limit"])
        val candidates = if (requestedSession == null) recentSessionsFirst(logsRepo) else emptyList()
        val filteredSessionIds = if (requestedSession != null) {
          val sessionId = SessionId(requestedSession)
          // Security: this exact-match membership check against the real on-disk session dirs
          // is the allowlist that makes the user-controlled `session` param safe — a traversal
          // payload (`../../etc`) won't equal any real dir name and 404s here before any file
          // access. Keep it an exact match; a prefix/normalize check would void that guarantee.
          if (sessionId !in allSessionIds) {
            call.respondText(
              "Session '$requestedSession' not found.",
              ContentType.Text.Plain,
              HttpStatusCode.NotFound,
            )
            return@get
          }
          listOf(sessionId)
        } else if (requestedLimit == null) {
          candidates
        } else {
          candidates.take(requestedLimit)
        }
        val omittedSessions = candidates.size - filteredSessionIds.size

        if (!reportSource.isAvailable) {
          Console.error("[Report] bun not found on the daemon's PATH — cannot build the interactive report.")
          call.respondText(
            bunMissingPage(),
            ContentType.Text.Html,
            HttpStatusCode.ServiceUnavailable,
          )
          return@get
        }

        Console.log(
          "[Report] Generating interactive report for ${filteredSessionIds.size} session(s)" +
            if (omittedSessions > 0) " (most recent of ${candidates.size})..." else "...",
        )

        // The scope is part of the report's identity, so it keys both the single-flight and the
        // output filename — a capped report and a ?limit=all one must not overwrite each other.
        // Null is the default unfiltered scope, which keeps the plain output filename.
        val scopeKey = when {
          requestedSession != null -> "session=$requestedSession"
          requestedLimit != DEFAULT_SESSION_LIMIT -> "limit=${requestedLimit ?: "all"}"
          else -> null
        }
        val reportFile = generations.generate(scopeKey.orEmpty()) {
          generateReport(
            reportSource = reportSource,
            logsRepo = logsRepo,
            sessionIds = filteredSessionIds,
            scopeKey = scopeKey,
            truncationNotice = if (omittedSessions > 0) {
              "Showing the ${filteredSessionIds.size} most recent of ${candidates.size} sessions."
            } else {
              null
            },
          )
        }
        if (reportFile == null) {
          call.respondText(
            generationFailedPage(),
            ContentType.Text.Html,
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
   * Every session that can be reported on, most recent first — the pool the `limit` applies to.
   *
   * Resolved through [LogsRepo.getSessionInfoSummary] (status logs only — the accessor built for
   * exactly this "walk every session" case) rather than by listing directories, for three
   * reasons: it sorts on the session's own recorded timestamp instead of a directory mtime that
   * anything can touch; it drops directories that aren't sessions, including this endpoint's own
   * `reports/` output — which, being rewritten on every request, would otherwise take the
   * freshest slot in the window; and it applies the same has-a-status-log gate the report
   * renderer does, so the count the user is shown is the count they get.
   */
  private fun recentSessionsFirst(logsRepo: LogsRepo): List<SessionId> = logsRepo.getSessionIds()
    .mapNotNull { logsRepo.getSessionInfoSummary(it) }
    .sortedByDescending { it.timestamp }
    .map { it.sessionId }

  /**
   * The `limit` query parameter: null means "no cap" (`all` or a non-positive number), absent or
   * unparseable means [DEFAULT_SESSION_LIMIT]. Unparseable falls back rather than 400-ing so a
   * typo still returns a usable report — the same convention the `TRAILBLAZE_*` knobs use.
   */
  private fun parseLimit(raw: String?): Int? {
    val value = raw?.trim()?.takeIf { it.isNotBlank() } ?: return DEFAULT_SESSION_LIMIT
    if (value.equals("all", ignoreCase = true)) return null
    val parsed = value.toIntOrNull() ?: return DEFAULT_SESSION_LIMIT
    return if (parsed <= 0) null else parsed
  }

  /**
   * Generates the report for [sessionIds] and moves it to this endpoint's stable output name,
   * keyed by [scopeKey] so reports of different scopes (the default window, an explicit
   * `?limit=`, each `?session=`) coexist on disk instead of overwriting one another.
   * [RunReportGenerator] writes a fresh timestamped file per call; moving it onto a stable name
   * keeps a daemon that serves this route all day from filling `logs/reports/`.
   *
   * The move is same-directory — [RunReportGenerator] already returns a file under
   * `<logsDir>/reports/` — so it is a rename, never a cross-volume copy. The fallback below
   * covers the case anyway.
   *
   * A non-null [truncationNotice] is rendered into the served page, so a report that covers
   * only part of the logs dir says so on the artifact itself rather than only at the link that
   * produced it.
   */
  private fun generateReport(
    reportSource: InteractiveReportSource,
    logsRepo: LogsRepo,
    sessionIds: List<SessionId>,
    scopeKey: String?,
    truncationNotice: String?,
  ): File? {
    val generated = reportSource.generate(logsRepo, sessionIds) ?: return null
    val reportsDir = File(logsRepo.logsDir, REPORTS_DIR_NAME).apply { mkdirs() }
    val outputFile = File(
      reportsDir,
      if (scopeKey != null) "trailblaze_live_report_${shortHash(scopeKey)}.html"
      else "trailblaze_live_report.html",
    )
    val served = runCatching {
      Files.move(generated.toPath(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING).toFile()
    }.getOrElse {
      // Serving the generator's own output is strictly better than failing the request over a
      // rename; the only cost is one extra file left in logs/reports/.
      Console.log("[Report] Could not move the report to ${outputFile.name}: ${it.message}")
      generated
    }
    if (truncationNotice != null) addTruncationNotice(served, truncationNotice)
    return served
  }

  /**
   * Appends the "showing N of M" banner to a generated report.
   *
   * Post-processing the rendered HTML (rather than teaching the renderer about it) keeps the
   * notice entirely inside this endpoint's concern — the same report generated by the CLI isn't
   * scoped by a URL and has nothing to say. Any failure only logs: a report without the banner
   * is still the report, and the home page carries the same signal.
   *
   * Only ever runs on the truncated path, which is bounded by the limit — so the whole-file
   * rewrite can't be handed the multi-hundred-MB document the cap exists to prevent.
   */
  private fun addTruncationNotice(reportFile: File, notice: String) {
    runCatching {
      val html = reportFile.readText()
      val bodyEnd = html.lastIndexOf("</body>")
      val banner = truncationBanner(notice)
      reportFile.writeText(
        if (bodyEnd >= 0) html.substring(0, bodyEnd) + banner + html.substring(bodyEnd) else html + banner,
      )
    }.onFailure { Console.log("[Report] Could not add the session-scope notice: ${it.message}") }
  }

  /** Dismissible floating notice naming the report's scope and the link that widens it. */
  private fun truncationBanner(notice: String): String = """
    <div id="trailblaze-report-scope-notice" style="position:fixed;left:16px;bottom:16px;z-index:2147483647;max-width:380px;background:#161b22;color:#e6edf3;border:1px solid #30363d;border-radius:10px;padding:10px 14px;box-shadow:0 6px 24px rgba(0,0,0,.35);font:13px/1.5 -apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif">
      $notice <a href="/report?limit=all" style="color:#58a6ff">Include every session</a> (slower, much larger).
      <button type="button" onclick="this.parentNode.remove()" style="margin-left:6px;background:none;border:0;color:#8b949e;cursor:pointer;font:inherit">Dismiss</button>
    </div>
  """.trimIndent()

  /** Where generated reports are written, under the logs dir. Not a session. */
  private const val REPORTS_DIR_NAME = "reports"

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

  /** The production [InteractiveReportSource]: [RunReportGenerator] over a bun subprocess. */
  private class RunReportSource : InteractiveReportSource {
    // Constructed per request rather than once per daemon so bun resolution is re-tried: a user
    // who installs bun after starting the daemon gets a working /report on the next reload
    // instead of having to restart the daemon.
    override val isAvailable: Boolean get() = RunReportGenerator().isBunAvailable

    override fun generate(logsRepo: LogsRepo, sessionIds: List<SessionId>): File? =
      RunReportGenerator().generate(logsRepo, sessionIds, imageBaseUrl = STATIC_IMAGE_BASE_URL)
  }

  /**
   * Runs report generation off the Ktor worker threads, one at a time, single-flighted per
   * report scope. Three properties matter for a daemon that has to stay responsive:
   *
   * - the bun subprocess runs on [Dispatchers.IO], never on the thread serving the request, so
   *   a slow generation can't starve the request-handling pool (the failure mode that wedges
   *   every route including `/ping`);
   * - concurrent requests for the SAME report share one generation instead of racing N
   *   subprocesses — a browser reload mid-generation joins the run already in flight;
   * - generations for DIFFERENT reports are serialized, both to bound the memory a report build
   *   holds and because [RunReportGenerator] names its intermediate output by wall-clock second,
   *   which two overlapping builds could collide on.
   *
   * A client that disconnects cancels only its own `await`; the shared generation keeps running
   * for whoever else is waiting on it.
   */
  private class ReportGenerations {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlightLock = Any()
    private val inFlight = mutableMapOf<String, Deferred<File?>>()
    private val generationLock = Mutex()

    suspend fun generate(key: String, block: () -> File?): File? {
      val deferred = synchronized(inFlightLock) {
        inFlight[key] ?: scope.async {
          try {
            generationLock.withLock { block() }
          } finally {
            synchronized(inFlightLock) { inFlight.remove(key) }
          }
        }.also { inFlight[key] = it }
      }
      return deferred.await()
    }
  }

  private fun bunMissingPage(): String = errorPage(
    title = "Interactive report unavailable",
    reason = "This daemon can't find <code>bun</code> on its PATH, and the interactive report is " +
      "rendered by a bun subprocess.",
    hint = "Install bun from <a href=\"https://bun.sh\">bun.sh</a> (or run <code>source bin/activate-hermit</code> " +
      "in a Trailblaze checkout), then reload this page. If bun is installed somewhere this daemon's " +
      "PATH doesn't cover, stop the daemon with <code>trailblaze app --stop</code> and re-run your " +
      "command from a shell that has bun.",
  )

  private fun generationFailedPage(): String = errorPage(
    title = "Report generation failed",
    reason = "The interactive report subprocess did not produce a report.",
    hint = "Check the daemon log for the <code>[RunReportGenerator]</code> lines explaining why " +
      "(<code>~/.trailblaze/desktop-logs/</code>), then reload this page.",
  )

  private fun errorPage(title: String, reason: String, hint: String): String = """
    <!DOCTYPE html>
    <html lang="en">
      <head>
        <meta charset="utf-8" />
        <meta name="viewport" content="width=device-width, initial-scale=1" />
        <title>$title &middot; Trailblaze</title>
        <style>
          body {
            margin: 0; min-height: 100vh; display: flex; align-items: center; justify-content: center;
            background: #0d1117; color: #e6edf3; padding: 24px;
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
          }
          .card { max-width: 640px; background: #161b22; border: 1px solid #30363d; border-radius: 14px; padding: 28px 32px; }
          h1 { margin: 0 0 16px; font-size: 22px; }
          p { margin: 0 0 12px; line-height: 1.55; color: #b9c3cf; }
          .label { color: #8b949e; text-transform: uppercase; letter-spacing: .08em; font-size: 11px; }
          code { background: #0d1117; border: 1px solid #30363d; border-radius: 5px; padding: 1px 5px; font-size: 13px; }
          a { color: #58a6ff; }
        </style>
      </head>
      <body>
        <main class="card">
          <h1>$title</h1>
          <p><span class="label">Reason</span><br />$reason</p>
          <p><span class="label">Fix</span><br />$hint</p>
          <p><a href="/">&larr; Back to the daemon home page</a></p>
        </main>
      </body>
    </html>
  """.trimIndent()
}
