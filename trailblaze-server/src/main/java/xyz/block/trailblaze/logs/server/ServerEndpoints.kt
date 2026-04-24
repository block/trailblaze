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
import xyz.block.trailblaze.logs.server.endpoints.CliExecEndpoint
import xyz.block.trailblaze.logs.server.endpoints.CliExecRequest
import xyz.block.trailblaze.logs.server.endpoints.CliExecResponse
import xyz.block.trailblaze.logs.server.endpoints.CliRunAsyncEndpoint
import xyz.block.trailblaze.logs.server.endpoints.CliRunManager
import xyz.block.trailblaze.logs.server.endpoints.CliRunRequest
import xyz.block.trailblaze.logs.server.endpoints.CliRunResponse
import xyz.block.trailblaze.logs.server.endpoints.CliShowWindowEndpoint
import xyz.block.trailblaze.logs.server.endpoints.CliShutdownEndpoint
import xyz.block.trailblaze.logs.server.endpoints.CliStatusEndpoint
import xyz.block.trailblaze.logs.server.endpoints.CliStatusResponse
import xyz.block.trailblaze.logs.server.endpoints.DeleteLogsEndpoint
import xyz.block.trailblaze.logs.server.endpoints.GetEndpointSessionDetail
import xyz.block.trailblaze.logs.server.endpoints.HomeEndpoint
import xyz.block.trailblaze.logs.server.endpoints.LogScreenshotPostEndpoint
import xyz.block.trailblaze.logs.server.endpoints.LogTracePostEndpoint
import xyz.block.trailblaze.logs.server.endpoints.PingEndpoint
import xyz.block.trailblaze.logs.server.endpoints.GenerateReportEndpoint
import xyz.block.trailblaze.logs.server.endpoints.ReverseProxyEndpoint
import xyz.block.trailblaze.logs.server.endpoints.ScriptingCallbackEndpoint
import xyz.block.trailblaze.llm.config.LlmAuthResolver
import xyz.block.trailblaze.llm.config.LlmConfigLoader
import xyz.block.trailblaze.llm.config.ResolvedProviderAuth
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.util.Console
import io.ktor.server.application.ApplicationStopped
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Callbacks for CLI endpoints. These are provided by the desktop app.
 */
data class CliEndpointCallbacks(
  /** Called when CLI requests a trail run. The progress callback receives status messages. */
  val onRunRequest: suspend (CliRunRequest, onProgress: (String) -> Unit) -> CliRunResponse,
  /** Called when CLI requests shutdown */
  val onShutdownRequest: () -> Unit,
  /** Called when CLI requests to show the window */
  val onShowWindowRequest: () -> Unit,
  /** Provides current daemon status */
  val statusProvider: () -> CliStatusResponse,
  /**
   * Called when CLI wants to execute a subcommand in-process on the daemon
   * (IPC fast path). Null means the feature isn't wired up and the endpoint
   * responds 404.
   */
  val onCliExecRequest: (suspend (CliExecRequest) -> CliExecResponse)? = null,
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
    resolvedAuths: Map<String, ResolvedProviderAuth>? = null,
  ) {
    val auths = resolvedAuths ?: LlmAuthResolver.resolveAll(LlmConfigLoader.load())
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
      LogTracePostEndpoint.register(this, logsRepo)
      ReverseProxyEndpoint.register(this, logsRepo, auths)
      GenerateReportEndpoint.register(this, logsRepo)
      ScriptingCallbackEndpoint.register(this)
      staticFiles("/static", logsRepo.logsDir)

      // CLI endpoints (only registered if callbacks provided)
      cliCallbacks?.let { callbacks ->
        val runManager = CliRunManager(callbacks.onRunRequest)
        // Close the CliRunManager when the application stops to cancel its coroutine scope
        environment.monitor.subscribe(ApplicationStopped) {
          runManager.close()
        }
        CliRunAsyncEndpoint.register(this, runManager)
        CliShutdownEndpoint.register(this, callbacks.onShutdownRequest)
        CliShowWindowEndpoint.register(this, callbacks.onShowWindowRequest)
        CliStatusEndpoint.register(this, callbacks.statusProvider)
        callbacks.onCliExecRequest?.let { CliExecEndpoint.register(this, it) }
      }

      route("{...}") {
        handle {
          Console.log("Unhandled route: ${call.request.uri} [${call.request.httpMethod}]")
          call.respond(HttpStatusCode.NotFound)
        }
      }
    }
  }
}
