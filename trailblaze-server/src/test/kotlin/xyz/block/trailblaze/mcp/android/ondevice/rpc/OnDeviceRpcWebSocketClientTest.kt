package xyz.block.trailblaze.mcp.android.ondevice.rpc

import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.send
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Test
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.ondevice.rpc.proto.OnDeviceRpcProtoCodec
import xyz.block.trailblaze.ondevice.rpc.proto.RpcResponseEnvelope
import xyz.block.trailblaze.mcp.android.ondevice.rpc.models.SelectToolSet
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class OnDeviceRpcWebSocketClientTest {

  @Test
  fun `unmapped legacy requests fall back before connecting`() {
    val client = OnDeviceRpcWebSocketClient("http://localhost:1")
    try {
      val result = runBlocking {
        client.call(SelectToolSet(toolSetNames = listOf("legacy")), timeoutMs = 5_000)
      }

      assertIs<OnDeviceRpcWebSocketClient.Attempt.FallbackToHttp>(result)
    } finally {
      client.close()
    }
  }

  @Test
  fun `typed RPC calls share one binary socket`() {
    val port = ServerSocket(0).use { it.localPort }
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
    val server = embeddedServer(CIO, port = port) {
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
    }.start(wait = false)

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
    val port = ServerSocket(0).use { it.localPort }
    val connectionCount = AtomicInteger()
    val server = embeddedServer(CIO, port = port) {
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
    }.start(wait = false)

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
}
