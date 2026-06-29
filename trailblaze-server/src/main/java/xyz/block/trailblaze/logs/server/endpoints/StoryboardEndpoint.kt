package xyz.block.trailblaze.logs.server.endpoints

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import java.io.File
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.report.StoryboardHtmlBuilder
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.util.Console

/**
 * Endpoint that renders a session's storyboard — every step's screenshot tiled into a
 * captioned CSS grid — as HTML, generated on demand.
 *
 * `/storyboard?session=<id>` resolves the session's logs and hands them to
 * [StoryboardHtmlBuilder.prepare], which produces the same scrollable page
 * `trailblaze report --id <id> --storyboard` writes to disk. Generating on demand here
 * is what lets every session row on the home dashboard carry a working Storyboard link
 * without the user first running the CLI exporter — previously the link was only live
 * when a pre-generated `.storyboard.html` happened to exist in the logs dir, so it was
 * permanently dead for sessions created through the normal server flow.
 *
 * Two intentional differences from the WebP exporter's invocation of the same builder,
 * both because we're serving scrollable HTML to a browser rather than encoding a
 * single fixed-canvas WebP:
 *  - screenshots are referenced as `/static/<session>/<file>` URLs (served by
 *    [xyz.block.trailblaze.logs.server.ServerEndpoints]' `staticFiles("/static", ...)`)
 *    instead of base64-inlined, so the response stays small and images load lazily —
 *    the same call `GenerateReportEndpoint` makes with `useRelativeImageUrls`.
 *  - the libwebp 16383px dimension cap is not enforced, so a large session that would
 *    bust the WebP encoder still renders fine as a scrollable page.
 *
 * The WebP (PR-comment) form of the storyboard still comes from the host-side
 * `ReportStoryboardExporter`, which needs headless Chromium.
 */
object StoryboardEndpoint {

  fun register(routing: Routing, logsRepo: LogsRepo) = with(routing) {
    get("/storyboard") {
      val requestedSession = call.request.queryParameters["session"]?.takeIf { it.isNotBlank() }
      if (requestedSession == null) {
        call.respondText(
          "Missing required 'session' query parameter. Use /storyboard?session=<id>.",
          ContentType.Text.Plain,
          HttpStatusCode.BadRequest,
        )
        return@get
      }

      val sessionId = SessionId(requestedSession)
      // Security: this exact-match membership check against the real on-disk session
      // dirs is the allowlist that makes the user-controlled `session` param safe — a
      // traversal payload (`../../etc`) won't equal any real dir name and 404s here
      // before any file access. Keep it an exact match; a prefix/normalize check would
      // void that guarantee. Same pattern as GenerateReportEndpoint.
      if (sessionId !in logsRepo.getSessionIds()) {
        call.respondText(
          "Session '$requestedSession' not found.",
          ContentType.Text.Plain,
          HttpStatusCode.NotFound,
        )
        return@get
      }

      // YAML annotations default on, re-deriving the CLI's `--storyboard-yaml` flag
      // default (the builder function itself defaults includeYaml=false). Pass
      // ?yaml=false to render the synthesized verb/detail labels instead.
      val includeYaml = call.request.queryParameters["yaml"]?.lowercase() != "false"

      try {
        Console.log("[Storyboard] Generating storyboard HTML for session $requestedSession ...")
        val prepared = StoryboardHtmlBuilder.prepare(
          logs = logsRepo.getLogsForSession(sessionId),
          resolveScreenshotFile = { logsRepo.getScreenshotFile(it) },
          includeYaml = includeYaml,
          localScreenshotToUrl = { file -> staticUrlForScreenshot(file, logsRepo.logsDir) },
          enforceWebpDimensionCap = false,
          sessionLabel = requestedSession,
        )
        call.respondText(text = prepared.html, contentType = ContentType.Text.Html)
      } catch (e: IllegalStateException) {
        // prepare() throws IllegalStateException for the expected "can't build a
        // storyboard for this session" cases (no screenshot-bearing steps, or a session
        // so large it busts the WebP dimension/memory caps). Surface the actionable
        // message as a 4xx rather than a 500 — it's the request that can't be served,
        // not the server that's broken.
        call.respondText(
          e.message ?: "Unable to render a storyboard for session '$requestedSession'.",
          ContentType.Text.Plain,
          HttpStatusCode.UnprocessableEntity,
        )
      } catch (e: Exception) {
        Console.error("[Storyboard] Error generating storyboard: ${e.message}")
        e.printStackTrace()
        call.respondText(
          "Error generating storyboard: ${e.message}",
          ContentType.Text.Plain,
          HttpStatusCode.InternalServerError,
        )
      }
    }
  }

  /**
   * Map a resolved on-disk screenshot to the `/static` URL the server serves it under.
   * Screenshots live at `<logsDir>/<session>/<file>` and `/static` is rooted at
   * `logsDir`, so the URL is `/static/<session>/<file>` — the same `screenshotRef`
   * convention WasmReport's on-demand image path uses. Returns null for any file that
   * doesn't sit under [logsDir] (so the builder falls back to inlining it rather than
   * emitting a `/static` URL that wouldn't resolve).
   */
  private fun staticUrlForScreenshot(file: File, logsDir: File): String? {
    val relative = file.relativeToOrNull(logsDir) ?: return null
    // relativeToOrNull can still return a path that climbs out with `..`; reject those.
    if (relative.path.isEmpty() || relative.startsWith("..")) return null
    val urlPath = relative.invariantSeparatorsPath
    return "/static/$urlPath"
  }
}
