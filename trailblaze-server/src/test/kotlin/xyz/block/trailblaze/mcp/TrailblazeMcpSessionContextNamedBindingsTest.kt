package xyz.block.trailblaze.mcp

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.mcp.models.McpSessionId
import xyz.block.trailblaze.toolcalls.SessionDeviceBindings

/**
 * Pins the named-binding state machine on the MCP session — the part the `device` tool and
 * `switchDevice` both drive.
 *
 * The roster is held as a [SessionDeviceBindings], which is immutable and always starts on its
 * FIRST entry, so every add and remove rebuilds it. That rebuild is the interesting behavior: it has
 * to carry the active name across, or binding a third device would silently hand the session back to
 * the first one. These tests exercise the case that catches it (active != first entry), which only
 * exists once a switch has happened.
 */
class TrailblazeMcpSessionContextNamedBindingsTest {

  @Test
  fun `a fresh session has no roster`() {
    val context = context()

    assertNull(context.namedDeviceBindings)
    assertNull(context.activeDeviceName())
    assertEquals(emptyList(), context.boundDeviceNames())
  }

  @Test
  fun `the first bind is active and later binds are not`() {
    val context = context()

    assertTrue(context.bindNamedDevice("seller", device("emulator-5554")))
    assertFalse(context.bindNamedDevice("buyer", device("emulator-5556")))

    assertEquals("seller", context.activeDeviceName())
    assertEquals(listOf("seller", "buyer"), context.boundDeviceNames())
  }

  /** The rebuild's whole reason for existing: a bind after a switch must not undo the switch. */
  @Test
  fun `a bind after a switch keeps the switched-to device active`() {
    val context = context()
    context.bindNamedDevice("seller", device("emulator-5554"))
    context.bindNamedDevice("buyer", device("emulator-5556"))
    context.switchActiveNamedDevice("buyer")

    context.bindNamedDevice("kitchen", device("emulator-5558"))

    assertEquals("buyer", context.activeDeviceName())
    assertEquals(listOf("seller", "buyer", "kitchen"), context.boundDeviceNames())
  }

  /** Same trap on the remove path: unbinding a non-active name must not move the active one. */
  @Test
  fun `an unbind after a switch keeps the switched-to device active`() {
    val context = context()
    context.bindNamedDevice("seller", device("emulator-5554"))
    context.bindNamedDevice("buyer", device("emulator-5556"))
    context.bindNamedDevice("kitchen", device("emulator-5558"))
    context.switchActiveNamedDevice("buyer")

    val result = context.unbindNamedDevice("kitchen")

    assertTrue(result is TrailblazeMcpSessionContext.UnbindResult.Unbound)
    assertEquals(false, (result as TrailblazeMcpSessionContext.UnbindResult.Unbound).activeChanged)
    assertEquals("buyer", context.activeDeviceName())
  }

  @Test
  fun `rebinding a name in place keeps its position and the active name`() {
    val context = context()
    context.bindNamedDevice("seller", device("emulator-5554"))
    context.bindNamedDevice("buyer", device("emulator-5556"))
    context.switchActiveNamedDevice("buyer")

    assertFalse(context.bindNamedDevice("seller", device("emulator-9999")))

    assertEquals(listOf("seller", "buyer"), context.boundDeviceNames())
    assertEquals("buyer", context.activeDeviceName())
    assertEquals("emulator-9999", context.boundDevice("seller")?.trailblazeDeviceId?.instanceId)
  }

  @Test
  fun `unbinding the active name promotes the first remaining one`() {
    val context = context()
    context.bindNamedDevice("seller", device("emulator-5554"))
    context.bindNamedDevice("buyer", device("emulator-5556"))
    context.switchActiveNamedDevice("buyer")

    val result = context.unbindNamedDevice("buyer")

    val unbound = requireNotNull(result as? TrailblazeMcpSessionContext.UnbindResult.Unbound)
    assertEquals("seller", unbound.activeName)
    assertTrue(unbound.activeChanged)
    assertEquals("emulator-5556", unbound.unbound.trailblazeDeviceId.instanceId)
    assertEquals("seller", context.activeDeviceName())
  }

  @Test
  fun `the last remaining binding is kept`() {
    val context = context()
    context.bindNamedDevice("seller", device("emulator-5554"))

    assertEquals(
      TrailblazeMcpSessionContext.UnbindResult.LastRemaining,
      context.unbindNamedDevice("seller"),
    )
    assertEquals(listOf("seller"), context.boundDeviceNames())
  }

  @Test
  fun `unbinding an unbound name reports it`() {
    val context = context()
    context.bindNamedDevice("seller", device("emulator-5554"))

    assertEquals(
      TrailblazeMcpSessionContext.UnbindResult.NotBound,
      context.unbindNamedDevice("buyer"),
    )
  }

  @Test
  fun `switching to an unbound name changes nothing`() {
    val context = context()
    context.bindNamedDevice("seller", device("emulator-5554"))

    assertNull(context.switchActiveNamedDevice("buyer"))
    assertEquals("seller", context.activeDeviceName())
  }

  /** Clearing the session's device association drops the roster with it — no orphan names. */
  @Test
  fun `clearing the device association clears the roster`() {
    val context = context()
    context.bindNamedDevice("seller", device("emulator-5554"))
    context.bindNamedDevice("buyer", device("emulator-5556"))

    context.clearAssociatedDevice()

    assertNull(context.namedDeviceBindings)
    assertEquals(emptyList(), context.boundDeviceNames())
    assertNull(context.associatedDeviceId)
  }

  // ---- helpers ---------------------------------------------------------------------------------

  private fun context() = TrailblazeMcpSessionContext(
    mcpServerSession = null,
    mcpSessionId = McpSessionId("mcp-session-under-test"),
  )

  private fun device(instanceId: String) = SessionDeviceBindings.BoundDevice(
    trailblazeDeviceId = TrailblazeDeviceId(instanceId, TrailblazeDevicePlatform.ANDROID),
    trailblazeDeviceInfo = null,
    description = null,
    targetId = null,
  )
}
