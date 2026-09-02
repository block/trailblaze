package xyz.block.trailblaze.logs.server.endpoints

import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.ByteString.Companion.toByteString
import xyz.block.trailblaze.logs.client.TrailblazeLogServerClient
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.server.ServerEndpoints.logsServerKtorEndpoints
import xyz.block.trailblaze.ondevice.rpc.proto.LogUploadEnvelope
import xyz.block.trailblaze.ondevice.rpc.proto.OnDeviceRpcProtoCodec
import xyz.block.trailblaze.ondevice.rpc.proto.TraceUpload
import xyz.block.trailblaze.report.utils.LogsRepo
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which clock an uploaded trace was stamped on, end to end.
 *
 * Every process that records part of a run uploads through this endpoint — including the host's own
 * runner — so the upload has to say. Assuming a device is the sender takes the host's spans off the
 * host timeline, and assuming the host is stretches the session window by the device's clock drift.
 */
class LogTracePostEndpointTest {

  private fun logsRepo(name: String): LogsRepo = LogsRepo(
    File.createTempFile(name, "").apply {
      delete()
      mkdirs()
    },
    watchFileSystem = false,
  )

  private fun span(name: String): String =
    """[{"name":"$name","cat":"tool","ph":"X","ts":1000,"dur":5,"pid":1,"tid":1}]"""

  /** The recorded `clock` of each event, in order. Null is the host's clock. */
  private fun clocksIn(logsRepo: LogsRepo, session: SessionId): List<String?> =
    Json.parseToJsonElement(logsRepo.getSessionDir(session).resolve("trace.json").readText())
      .jsonArray.map { it.jsonObject["clock"]?.jsonPrimitive?.content }

  @Test
  fun `a host upload is left on the host clock`() = testApplication {
    val logsRepo = logsRepo("host-clock-upload")
    application { logsServerKtorEndpoints(logsRepo) }
    val client = TrailblazeLogServerClient(createClient { }, "http://localhost")
    val session = SessionId("host-clock")

    runBlocking { client.sendTrace(session, span("hostTool")) }

    assertEquals(listOf(null), clocksIn(logsRepo, session))
  }

  @Test
  fun `a device upload is marked as device-clock`() = testApplication {
    val logsRepo = logsRepo("device-clock-upload")
    application { logsServerKtorEndpoints(logsRepo) }
    val client = TrailblazeLogServerClient(createClient { }, "http://localhost")
    val session = SessionId("device-clock")

    runBlocking { client.sendTrace(session, span("captureHierarchy"), onDeviceClock = true) }

    assertEquals(listOf(TrailblazeLogServerClient.DEVICE_CLOCK), clocksIn(logsRepo, session))
  }

  @Test
  fun `both halves of a run keep their own clocks in the merged file`() = testApplication {
    val logsRepo = logsRepo("both-halves")
    application { logsServerKtorEndpoints(logsRepo) }
    val client = TrailblazeLogServerClient(createClient { }, "http://localhost")
    val session = SessionId("both-halves")

    runBlocking {
      client.sendTrace(session, span("hostTool"))
      client.sendTrace(session, span("captureHierarchy"), onDeviceClock = true)
    }

    assertEquals(listOf(null, TrailblazeLogServerClient.DEVICE_CLOCK), clocksIn(logsRepo, session))
  }

  @Test
  fun `an older runner's unmarked log-socket upload is still read as a device`() = testApplication {
    // `/logs-ws` only ever carries device traffic, and a new host is expected to serve older runner
    // APKs — which send no marker at all. Reading those as host spans would put a device's clock
    // skew into the session window, which is the failure the marking exists to prevent.
    val logsRepo = logsRepo("legacy-socket-upload")
    application { logsServerKtorEndpoints(logsRepo) }
    val session = "legacy-socket"
    val envelope = LogUploadEnvelope(
      upload_id = 1,
      trace = TraceUpload(
        session_id = session,
        trace_json = span("captureHierarchy").encodeToByteArray().toByteString(),
      ),
    )

    createClient { install(WebSockets) }.webSocket("/logs-ws") {
      send(Frame.Binary(fin = true, data = OnDeviceRpcProtoCodec.encode(envelope)))
      incoming.receive()
    }

    assertEquals(
      listOf(TrailblazeLogServerClient.DEVICE_CLOCK),
      clocksIn(logsRepo, SessionId(session)),
    )
  }

  @Test
  fun `an older client's unmarked POST is read as the host`() = testApplication {
    // The HTTP route carries both, so absence reads as host: an unmarked device batch only widens
    // the session window, while a host batch mistaken for a device one leaves the timeline empty.
    val logsRepo = logsRepo("legacy-post-upload")
    application { logsServerKtorEndpoints(logsRepo) }
    val session = "legacy-post"

    createClient { }.post("/log/trace?session=$session") { setBody(span("hostTool")) }

    assertEquals(listOf(null), clocksIn(logsRepo, SessionId(session)))
  }

  @Test
  fun `the protobuf transport carries the clock too`() = testApplication {
    // The device may upload over either transport, so a clock that only rode the query parameter
    // would leave protobuf uploads unmarked.
    val logsRepo = logsRepo("protobuf-clock")
    application { logsServerKtorEndpoints(logsRepo) }
    val client = TrailblazeLogServerClient(
      httpClient = createClient { install(WebSockets) },
      baseUrl = "http://localhost",
      useBinaryTransport = true,
    )
    val session = SessionId("protobuf-clock")

    runBlocking {
      client.sendTrace(session, span("hostTool"))
      client.sendTrace(session, span("captureHierarchy"), onDeviceClock = true)
    }

    assertEquals(listOf(null, TrailblazeLogServerClient.DEVICE_CLOCK), clocksIn(logsRepo, session))
    client.close()
  }
}
