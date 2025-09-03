package xyz.block.trailblaze.logs.server.endpoints

import io.ktor.http.ContentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import xyz.block.trailblaze.report.utils.LogsRepo

/**
 * Endpoint to serve the home page of the Trailblaze logs server.
 * This endpoint displays a list of session IDs and a sample Goose recipe.
 */
object HomeEndpoint {

  fun register(
    routing: Routing,
    logsRepo: LogsRepo,
  ) = with(routing) {
    get("/") {
      call.respondText(
        """
        <!DOCTYPE html>
        <html>
          <body>
            <h1>The HTML Logs Viewer has been replaced by the Trailblaze Desktop App.</h1>
            <h3>Start it by running the following command within the Trailblaze directory:</h3>
            <h1><pre>./trailblaze</pre></h1>
            <br/>
          </body>
        </html>
        """.trimIndent(),
        ContentType.Text.Html,
      )
    }
  }
}
