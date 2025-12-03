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

  private const val DEFAULT_HTML = """
    <!DOCTYPE html>
    <html>
      <body>
        <h1>The HTML Logs Viewer has been replaced by the Trailblaze Desktop App.</h1>
        <h3>Start it by running the following command within the Trailblaze directory:</h3>
        <h1><pre>./trailblaze</pre></h1>
        <br/>
      </body>
    </html>
  """

  fun register(
    routing: Routing,
    logsRepo: LogsRepo,
    homeCallbackHandler: ((parameters: Map<String, List<String>>) -> Result<String>)? = null,
  ) = with(routing) {
    get("/") {
      val callbackHandlerResult = homeCallbackHandler?.invoke(call.request.queryParameters.toMap())
      val htmlResult = callbackHandlerResult?.getOrDefault(
        defaultValue = DEFAULT_HTML.replaceAfterLast("<br/>", "<h3> Handler error. </h3> <br/>"),
      ) ?: DEFAULT_HTML
      call.respondText(text = htmlResult, contentType = ContentType.Text.Html)
    }
  }
}
