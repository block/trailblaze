package xyz.block.trailblaze.toolcalls.commands

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    assertEquals("device-buyer", bindings.active.trailblazeDeviceId.instanceId)
    val message = (result as TrailblazeToolResult.Success).message.orEmpty()
    assertTrue("buyer" in message && "seller" in message, "message must name both devices, was: $message")
  }

  @Test
  fun `switches to a device bound without probed info and still names it`() {
    // A handover needs identity, not geometry. A caller that binds a device by name with nothing
    // probed — an interactive session — must still be switchable to, and the confirmation must
    // still identify the device it landed on.
    val bindings = SessionDeviceBindings(
      devices = linkedMapOf(
        "seller" to boundDevice(instanceId = "device-seller"),
        "buyer" to SessionDeviceBindings.BoundDevice(
          trailblazeDeviceId = deviceId("device-buyer"),
          trailblazeDeviceInfo = null,
          description = null,
          targetId = null,
        ),
      ),
    )

    val result = runBlocking {
      SwitchDeviceTrailblazeTool(name = "buyer").execute(context(bindings))
    }

    assertTrue(result is TrailblazeToolResult.Success, "expected Success but was $result")
    assertEquals("buyer", bindings.activeName)
    val message = (result as TrailblazeToolResult.Success).message.orEmpty()
    assertTrue(
      "device-buyer" in message,
      "the handover confirmation must identify the device even unprobed, was: $message",
    )
  }

  @Test
  fun `rejects a binding whose probed info names a different device`() {
    // Two fields can name the device; a mismatch would let `switchDevice` resolve one identity
    // while the prompt roster describes another. Fail at bind time, not mid-trail.
    val failure = assertFailsWith<IllegalArgumentException> {
      SessionDeviceBindings.BoundDevice(
        trailblazeDeviceId = deviceId("device-buyer"),
        trailblazeDeviceInfo = deviceInfo("device-seller"),
        description = null,
        targetId = null,
      )
    }
    assertTrue(
      "device-buyer" in failure.message.orEmpty() && "device-seller" in failure.message.orEmpty(),
      "the failure must name both disagreeing ids, was: ${failure.message}",
    )
  }

  @Test
  fun `rejects two names bound to the same device`() {
    // Reachable precisely because a binding is identity-only: hand the same serial in under two
    // names and the roster advertises a pair that is one device. `switchDevice` would then report
    // success and change nothing, and every assertion after it would run on the display the agent
    // believes it left — a handover that passes while proving nothing.
    val failure = assertFailsWith<IllegalArgumentException> {
      SessionDeviceBindings(
        devices = mapOf(
          "seller" to boundDevice(instanceId = "emulator-5560"),
          "buyer" to boundDevice(instanceId = "emulator-5560"),
        ),
      )
    }
    val message = failure.message.orEmpty()
    assertTrue(
      "seller" in message && "buyer" in message && "emulator-5560" in message,
      "the failure must name both colliding names and the device, was: $message",
    )
  }

  @Test
  fun `binds distinct devices under distinct names`() {
    // The invariant must not reject the normal pair — a same-platform, different-serial roster is
    // exactly what an X2 seller/buyer session binds.
    val bindings = bindings("seller", "buyer")
    assertEquals(setOf("seller", "buyer"), bindings.names)
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
      trailblazeDeviceId = deviceId(instanceId),
      trailblazeDeviceInfo = deviceInfo(instanceId),
      description = null,
      targetId = null,
    )

  private fun deviceId(instanceId: String): TrailblazeDeviceId = TrailblazeDeviceId(
    instanceId = instanceId,
    trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
  )

  private fun deviceInfo(instanceId: String): TrailblazeDeviceInfo = TrailblazeDeviceInfo(
    trailblazeDeviceId = deviceId(instanceId),
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
