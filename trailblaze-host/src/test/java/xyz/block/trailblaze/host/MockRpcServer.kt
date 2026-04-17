package xyz.block.trailblaze.host

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePort.getTrailblazeOnDeviceSpecificPort
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance

/**
 * Lightweight Ktor server that mimics the on-device RPC server for testing. Starts on the
 * deterministic port derived from the device ID, so an OnDeviceRpcClient constructed with the same
 * device ID will connect to it automatically.
 */
class MockRpcServer(deviceId: TrailblazeDeviceId) {

  val port: Int = deviceId.getTrailblazeOnDeviceSpecificPort()

  /** Response status and JSON body that the mock server returns for any RPC POST. */
  @Volatile var responseStatus: HttpStatusCode = HttpStatusCode.InternalServerError

  @Volatile
  var responseBody: String =
    """{"errorType":"UNKNOWN_ERROR","message":"Mock: no handler","details":null}"""

  private val server =
    embeddedServer(CIO, port = port) {
      install(ContentNegotiation) { json(TrailblazeJsonInstance) }
      routing {
        post("/rpc/{path...}") {
          call.respondText(responseBody, ContentType.Application.Json, responseStatus)
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
