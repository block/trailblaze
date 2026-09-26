package xyz.block.trailblaze.mcp.android.ondevice.rpc

import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.send
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Test
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.ondevice.rpc.proto.OnDeviceRpcProtoCodec
import xyz.block.trailblaze.ondevice.rpc.proto.RpcResponseEnvelope
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class OnDeviceRpcWebSocketClientTest {

  /** Private to this test, so production can never give it a protobuf mapping. */
  private object UnmappedProbeRequest : RpcRequest<Unit>

  @Test
  fun `unmapped requests fall back before connecting`() {
    val connectionCount = AtomicInteger()
    // Reachable server: a connect failure can no longer masquerade as the unmapped fallback.
    val server = embeddedServer(CIO, port = EPHEMERAL_PORT) {
      install(WebSockets)
      routing {
        webSocket("/rpc-ws") {
          connectionCount.incrementAndGet()
        }
      }
    }
    val port = server.startOnEphemeralPort()

    try {
      val client = OnDeviceRpcWebSocketClient("http://localhost:$port")
      try {
        val result = runBlocking {
          client.call(UnmappedProbeRequest, timeoutMs = 5_000)
        }

        assertIs<OnDeviceRpcWebSocketClient.Attempt.FallbackToHttp>(result)
        assertEquals(0, connectionCount.get())
      } finally {
        client.close()
      }
    } finally {
      server.stop(gracePeriodMillis = 0, timeoutMillis = 500)
    }
  }

  @Test
  fun `typed RPC calls share one binary socket`() {
    val connectionCount = AtomicInteger()
    val screenResponse = GetScreenStateResponse(
      viewHierarchy = ViewHierarchyTreeNode(text = "Home"),
      screenshotBase64 = null,
      deviceWidth = 1080,
      deviceHeight = 1920,
      trailblazeNodeTree = TrailblazeNode(
        nodeId = 1,
        driverDetail = DriverNodeDetail.AndroidAccessibility(text = "Home"),
      ),
    ).apply { screenshotBytes = byteArrayOf(1, 2, 3) }
    val server = embeddedServer(CIO, port = EPHEMERAL_PORT) {
      install(WebSockets)
      routing {
        webSocket("/rpc-ws") {
          connectionCount.incrementAndGet()
          for (frame in incoming) {
            if (frame !is Frame.Binary) continue
            val request = OnDeviceRpcProtoCodec.decodeRequest(frame.readBytes())
            val response = when {
              request.get_screen_state != null -> RpcResponseEnvelope(
                request_id = request.request_id,
                get_screen_state = OnDeviceRpcProtoCodec.run { screenResponse.toProto() },
              )
              request.drain_session != null -> RpcResponseEnvelope(
                request_id = request.request_id,
                drain_session = OnDeviceRpcProtoCodec.run {
                  DrainSessionResponse(uiAutomationCleared = true).toProto()
                },
              )
              else -> error("request omitted payload")
            }
            send(Frame.Binary(true, OnDeviceRpcProtoCodec.encode(response)))
          }
        }
      }
    }
    val port = server.startOnEphemeralPort()

    try {
      val client = OnDeviceRpcWebSocketClient("http://localhost:$port")
      try {
        val first = runBlocking {
          client.call(GetScreenStateRequest(includeScreenshot = true), timeoutMs = 5_000)
        }
        val second = runBlocking {
          client.call(DrainSessionRequest(reason = "test"), timeoutMs = 5_000)
        }

        val decodedScreen = assertIs<OnDeviceRpcWebSocketClient.Attempt.Success<GetScreenStateResponse>>(first).value
        assertContentEquals(byteArrayOf(1, 2, 3), decodedScreen.screenshotBytes)
        assertEquals("Home", decodedScreen.viewHierarchy.text)
        assertEquals(
          true,
          assertIs<OnDeviceRpcWebSocketClient.Attempt.Success<DrainSessionResponse>>(second)
            .value.uiAutomationCleared,
        )
        assertEquals(1, connectionCount.get())
      } finally {
        client.close()
      }
    } finally {
      server.stop(gracePeriodMillis = 0, timeoutMillis = 500)
    }
  }

  @Test
  fun `timed out socket is replaced before the next call`() {
    val connectionCount = AtomicInteger()
    val server = embeddedServer(CIO, port = EPHEMERAL_PORT) {
      install(WebSockets)
      routing {
        webSocket("/rpc-ws") {
          val connection = connectionCount.incrementAndGet()
          for (frame in incoming) {
            if (frame !is Frame.Binary) continue
            if (connection == 1) continue
            val request = OnDeviceRpcProtoCodec.decodeRequest(frame.readBytes())
            send(
              Frame.Binary(
                true,
                OnDeviceRpcProtoCodec.encode(
                  RpcResponseEnvelope(
                    request_id = request.request_id,
                    drain_session = OnDeviceRpcProtoCodec.run {
                      DrainSessionResponse(uiAutomationCleared = true).toProto()
                    },
                  ),
                ),
              ),
            )
          }
        }
      }
    }
    val port = server.startOnEphemeralPort()

    try {
      val client = OnDeviceRpcWebSocketClient("http://localhost:$port")
      try {
        val timedOut = runBlocking {
          client.call(DrainSessionRequest(reason = "timeout"), timeoutMs = 500)
        }
        assertIs<OnDeviceRpcWebSocketClient.Attempt.Failure>(timedOut)

        val recovered = runBlocking {
          client.call(DrainSessionRequest(reason = "retry"), timeoutMs = 5_000)
        }
        assertEquals(
          true,
          assertIs<OnDeviceRpcWebSocketClient.Attempt.Success<DrainSessionResponse>>(recovered)
            .value.uiAutomationCleared,
        )
      } finally {
        client.close()
      }
    } finally {
      server.stop(gracePeriodMillis = 0, timeoutMillis = 500)
    }
  }

  private companion object {
    /** Ktor's "bind whatever the OS gives you"; the real port comes from [startOnEphemeralPort]. */
    const val EPHEMERAL_PORT = 0

    /**
     * Binds port 0 and returns the port Ktor actually took. Probing a free port with a throwaway
     * `ServerSocket(0)` and handing Ktor the number leaves the port unowned between the close and
     * the bind, and a busy CI agent fills that gap — a `BindException` unrelated to the code under
     * test. `start()` throws on a failed bind, so the resolve only ever waits on one that landed.
     */
    fun EmbeddedServer<*, *>.startOnEphemeralPort(): Int {
      start(wait = false)
      return runBlocking { engine.resolvedConnectors() }.first().port
    }
  }
}
