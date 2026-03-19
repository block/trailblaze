package xyz.block.trailblaze.logs.server.endpoints

import io.ktor.http.ContentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.util.toMap
import xyz.block.trailblaze.report.utils.LogsRepo

/**
 * Endpoint to serve the home page of the Trailblaze logs server.
 * This endpoint displays a list of session IDs and a sample Goose recipe.
 */
object HomeEndpoint {

  private fun defaultHtml(logsRepo: LogsRepo): String {
    val sessionCount = logsRepo.getSessionIds().size
    return """
    <!DOCTYPE html>
    <html>
      <head>
        <title>Trailblaze Server</title>
        <style>
          body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; max-width: 600px; margin: 60px auto; padding: 0 20px; color: #333; }
          h1 { font-size: 24px; }
          a { color: #0066cc; text-decoration: none; }
          a:hover { text-decoration: underline; }
          .sessions { color: #666; font-size: 14px; margin-top: 4px; }
          ul { list-style: none; padding: 0; }
          li { margin: 12px 0; font-size: 16px; }
          li a::before { content: ''; margin-right: 8px; }
        </style>
      </head>
      <body>
        <h1>Trailblaze Server</h1>
        <p class="sessions">$sessionCount session(s) available</p>
        <ul>
          <li><a href="/report">View Report</a></li>
          <li><a href="/ping">Health Check</a></li>
        </ul>
      </body>
    </html>
    """
  }

  fun register(
    routing: Routing,
    logsRepo: LogsRepo,
    homeCallbackHandler: ((parameters: Map<String, List<String>>) -> Result<String>)? = null,
  ) = with(routing) {
    get("/") {
      val callbackHandlerResult = homeCallbackHandler?.invoke(call.request.queryParameters.toMap())
      val defaultPage = defaultHtml(logsRepo)
      val htmlResult = callbackHandlerResult?.getOrNull() ?: defaultPage
      call.respondText(text = htmlResult, contentType = ContentType.Text.Html)
    }
  }
}
