package xyz.block.trailblaze.host

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.util.concurrent.ConcurrentHashMap
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePort.getTrailblazeOnDeviceSpecificPort
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance

/**
 * Lightweight Ktor server that mimics the on-device RPC server for testing. Starts on the
 * deterministic port derived from the device ID, so an OnDeviceRpcClient constructed with the same
 * device ID will connect to it automatically.
 *
 * By default every POST returns [responseStatus]/[responseBody]. Tests that need path-specific
 * behavior (e.g., different responses for `/rpc/RunYamlRequest` vs `/rpc/GetExecutionStatusRequest`)
 * can register a handler via [onPost]; unmatched paths fall back to the default. Every incoming
 * request body is appended to [requestLog] keyed by its `/rpc/<Name>` path.
 */
class MockRpcServer(deviceId: TrailblazeDeviceId) {

  val port: Int = deviceId.getTrailblazeOnDeviceSpecificPort()

  /** Default response status and JSON body returned when no per-path handler is registered. */
  @Volatile var responseStatus: HttpStatusCode = HttpStatusCode.InternalServerError

  @Volatile
  var responseBody: String =
    """{"errorType":"UNKNOWN_ERROR","message":"Mock: no handler","details":null}"""

  /** Raw request bodies received, keyed by URL path (e.g., `/rpc/RunYamlRequest`). */
  val requestLog: MutableMap<String, MutableList<String>> = ConcurrentHashMap()

  private data class HandlerResponse(val status: HttpStatusCode, val body: String)

  private val handlers = ConcurrentHashMap<String, () -> HandlerResponse>()

  /**
   * Register a response for a specific `/rpc/<RequestName>` path. The lambda is invoked on
   * every matching request, so tests can return different responses across calls (e.g., simulate
   * RUNNING→COMPLETED status transitions).
   */
  fun onPost(path: String, respond: () -> Pair<HttpStatusCode, String>) {
    handlers[path] = {
      val (status, body) = respond()
      HandlerResponse(status, body)
    }
  }

  private val server =
    embeddedServer(CIO, port = port) {
      install(ContentNegotiation) { json(TrailblazeJsonInstance) }
      routing {
        post("/rpc/{path...}") {
          val path = call.request.local.uri.substringBefore('?')
          val body = call.receiveText()
          requestLog.getOrPut(path) { mutableListOf() }.add(body)
          val handler = handlers[path]
          if (handler != null) {
            val response = handler()
            call.respondText(response.body, ContentType.Application.Json, response.status)
          } else {
            call.respondText(responseBody, ContentType.Application.Json, responseStatus)
          }
        }
      }
    }

  fun start() {
    server.start(wait = false)
    Thread.sleep(300)
  }

  fun stop() {
    server.stop(gracePeriodMillis = 0, timeoutMillis = 500)
  }
}
