package xyz.block.trailblaze.compose.driver.rpc

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.compose.driver.ComposeTrailblazeAgent.Companion.DEFAULT_VIEWPORT_HEIGHT
import xyz.block.trailblaze.compose.driver.ComposeTrailblazeAgent.Companion.DEFAULT_VIEWPORT_WIDTH
import xyz.block.trailblaze.devices.TrailblazeDevicePort
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult

/**
 * Embedded HTTP server that exposes Compose test operations over RPC.
 *
 * This server wraps a [ComposeUiTest] instance and provides two endpoints:
 * - `GET /ping` — health check
 * - `POST /rpc/GetScreenStateRequest` — captures screenshot + view hierarchy + semantics text
 * - `POST /rpc/ExecuteToolsRequest` — deserializes and executes compose tools
 *
 * A shared [Mutex] prevents concurrent tool execution against [ComposeUiTest].
 */
@OptIn(ExperimentalTestApi::class)
class ComposeRpcServer(
  private val composeUiTest: ComposeUiTest,
  private val port: Int = COMPOSE_DEFAULT_PORT,
  private val viewportWidth: Int = DEFAULT_VIEWPORT_WIDTH,
  private val viewportHeight: Int = DEFAULT_VIEWPORT_HEIGHT,
) {
  private val mutex = Mutex()
  private var server: EmbeddedServer<*, *>? = null

  fun start(wait: Boolean = true) {
    server =
      embeddedServer(factory = CIO, port = port) {
          install(ContentNegotiation) { json(TrailblazeJsonInstance) }

          routing {
            get("/ping") {
              call.respond(
                PingResponse(
                  status = PING_STATUS_RUNNING,
                  port = port,
                  driver = DRIVER_NAME,
                )
              )
            }

            registerRpcHandler(
              GetScreenStateHandler(
                composeUiTest = composeUiTest,
                mutex = mutex,
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight,
              )
            )

            registerRpcHandler(
              ExecuteToolsHandler(
                composeUiTest = composeUiTest,
                mutex = mutex,
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight,
              )
            )

            post("/rpc/{...}") {
              call.respondRpcError(
                status = HttpStatusCode.NotFound,
                errorType = RpcResult.ErrorType.HTTP_ERROR,
                message =
                  "No RPC handler registered for path: ${call.request.local.uri}",
              )
            }

            route("{...}") { handle { call.respond(HttpStatusCode.NotFound) } }
          }
        }
        .start(wait = wait)
  }

  fun stop() {
    server?.stop(gracePeriodMillis = 100, timeoutMillis = 500)
    server = null
  }

  @Serializable
  data class PingResponse(
    val status: String,
    val port: Int,
    val driver: String,
  )

  companion object {
    const val COMPOSE_DEFAULT_PORT = TrailblazeDevicePort.COMPOSE_DEFAULT_RPC_PORT
    const val PING_STATUS_RUNNING = "running"
    const val DRIVER_NAME = "compose"
  }
}
