package xyz.block.trailblaze.logs.server

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.http.content.staticFiles
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
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
import xyz.block.trailblaze.logs.server.endpoints.LogWebSocketEndpoint
import xyz.block.trailblaze.logs.server.endpoints.PingEndpoint
import xyz.block.trailblaze.logs.server.endpoints.GenerateReportEndpoint
import xyz.block.trailblaze.logs.server.endpoints.ReverseProxyEndpoint
import xyz.block.trailblaze.logs.server.endpoints.ScriptingCallbackEndpoint
import xyz.block.trailblaze.logs.server.endpoints.StoryboardEndpoint
import xyz.block.trailblaze.llm.config.LlmAuthResolver
import xyz.block.trailblaze.llm.config.LlmConfigLoader
import xyz.block.trailblaze.llm.config.ResolvedProviderAuth
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.transport.AndroidWireTransport
import xyz.block.trailblaze.transport.AndroidWireTransportMode
import xyz.block.trailblaze.util.Console
import io.ktor.server.application.ApplicationStopped
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Duration.Companion.seconds

/**
 * Callbacks for CLI endpoints. These are provided by the desktop app.
 */
data class CliEndpointCallbacks(
  /** Called when CLI requests a trail run. The progress callback receives status messages. */
  val onRunRequest: suspend (CliRunRequest, onProgress: (String) -> Unit) -> CliRunResponse,
  /** Called when CLI requests shutdown */
  val onShutdownRequest: () -> Unit,
  /**
   * Called when CLI requests to show the window. Returns `true` when a window handler ran,
   * `false` when this daemon has no window to show (headless server, or the desktop UI hasn't
   * installed its callback yet) — callers branch on the resulting `success` flag.
   */
  val onShowWindowRequest: () -> Boolean,
  /**
   * Provides current daemon status. Suspend so implementations can await (bounded) device
   * queries - a plain function here invited `runBlocking` inside the Ktor handler, which runs
   * on a Netty event-loop thread. When the device layer wedged, each status poll parked one
   * worker permanently until the whole pool starved and every route (including /ping) hung.
   */
  val statusProvider: suspend () -> CliStatusResponse,
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
    /** Trail Runner route exposed by this server instance, or null when it is not registered. */
    trailRunnerPath: String? = null,
    installContentNegotiation: Boolean = true,
    cliCallbacks: CliEndpointCallbacks? = null,
    resolvedAuths: Map<String, ResolvedProviderAuth>? = null,
    /**
     * Optional hook for host-layer modules (trailblaze-host, etc.) to register
     * additional Ktor routes alongside the server's built-ins. Invoked inside the
     * `routing { }` block immediately before the 404 catchall, so any path the
     * callback registers wins over the catchall.
     *
     * Used by the desktop's waypoint-graph endpoint, which lives in trailblaze-host
     * (where waypoint discovery is) but needs to expose itself on the same Ktor
     * server the daemon already runs. Keeps the dep direction clean (server doesn't
     * know about host) by inverting registration into a callback.
     */
    additionalRouteRegistration: (Routing.() -> Unit)? = null,
  ) {
    logsServerKtorEndpointsWithWireTransport(
      logsRepo = logsRepo,
      homeCallbackHandler = homeCallbackHandler,
      trailRunnerPath = trailRunnerPath,
      installContentNegotiation = installContentNegotiation,
      cliCallbacks = cliCallbacks,
      resolvedAuths = resolvedAuths,
      androidWireTransportMode = AndroidWireTransport.mode,
      additionalRouteRegistration = additionalRouteRegistration,
    )
  }

  @OptIn(ExperimentalEncodingApi::class)
  internal fun Application.logsServerKtorEndpointsWithWireTransport(
    logsRepo: LogsRepo,
    homeCallbackHandler: ((parameters: Map<String, List<String>>) -> Result<String>)?,
    trailRunnerPath: String?,
    installContentNegotiation: Boolean,
    cliCallbacks: CliEndpointCallbacks?,
    resolvedAuths: Map<String, ResolvedProviderAuth>?,
    androidWireTransportMode: AndroidWireTransportMode,
    additionalRouteRegistration: (Routing.() -> Unit)?,
  ) {
    val auths = resolvedAuths ?: LlmAuthResolver.resolveAll(LlmConfigLoader.load())
    if (installContentNegotiation) {
      install(ContentNegotiation) {
        json(TrailblazeJsonInstance)
      }
    }
    install(WebSockets) { pingPeriod = 15.seconds }
    install(CORS) {
      anyHost()
      anyMethod()
      allowHeader("Content-Type")
      allowHeader("Authorization")
    }
    routing {
      HomeEndpoint.register(
        routing = this,
        logsRepo = logsRepo,
        homeCallbackHandler = homeCallbackHandler,
        trailRunnerPath = trailRunnerPath,
      )
      PingEndpoint.register(this)
      GetEndpointSessionDetail.register(this, logsRepo)
      AgentLogEndpoint.register(this, logsRepo)
      DeleteLogsEndpoint.register(this, logsRepo)
      LogScreenshotPostEndpoint.register(this, logsRepo)
      LogTracePostEndpoint.register(this, logsRepo)
      if (androidWireTransportMode != AndroidWireTransportMode.JSON) {
        LogWebSocketEndpoint.register(this, logsRepo)
      }
      ReverseProxyEndpoint.register(this, logsRepo, auths)
      GenerateReportEndpoint.register(this, logsRepo)
      StoryboardEndpoint.register(this, logsRepo)
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
        CliShutdownEndpoint.register(this, callbacks.onShutdownRequest, runManager::activeRunSummaries)
        CliShowWindowEndpoint.register(this, callbacks.onShowWindowRequest)
        // activeRuns lives on the CliRunManager created here, so it's stamped onto the
        // status response server-side rather than by each app's statusProvider. Derive the
        // count from the same summaries snapshot so the two fields can't disagree if a run
        // starts/finishes between two separate reads.
        CliStatusEndpoint.register(this) {
          val summaries = runManager.activeRunSummaries()
          callbacks.statusProvider().copy(
            activeRuns = summaries.size,
            activeRunSummaries = summaries,
          )
        }
        callbacks.onCliExecRequest?.let { CliExecEndpoint.register(this, it) }
      }

      // Host-layer routes (e.g. waypoint graph view from trailblaze-host) — registered
      // before the catchall so they can claim paths the server doesn't know about.
      additionalRouteRegistration?.invoke(this)

      route("{...}") {
        handle {
          Console.log("Unhandled route: ${call.request.uri} [${call.request.httpMethod}]")
          call.respond(HttpStatusCode.NotFound)
        }
      }
    }
  }
}
