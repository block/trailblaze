package xyz.block.trailblaze.logs.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.send
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okio.ByteString.Companion.toByteString
import xyz.block.trailblaze.ondevice.rpc.proto.LogUploadAck
import xyz.block.trailblaze.ondevice.rpc.proto.LogUploadEnvelope
import xyz.block.trailblaze.ondevice.rpc.proto.OnDeviceRpcProtoCodec
import xyz.block.trailblaze.ondevice.rpc.proto.TraceUpload
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.server.websocket.WebSockets as ServerWebSockets

class TrailblazeLogWebSocketClientTest {

  /**
   * Log uploads overlap on a device — driver logging is async while tool logging uploads on its own
   * — and each send releases the send mutex before waiting for its ack. So the size a send reports
   * has to travel with that send's own result: a size parked on the client is read back after some
   * other upload has already overwritten it, and the per-action trace then bills one upload's bytes
   * to another.
   */
  @Test
  fun `each overlapping upload reports the size of its own frame`() {
    val port = ServerSocket(0).use { it.localPort }
    val frameSizesByUploadId = ConcurrentHashMap<Long, Long>()
    val framesArrived = AtomicInteger()
    val bothFramesArrived = CompletableDeferred<Unit>()

    val server = embeddedServer(CIO, port = port) {
      install(ServerWebSockets)
      routing {
        webSocket("/logs-ws") {
          for (frame in incoming) {
            if (frame !is Frame.Binary) continue
            val bytes = frame.readBytes()
            val envelope = OnDeviceRpcProtoCodec.decodeLogUpload(bytes)
            frameSizesByUploadId[envelope.upload_id] = bytes.size.toLong()
            if (framesArrived.incrementAndGet() == 2) bothFramesArrived.complete(Unit)
            // Withhold every ack until both frames are on the wire, so both sends are genuinely in
            // flight together rather than one after the other.
            launch {
              bothFramesArrived.await()
              send(
                Frame.Binary(
                  fin = true,
                  data = OnDeviceRpcProtoCodec.encode(
                    LogUploadAck(upload_id = envelope.upload_id, success = true),
                  ),
                ),
              )
            }
          }
        }
      }
    }.start(wait = false)

    val httpClient = HttpClient(OkHttp) { install(ClientWebSockets) }
    val client = TrailblazeLogWebSocketClient(httpClient, "http://localhost:$port")
    val smallUploadId = AtomicLong(-1)
    val largeUploadId = AtomicLong(-1)
    try {
      val (small, large) = runBlocking {
        val smallSend = async {
          client.send { id ->
            smallUploadId.set(id)
            traceUpload(id, payload = ByteArray(8))
          }
        }
        val largeSend = async {
          client.send { id ->
            largeUploadId.set(id)
            traceUpload(id, payload = ByteArray(200_000))
          }
        }
        smallSend.await() to largeSend.await()
      }

      assertIs<TrailblazeLogWebSocketClient.Attempt.Success>(small)
      assertIs<TrailblazeLogWebSocketClient.Attempt.Success>(large)
      assertEquals(
        frameSizesByUploadId[smallUploadId.get()],
        small.wireBytes,
        "the small upload must report the bytes of the frame that carried it",
      )
      assertEquals(
        frameSizesByUploadId[largeUploadId.get()],
        large.wireBytes,
        "the large upload must report the bytes of the frame that carried it",
      )
      // Guards the two assertions above from passing on two uploads that happened to be the same
      // size, where a swapped attribution would be invisible.
      assertTrue(
        large.wireBytes > small.wireBytes * 10,
        "expected the two uploads to differ in size, got ${small.wireBytes} and ${large.wireBytes}",
      )
    } finally {
      client.close()
      httpClient.close()
      server.stop(gracePeriodMillis = 0, timeoutMillis = 500)
    }
  }

  private fun traceUpload(uploadId: Long, payload: ByteArray) = LogUploadEnvelope(
    upload_id = uploadId,
    trace = TraceUpload(
      session_id = "overlapping-uploads",
      trace_json = payload.toByteString(),
      clock = TrailblazeLogServerClient.HOST_CLOCK,
    ),
  )
}
