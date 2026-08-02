package xyz.block.trailblaze.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.host.MockRpcServer
import xyz.block.trailblaze.host.OnDeviceRpcClientPool
import xyz.block.trailblaze.llm.RunYamlResponse
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateRequest
import xyz.block.trailblaze.mcp.android.ondevice.rpc.OnDeviceRpcClient
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.util.UiAutomationHandleErrors

class TrailblazeMcpBridgeRecoveryTest {

  private val affectedDevice = TrailblazeDeviceId(
    instanceId = "test-direct-mcp-wedged-device",
    trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
  )

  private val unaffectedDevice = TrailblazeDeviceId(
    instanceId = "test-direct-mcp-healthy-device",
    trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
  )

  private val legacyWedgeMessage =
    "${UiAutomationHandleErrors.NON_RECOVERABLE_RETRY_FAILED_PHRASE}. The on-device server's " +
      "instrumentation is in a ${UiAutomationHandleErrors.NON_RECOVERABLE_STATE_PHRASE} — " +
      "restart the Trailblaze on-device server."

  private val reflectionBlockedWedgeMessage =
    "UiAutomation is not connected and the " +
      "${UiAutomationHandleErrors.NON_RECOVERABLE_CACHE_CLEAR_FAILED_PHRASE}."

  @Test
  fun `structured MCP wedge arms only its device and stays armed until runner recovery`() {
    withRecoveryPool { recovery, pool ->
      val affectedClient = pool.get(affectedDevice)
      val unaffectedClient = pool.get(unaffectedDevice)

      assertTrue(
        affectedClient.noteIfNonRecoverableWedge(
          RunYamlResponse(
            sessionId = SessionId("direct-mcp-structured-wedge"),
            success = false,
            errorMessage = "on-device failure",
            nonRecoverableWedge = true,
          ),
        ),
      )
      assertTrue(recovery.requiresRestart(affectedDevice))
      assertFalse(recovery.requiresRestart(unaffectedDevice))
      assertSame(affectedClient, pool.get(affectedDevice))
      assertSame(unaffectedClient, pool.get(unaffectedDevice))

      pool.evict(affectedDevice)

      assertTrue(recovery.requiresRestart(affectedDevice))
      assertNotSame(affectedClient, pool.get(affectedDevice))
      assertSame(unaffectedClient, pool.get(unaffectedDevice))

      recovery.markRecovered(affectedDevice)

      assertFalse(recovery.requiresRestart(affectedDevice))
      assertFalse(recovery.requiresRestart(unaffectedDevice))
    }
  }

  @Test
  fun `untagged legacy MCP response arms the affected runner`() {
    assertInlineResponseArmsRunner(legacyWedgeMessage)
  }

  @Test
  fun `untagged Android 35 MCP response arms the affected runner`() {
    assertInlineResponseArmsRunner(reflectionBlockedWedgeMessage)
  }

  @Test
  fun `legacy MCP HTTP failure arms the affected runner`() {
    assertHttpFailureArmsRunner(legacyWedgeMessage)
  }

  @Test
  fun `Android 35 MCP HTTP failure arms the affected runner`() {
    assertHttpFailureArmsRunner(reflectionBlockedWedgeMessage)
  }

  @Test
  fun `ordinary MCP failure does not arm or evict either runner`() {
    withRecoveryPool { recovery, pool ->
      val affectedClient = pool.get(affectedDevice)
      val unaffectedClient = pool.get(unaffectedDevice)

      assertFalse(
        affectedClient.noteIfNonRecoverableWedge(
          RunYamlResponse(
            sessionId = SessionId("direct-mcp-ordinary-failure"),
            success = false,
            errorMessage = "Element not found",
          ),
        ),
      )

      assertFalse(recovery.requiresRestart(affectedDevice))
      assertFalse(recovery.requiresRestart(unaffectedDevice))
      assertSame(affectedClient, pool.get(affectedDevice))
      assertSame(unaffectedClient, pool.get(unaffectedDevice))
    }
  }

  private fun assertInlineResponseArmsRunner(errorMessage: String) {
    withRecoveryPool { recovery, pool ->
      val unaffectedClient = pool.get(unaffectedDevice)

      assertTrue(
        pool.get(affectedDevice).noteIfNonRecoverableWedge(
          RunYamlResponse(
            sessionId = SessionId("direct-mcp-legacy-wedge"),
            success = false,
            errorMessage = errorMessage,
          ),
        ),
      )

      assertTrue(recovery.requiresRestart(affectedDevice))
      assertFalse(recovery.requiresRestart(unaffectedDevice))
      assertSame(unaffectedClient, pool.get(unaffectedDevice))
    }
  }

  private fun assertHttpFailureArmsRunner(errorMessage: String) {
    val server = MockRpcServer(affectedDevice)
    server.responseBody =
      """{"errorType":"UNKNOWN_ERROR","message":"Failed to capture screen state","details":${
        TrailblazeJsonInstance.encodeToString(String.serializer(), errorMessage)
      }}"""
    server.start()

    try {
      withRecoveryPool { recovery, pool ->
        val unaffectedClient = pool.get(unaffectedDevice)
        val result = runBlocking {
          pool.get(affectedDevice).rpcCall(GetScreenStateRequest(includeScreenshot = false))
        }

        assertTrue(result is RpcResult.Failure)
        assertTrue(recovery.requiresRestart(affectedDevice))
        assertFalse(recovery.requiresRestart(unaffectedDevice))
        assertSame(unaffectedClient, pool.get(unaffectedDevice))
        assertEquals(1, server.requestLog["/rpc/GetScreenStateRequest"]?.size)
      }
    } finally {
      server.stop()
    }
  }

  private inline fun withRecoveryPool(
    block: (
      TrailblazeMcpBridgeImpl.OnDeviceRunnerRecovery,
      OnDeviceRpcClientPool<OnDeviceRpcClient>,
    ) -> Unit,
  ) {
    val recovery = TrailblazeMcpBridgeImpl.OnDeviceRunnerRecovery()
    val pool = OnDeviceRpcClientPool { deviceId ->
      OnDeviceRpcClient(
        trailblazeDeviceId = deviceId,
        onNonRecoverableWedge = { recovery.arm(deviceId) },
      )
    }

    try {
      block(recovery, pool)
    } finally {
      pool.close()
    }
  }
}
