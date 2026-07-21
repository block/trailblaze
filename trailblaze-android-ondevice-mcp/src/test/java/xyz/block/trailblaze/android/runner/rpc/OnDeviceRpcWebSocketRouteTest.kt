package xyz.block.trailblaze.android.runner.rpc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import xyz.block.trailblaze.mcp.android.ondevice.rpc.DrainSessionRequest
import xyz.block.trailblaze.mcp.android.ondevice.rpc.DrainSessionResponse
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.ondevice.rpc.proto.OnDeviceRpcProtoCodec
import xyz.block.trailblaze.ondevice.rpc.proto.RpcRequestEnvelope

class OnDeviceRpcWebSocketRouteTest {

  @Test
  fun `typed RPC is dispatched through the binary envelope`() = runBlocking {
    val request = RpcRequestEnvelope(
      request_id = 42,
      drain_session = OnDeviceRpcProtoCodec.run {
        DrainSessionRequest(reason = "test").toProto()
      },
    )

    val response = handleBinaryRequest(
      bytes = OnDeviceRpcProtoCodec.encode(request),
      handlers = OnDeviceProtoRpcHandlers(
        getScreenState = { error("unexpected screen request") },
        runYaml = { error("unexpected run request") },
        drainSession = {
          assertEquals("test", it.reason)
          RpcResult.Success(DrainSessionResponse(uiAutomationCleared = true))
        },
        subscribeToProgress = { error("unexpected progress request") },
        getExecutionStatus = { error("unexpected status request") },
        listActiveSessions = { error("unexpected list request") },
      ),
    )

    assertEquals(42, response.request_id)
    assertEquals(true, response.drain_session?.ui_automation_cleared)
    assertNull(response.failure)
  }
}
