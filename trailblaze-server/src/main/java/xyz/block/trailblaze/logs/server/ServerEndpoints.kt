package xyz.block.trailblaze.logs.server

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
import xyz.block.trailblaze.logs.server.endpoints.DeleteLogsEndpoint
import xyz.block.trailblaze.logs.server.endpoints.GetEndpointSessionDetail
import xyz.block.trailblaze.logs.server.endpoints.HomeEndpoint
import xyz.block.trailblaze.logs.server.endpoints.LogScreenshotPostEndpoint
import xyz.block.trailblaze.logs.server.endpoints.PingEndpoint
import xyz.block.trailblaze.logs.server.endpoints.ReverseProxyEndpoint
import xyz.block.trailblaze.report.utils.LogsRepo
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * This object contains the Ktor server endpoints for the Trailblaze logs server.
 */
object ServerEndpoints {

  @OptIn(ExperimentalEncodingApi::class)
  fun Application.logsServerKtorEndpoints(
    logsRepo: LogsRepo,
    homeCallbackHandler: ((parameters: Map<String, List<String>>) -> Result<String>)? = null,
  ) {
    install(ContentNegotiation) {
      json(TrailblazeJsonInstance)
    }
    install(CORS) {
      anyHost()
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
      route("{...}") {
        handle {
          println("Unhandled route: ${call.request.uri} [${call.request.httpMethod}]")
          call.respond(HttpStatusCode.NotFound)
        }
      }
    }
  }
}
