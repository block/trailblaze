package xyz.block.trailblaze.logs.server.endpoints

import io.ktor.http.ContentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import xyz.block.trailblaze.report.utils.LogsRepo

/**
 * Endpoint to serve the home page of the Trailblaze logs server.
 * This endpoint displays a list of session IDs and a sample Goose recipe.
 *
 * Also handles OAuth callbacks when query parameters (code/error) are present.
 */
object HomeEndpoint {

  // OAuth callback handler - set by OAuth client when waiting for callback
  @Volatile
  var oauthCallback: ((code: String?, error: String?) -> Unit)? = null

  fun register(
    routing: Routing,
    logsRepo: LogsRepo,
  ) = with(routing) {
    get("/") {
      val code = call.request.queryParameters["code"]
      val error = call.request.queryParameters["error"]

      // Check if this is an OAuth callback (has code or error parameter)
      if (code != null || error != null) {
        println("✓ OAuth callback received at logs server")
        println("  code: ${code?.take(10)}... error: $error")

        // Invoke the OAuth callback handler if set
        oauthCallback?.invoke(code, error)

        // Send appropriate response
        call.respondText(
          if (code != null) {
            """
            <!DOCTYPE html>
            <html>
              <head>
                <title>Authentication Successful</title>
                <style>
                  body {
                    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    height: 100vh;
                    margin: 0;
                    background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
                  }
                  .container {
                    background: white;
                    padding: 48px;
                    border-radius: 12px;
                    box-shadow: 0 20px 60px rgba(0,0,0,0.3);
                    text-align: center;
                    max-width: 400px;
                  }
                  .success-icon { font-size: 64px; margin-bottom: 16px; }
                  h1 { color: #2d3748; margin: 0 0 16px 0; font-size: 24px; }
                  p { color: #718096; margin: 0; line-height: 1.6; }
                </style>
              </head>
              <body>
                <div class="container">
                  <div class="success-icon">✓</div>
                  <h1>Authentication Successful</h1>
                  <p>You have successfully authenticated with Databricks.</p>
                  <p>You can close this window and return to your terminal.</p>
                </div>
              </body>
            </html>
            """.trimIndent()
          } else {
            val errorDescription = call.request.queryParameters["error_description"] ?: "No description"
            """
            <!DOCTYPE html>
            <html>
              <head>
                <title>Authentication Failed</title>
                <style>
                  body {
                    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    height: 100vh;
                    margin: 0;
                    background: linear-gradient(135deg, #f093fb 0%, #f5576c 100%);
                  }
                  .container {
                    background: white;
                    padding: 48px;
                    border-radius: 12px;
                    box-shadow: 0 20px 60px rgba(0,0,0,0.3);
                    text-align: center;
                    max-width: 400px;
                  }
                  .error-icon { font-size: 64px; margin-bottom: 16px; }
                  h1 { color: #2d3748; margin: 0 0 16px 0; font-size: 24px; }
                  p { color: #718096; margin: 0 0 8px 0; line-height: 1.6; }
                  .error-details {
                    background: #fed7d7;
                    border: 1px solid #fc8181;
                    border-radius: 6px;
                    padding: 12px;
                    margin-top: 16px;
                    font-size: 14px;
                    color: #c53030;
                    text-align: left;
                  }
                </style>
              </head>
              <body>
                <div class="container">
                  <div class="error-icon">✗</div>
                  <h1>Authentication Failed</h1>
                  <p>There was an error during authentication.</p>
                  <div class="error-details">
                    <strong>Error:</strong> $error<br>
                    <strong>Description:</strong> $errorDescription
                  </div>
                  <p style="margin-top: 16px;">Please try again or contact your administrator.</p>
                </div>
              </body>
            </html>
            """.trimIndent()
          },
          ContentType.Text.Html,
        )
      } else {
        // Normal home page (no OAuth parameters)
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
}
