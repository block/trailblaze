package xyz.block.trailblaze.logs.server

import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.http.content.staticFiles
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.server.endpoints.AgentLogEndpoint
import xyz.block.trailblaze.logs.server.endpoints.CliRunEndpoint
import xyz.block.trailblaze.logs.server.endpoints.CliRunRequest
import xyz.block.trailblaze.logs.server.endpoints.CliRunResponse
import xyz.block.trailblaze.logs.server.endpoints.CliShutdownEndpoint
import xyz.block.trailblaze.logs.server.endpoints.CliShowWindowEndpoint
import xyz.block.trailblaze.logs.server.endpoints.CliStatusEndpoint
import xyz.block.trailblaze.logs.server.endpoints.CliStatusResponse
import xyz.block.trailblaze.logs.server.endpoints.DeleteLogsEndpoint
import xyz.block.trailblaze.logs.server.endpoints.GetEndpointSessionDetail
import xyz.block.trailblaze.logs.server.endpoints.HomeEndpoint
import xyz.block.trailblaze.logs.server.endpoints.LogScreenshotPostEndpoint
import xyz.block.trailblaze.logs.server.endpoints.PingEndpoint
import xyz.block.trailblaze.logs.server.endpoints.ReverseProxyEndpoint
import xyz.block.trailblaze.report.utils.LogsRepo
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Callbacks for CLI endpoints. These are provided by the desktop app.
 */
data class CliEndpointCallbacks(
  /** Called when CLI requests a trail run */
  val onRunRequest: (CliRunRequest) -> CliRunResponse,
  /** Called when CLI requests shutdown */
  val onShutdownRequest: () -> Unit,
  /** Called when CLI requests to show the window */
  val onShowWindowRequest: () -> Unit,
  /** Provides current daemon status */
  val statusProvider: () -> CliStatusResponse,
)

/**
 * This object contains the Ktor server endpoints for the Trailblaze logs server.
 */
object ServerEndpoints {

  @OptIn(ExperimentalEncodingApi::class)
  fun Application.logsServerKtorEndpoints(
    logsRepo: LogsRepo,
    homeCallbackHandler: ((parameters: Map<String, List<String>>) -> Result<String>)? = null,
    installContentNegotiation: Boolean = true,
    cliCallbacks: CliEndpointCallbacks? = null,
  ) {
    if (installContentNegotiation) {
      install(ContentNegotiation) {
        json(TrailblazeJsonInstance)
      }
    }
    install(CORS) {
      anyHost()
      anyMethod()
      allowHeader("Content-Type")
      allowHeader("Authorization")
    }
    routing {
      HomeEndpoint.register(routing = this, logsRepo = logsRepo, homeCallbackHandler = homeCallbackHandler)
      PingEndpoint.register(this)
      GetEndpointSessionDetail.register(this, logsRepo)
      AgentLogEndpoint.register(this, logsRepo)
      DeleteLogsEndpoint.register(this, logsRepo)
      LogScreenshotPostEndpoint.register(this, logsRepo)
      ReverseProxyEndpoint.register(this, logsRepo)
      staticFiles("/static", logsRepo.logsDir)
      
      // CLI endpoints (only registered if callbacks provided)
      cliCallbacks?.let { callbacks ->
        CliRunEndpoint.register(this, callbacks.onRunRequest)
        CliShutdownEndpoint.register(this, callbacks.onShutdownRequest)
        CliShowWindowEndpoint.register(this, callbacks.onShowWindowRequest)
        CliStatusEndpoint.register(this, callbacks.statusProvider)
      }
      
      route("{...}") {
        handle {
          println("Unhandled route: ${call.request.uri} [${call.request.httpMethod}]")
          call.respond(HttpStatusCode.NotFound)
        }
      }
    }
  }
}
