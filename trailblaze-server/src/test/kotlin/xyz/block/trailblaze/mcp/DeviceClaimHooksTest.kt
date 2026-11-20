package xyz.block.trailblaze.mcp

import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** What the registry tells its [DeviceClaimHooks] as sessions claim and release devices. */
class DeviceClaimHooksTest {

  private val events = mutableListOf<String>()
  private var refuse = false
  private var toolCallInFlight: ((TrailblazeDeviceId) -> Boolean)? = null

  private val hooks = object : DeviceClaimHooks {
    override fun beforeClaim(deviceId: TrailblazeDeviceId) {
      if (refuse) throw IllegalStateException("not free")
      events += "before ${deviceId.instanceId}"
    }

    override fun onClaimed(deviceId: TrailblazeDeviceId) {
      events += "claimed ${deviceId.instanceId}"
    }

    override fun onReleased(deviceId: TrailblazeDeviceId) {
      events += "release ${deviceId.instanceId}"
    }

    override fun watchToolCalls(inFlight: (TrailblazeDeviceId) -> Boolean) {
      toolCallInFlight = inFlight
    }
  }

  private var busySession: String? = null
  private val registry = DeviceClaimRegistry(
    inFlightLookup = { session -> if (session == busySession) InFlightToolCall("blaze", null, "t") else null },
    hooks = hooks,
  )
  private val device = TrailblazeDeviceId("host:5555", TrailblazeDevicePlatform.ANDROID)

  @Test
  fun `claiming and releasing a device tells the hooks`() {
    registry.claim(device, "s1")
    registry.release(device, "s1")
    assertEquals(listOf("before host:5555", "claimed host:5555", "release host:5555"), events)
  }

  @Test
  fun `the hooks can see whether the session claiming a device is mid tool call`() {
    val inFlight = checkNotNull(toolCallInFlight)
    assertEquals(false, inFlight(device))
    registry.claim(device, "s1")
    assertEquals(false, inFlight(device))
    busySession = "s1"
    assertEquals(true, inFlight(device))
  }

  @Test
  fun `closing a session releases each device it held`() {
    val other = TrailblazeDeviceId("host:5557", TrailblazeDevicePlatform.ANDROID)
    registry.claim(device, "s1")
    registry.claim(other, "s1")
    registry.releaseAllForSession("s1")
    assertEquals(setOf("release host:5555", "release host:5557"), events.filter { it.startsWith("release") }.toSet())
  }

  @Test
  fun `a device passed to another session is not released`() {
    registry.claim(device, "s1")
    registry.claim(device, "s2")
    assertTrue(events.none { it.startsWith("release") }, events.toString())
  }

  @Test
  fun `a claim refused because the holder is busy does not release the holder's device`() {
    registry.claim(device, "s1")
    busySession = "s1"
    assertFailsWith<DeviceBusyException> { registry.claim(device, "s2") }
    assertTrue(events.none { it.startsWith("release") }, events.toString())
    assertEquals("s1", registry.getClaim(device)?.mcpSessionId)
  }

  @Test
  fun `a hook that refuses the claim leaves the device unclaimed`() {
    refuse = true
    // As the busy refusal callers already catch, so they roll back and report it, keeping its message.
    val refused = assertFailsWith<DeviceUnavailableException> { registry.claim(device, "s1") }
    assertEquals("not free", refused.message)
    assertEquals(device, refused.deviceId)
    assertEquals(null, registry.getClaim(device))
    assertTrue(events.isEmpty(), events.toString())
  }

  @Test
  fun `a stale claim that is evicted is released`() {
    val registry = DeviceClaimRegistry(claimTtlMs = -1, hooks = hooks)
    val other = TrailblazeDeviceId("host:5557", TrailblazeDevicePlatform.ANDROID)
    registry.claim(device, "s1")
    registry.claim(other, "s2")
    assertTrue("release host:5555" in events, events.toString())
  }

  @Test
  fun `releasing a device the session does not hold tells the hooks nothing`() {
    registry.claim(device, "s1")
    registry.release(device, "s2")
    assertEquals(listOf("before host:5555", "claimed host:5555"), events)
  }
}
