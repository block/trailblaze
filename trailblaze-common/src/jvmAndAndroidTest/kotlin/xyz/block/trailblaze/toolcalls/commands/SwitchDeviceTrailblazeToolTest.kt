package xyz.block.trailblaze.toolcalls.commands

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.toolcalls.SessionDeviceBindings
import xyz.block.trailblaze.toolcalls.ToolBatchScope
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * `switchDevice` is session-state mutation, not a device action — so the behavioral contract
 * is about the bindings it flips and the batch context it invalidates, plus the error shapes
 * an author sees when the session isn't multi-device.
 */
class SwitchDeviceTrailblazeToolTest {

  @Test
  fun `errors clearly when the session has no device bindings`() {
    val result = runBlocking {
      SwitchDeviceTrailblazeTool(name = "buyer").execute(context(deviceBindings = null))
    }
    assertTrue(result is TrailblazeToolResult.Error, "expected Error but was $result")
    val message = (result as TrailblazeToolResult.Error.ExceptionThrown).errorMessage
    assertTrue(
      "config.devices" in message.orEmpty(),
      "the error must tell the author HOW to bind devices, was: $message",
    )
  }

  @Test
  fun `errors listing the bound names when the requested name is unknown`() {
    val bindings = bindings("seller", "buyer")
    val result = runBlocking {
      SwitchDeviceTrailblazeTool(name = "kitchen").execute(context(bindings))
    }
    assertTrue(result is TrailblazeToolResult.Error, "expected Error but was $result")
    val message = (result as TrailblazeToolResult.Error.ExceptionThrown).errorMessage.orEmpty()
    assertTrue("seller" in message && "buyer" in message, "error must list bound names, was: $message")
    // A failed switch must not move the active device.
    assertEquals("seller", bindings.activeName)
  }

  @Test
  fun `switches the active device and reports the handover`() {
    val bindings = bindings("seller", "buyer")
    val result = runBlocking {
      SwitchDeviceTrailblazeTool(name = "buyer").execute(context(bindings))
    }
    assertTrue(result is TrailblazeToolResult.Success, "expected Success but was $result")
    assertEquals("buyer", bindings.activeName)
    assertEquals("device-buyer", bindings.active.trailblazeDeviceInfo.trailblazeDeviceId.instanceId)
    val message = (result as TrailblazeToolResult.Success).message.orEmpty()
    assertTrue("buyer" in message && "seller" in message, "message must name both devices, was: $message")
  }

  @Test
  fun `switching to the already-active device succeeds without side effects`() {
    // Idempotence is load-bearing for replay/retry: a re-run switch's post-condition already
    // holds and must not fail the trail.
    val bindings = bindings("seller", "buyer")
    bindings.switchTo("buyer")
    val result = runBlocking {
      SwitchDeviceTrailblazeTool(name = "buyer").execute(context(bindings))
    }
    assertTrue(result is TrailblazeToolResult.Success, "expected Success but was $result")
    assertEquals("buyer", bindings.activeName)
  }

  @Test
  fun `the session starts on the first declared device`() {
    // Declaration order is the contract: the first entry of the configuration's devices map
    // is where the trail starts — no reserved name, no marker.
    assertEquals("seller", bindings("seller", "buyer").activeName)
    assertEquals("buyer", bindings("buyer", "seller").activeName)
  }

  @Test
  fun `a successful switch invalidates the shared tool-batch context so the next dispatch rebuilds`() {
    // A recorded step's tools share ONE context (built for the pre-switch device). The switch
    // must drop it — otherwise every later tool in the recording dispatches against the old
    // device's executor and the handover silently doesn't take effect.
    val bindings = bindings("seller", "buyer")
    ToolBatchScope.enter()
    try {
      val before = ToolBatchScope.contextOrBuild { context(bindings) }
      val result = runBlocking { SwitchDeviceTrailblazeTool(name = "buyer").execute(before) }
      assertTrue(result is TrailblazeToolResult.Success, "expected Success but was $result")
      val after = ToolBatchScope.contextOrBuild { context(bindings) }
      assertNotSame(before, after, "the batch context must be rebuilt after a device switch")
    } finally {
      ToolBatchScope.exit()
    }
  }

  @Test
  fun `a same-device no-op switch keeps the shared batch context`() {
    // The idempotent path changes nothing, so rebuilding the context (and dropping its
    // cross-tool device-state caches, e.g. the Android clipboard cache) would be a regression.
    val bindings = bindings("seller", "buyer")
    ToolBatchScope.enter()
    try {
      val before = ToolBatchScope.contextOrBuild { context(bindings) }
      val result = runBlocking {
        SwitchDeviceTrailblazeTool(name = "seller").execute(before)
      }
      assertTrue(result is TrailblazeToolResult.Success, "expected Success but was $result")
      assertSame(before, ToolBatchScope.contextOrBuild { context(bindings) })
    } finally {
      ToolBatchScope.exit()
    }
  }

  private fun bindings(vararg names: String): SessionDeviceBindings = SessionDeviceBindings(
    devices = names.associateWith { boundDevice(instanceId = "device-$it") },
  )

  private fun boundDevice(instanceId: String): SessionDeviceBindings.BoundDevice =
    SessionDeviceBindings.BoundDevice(
      trailblazeDeviceInfo = deviceInfo(instanceId),
    )

  private fun deviceInfo(instanceId: String): TrailblazeDeviceInfo = TrailblazeDeviceInfo(
    trailblazeDeviceId = TrailblazeDeviceId(
      instanceId = instanceId,
      trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
    ),
    trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
    widthPixels = 1080,
    heightPixels = 1920,
  )

  private fun context(deviceBindings: SessionDeviceBindings?): TrailblazeToolExecutionContext =
    TrailblazeToolExecutionContext(
      screenState = null,
      traceId = null,
      trailblazeDeviceInfo = deviceInfo("device-seller"),
      sessionProvider = TrailblazeSessionProvider {
        TrailblazeSession(sessionId = SessionId("test-session"), startTime = Clock.System.now())
      },
      trailblazeLogger = TrailblazeLogger.createNoOp(),
      memory = AgentMemory(),
      deviceBindings = deviceBindings,
    )
}
