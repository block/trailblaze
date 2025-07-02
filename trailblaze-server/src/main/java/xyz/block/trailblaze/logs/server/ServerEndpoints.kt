package xyz.block.trailblaze.logs.server

import freemarker.cache.ClassTemplateLoader
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.freemarker.FreeMarker
import io.ktor.server.http.content.staticFiles
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.server.endpoints.AgentLogEndpoint
import xyz.block.trailblaze.logs.server.endpoints.DeleteLogsEndpoint
import xyz.block.trailblaze.logs.server.endpoints.GetEndpointMaestroYamlSessionRecording
import xyz.block.trailblaze.logs.server.endpoints.GetEndpointSessionDetail
import xyz.block.trailblaze.logs.server.endpoints.GetEndpointTrailblazeSimpleYamlSessionRecording
import xyz.block.trailblaze.logs.server.endpoints.GetEndpointTrailblazeYamlSessionRecording
import xyz.block.trailblaze.logs.server.endpoints.HomeEndpoint
import xyz.block.trailblaze.logs.server.endpoints.LlmSessionEndpoint
import xyz.block.trailblaze.logs.server.endpoints.LogScreenshotPostEndpoint
import xyz.block.trailblaze.logs.server.endpoints.PingEndpoint
import xyz.block.trailblaze.logs.server.endpoints.RealtimeWebsocketEndpoint
import xyz.block.trailblaze.logs.server.endpoints.SessionJsonRecordingEndpoint
import xyz.block.trailblaze.logs.server.endpoints.SinglePageReportEndpoint
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.report.utils.TemplateHelpers
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * This object contains the Ktor server endpoints for the Trailblaze logs server.
 */
object ServerEndpoints {

  @OptIn(ExperimentalEncodingApi::class)
  fun Application.logsServerKtorEndpoints(logsRepo: LogsRepo) {
    install(WebSockets)
    install(ContentNegotiation) {
      json(TrailblazeJsonInstance)
    }
    install(FreeMarker) {
      templateLoader = ClassTemplateLoader(this::class.java.classLoader, "templates")
      this.setSharedVariable(TemplateHelpers::class.simpleName, TemplateHelpers)
    }
    routing {
      RealtimeWebsocketEndpoint.register(this, logsRepo)
      HomeEndpoint.register(this, logsRepo)
      PingEndpoint.register(this)
      LlmSessionEndpoint.register(this, logsRepo)
      SessionJsonRecordingEndpoint.register(this, logsRepo)
      GetEndpointSessionDetail.register(this, logsRepo)
      AgentLogEndpoint.register(this, logsRepo)
      DeleteLogsEndpoint.register(this, logsRepo)
      GetEndpointMaestroYamlSessionRecording.register(this, logsRepo)
      GetEndpointTrailblazeYamlSessionRecording.register(this, logsRepo)
      GetEndpointTrailblazeSimpleYamlSessionRecording.register(this, logsRepo)
      LogScreenshotPostEndpoint.register(this, logsRepo)
      SinglePageReportEndpoint.register(this, logsRepo)
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
