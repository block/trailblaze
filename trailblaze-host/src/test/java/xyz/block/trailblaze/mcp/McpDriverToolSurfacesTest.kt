package xyz.block.trailblaze.mcp

import org.junit.Test
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.driver.FakeHostDriverDescriptor
import xyz.block.trailblaze.host.driver.HostDriverDescriptorRegistry
import xyz.block.trailblaze.host.driver.ReferenceHostDriverDescriptors
import xyz.block.trailblaze.host.rules.BasePlaywrightElectronTest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two MCP tool-surface policies, asserted without an MCP server or a device.
 *
 * Both used to be `when (driverType)` blocks inside `TrailblazeMcpBridgeImpl`, which is not
 * constructible without a device manager, a logs repo and an LLM client factory — so neither
 * policy was ever asserted, and the two had drifted into naming different drivers for reasons
 * nothing recorded.
 */
class McpDriverToolSurfacesTest {

  private val descriptors = HostDriverDescriptorRegistry(ReferenceHostDriverDescriptors.all())

  private fun builtIn(driverType: TrailblazeDriverType?) =
    McpDriverToolSurfaces.builtInToolClasses(driverType, descriptors)

  private fun override(driverType: TrailblazeDriverType?) =
    McpDriverToolSurfaces.innerAgentOverride(driverType, descriptors)

  /**
   * Which drivers substitute their tools for the trailmap's. Stated as the exact set because both
   * directions break a session: a driver missing from it has nothing to drive its device with,
   * and a driver wrongly in it discards the rest of its target's declared toolsets.
   */
  @Test
  fun `only Playwright and Revyl substitute their tools for the trailmap's`() {
    val substituting = TrailblazeDriverType.entries.filter { builtIn(it).isNotEmpty() }.toSet()

    assertEquals(
      setOf(
        TrailblazeDriverType.PLAYWRIGHT_NATIVE,
        TrailblazeDriverType.PLAYWRIGHT_ELECTRON,
        TrailblazeDriverType.REVYL_ANDROID,
        TrailblazeDriverType.REVYL_IOS,
      ),
      substituting,
      "the trailmap's declarations are filtered out as driver-incompatible for these four, so " +
        "their tools have to come from here. Compose is deliberately absent — see " +
        "McpDriverToolSurfaces",
    )
  }

  /**
   * Compose's absence from the substitution set is the load-bearing half of that policy, so it is
   * asserted on its own: adding it would send the consumers down the substitution branch and drop
   * the rest of a Compose target's declared toolsets.
   */
  @Test
  fun `Compose does not substitute, so its target's trailmap toolsets still apply`() {
    assertTrue(
      builtIn(TrailblazeDriverType.COMPOSE).isEmpty(),
      "empty is the signal the consumers read to keep resolving from the trailmap's tool_sets",
    )
  }

  /**
   * The other policy, and the reason it cannot simply be "every driver that contributes tools":
   * `StepToolSet.selectInnerAgentTools` treats a non-null answer as the *whole* list, discarding
   * the target's customs and the YAML-defined tools. Only Compose can afford that, because its RPC
   * server cannot execute anything else.
   */
  @Test
  fun `only Compose replaces the inner agent's tool list outright`() {
    val replacing = TrailblazeDriverType.entries.filter { override(it) != null }.toSet()

    assertEquals(setOf(TrailblazeDriverType.COMPOSE), replacing, "a non-null answer is a hard replacement")
  }

  /**
   * The two policies are disjoint, which is what makes the pair coherent: every driver that
   * contributes tools is served by exactly one of them, and none by both. A driver in both would
   * have its inner agent's list replaced *and* its trailmap's toolsets substituted.
   */
  @Test
  fun `no driver is served by both policies`() {
    val servedByBoth = TrailblazeDriverType.entries
      .filter { builtIn(it).isNotEmpty() && override(it) != null }

    assertTrue(servedByBoth.isEmpty(), "these drivers hit both MCP tool-surface policies: $servedByBoth")
  }

  /**
   * Both policies read the driver's tools from its descriptor rather than resolving the catalog
   * themselves — including Electron's extras, which the bridge's own `when` used to leave out
   * while the Playwright run path included them.
   */
  @Test
  fun `the tool classes come from the driver's descriptor`() {
    TrailblazeDriverType.entries.forEach { driverType ->
      val surfaced = builtIn(driverType).ifEmpty { override(driverType).orEmpty() }
      if (surfaced.isNotEmpty()) {
        // Looked up inside the guard, not above it: `forDriver` throws for a driver the registry
        // doesn't carry, so hoisting it would fail the first driver added without a
        // `ReferenceHostDriverDescriptors` entry on "No HostDriverDescriptor is registered"
        // instead of on the parity assertion this test exists to make. A non-empty surface
        // already proves a descriptor answered.
        val fromDescriptor = descriptors.forDriver(driverType).toolClasses(driverType)
        assertEquals(fromDescriptor, surfaced, "$driverType should surface exactly what its descriptor names")
      }
    }
    // Named explicitly, because this is the one value the old bridge computed differently from the
    // descriptor: it advertised the web tools alone for Electron, with no way to launch the app.
    assertTrue(
      builtIn(TrailblazeDriverType.PLAYWRIGHT_ELECTRON)
        .containsAll(BasePlaywrightElectronTest.ELECTRON_BUILT_IN_TOOL_CLASSES),
      "an Electron MCP session should be offered the Electron-only tools too",
    )
  }

  /**
   * No device bound yet is the ordinary startup state, and it must not be mistaken for "replace
   * the inner agent's tools with nothing".
   */
  @Test
  fun `an unbound driver leaves both surfaces alone`() {
    assertTrue(builtIn(null).isEmpty(), "no substitution without a driver")
    assertNull(override(null), "null, not empty — an empty override would leave the agent toolless")
  }

  /**
   * A registered descriptor that contributes no tools is not the same as a replacement of nothing.
   *
   * Distinct from the unregistered case above: the descriptor resolves, so the lookup succeeds and
   * returns an empty set. Handing that to `selectInnerAgentTools` as a non-null override would
   * replace the inner agent's whole tool list with nothing and leave it unable to act, which is
   * strictly worse than not overriding at all.
   */
  @Test
  fun `a descriptor that contributes no tools does not become an empty override`() {
    val contributesNothing = HostDriverDescriptorRegistry(
      setOf(FakeHostDriverDescriptor(TrailblazeDriverType.COMPOSE)),
    )

    assertNull(
      McpDriverToolSurfaces.innerAgentOverride(TrailblazeDriverType.COMPOSE, contributesNothing),
      "an empty contribution should leave the inner agent's own tools in place, not blank them",
    )
  }

  /**
   * A distribution is entitled to omit a driver, and an MCP session on one must degrade to the
   * trailmap rather than to an empty tool list.
   */
  @Test
  fun `a driver with no registered descriptor leaves both surfaces alone`() {
    val empty = HostDriverDescriptorRegistry.EMPTY

    assertTrue(
      McpDriverToolSurfaces.builtInToolClasses(TrailblazeDriverType.PLAYWRIGHT_NATIVE, empty).isEmpty(),
      "an unregistered driver should fall back to the trailmap, not substitute nothing",
    )
    assertNull(
      McpDriverToolSurfaces.innerAgentOverride(TrailblazeDriverType.COMPOSE, empty),
      "an unregistered driver should leave the inner agent's own tools in place",
    )
    // Sanity: the same two calls DO answer against the reference set, so the assertions above are
    // about the empty registry rather than about these drivers.
    assertTrue(builtIn(TrailblazeDriverType.PLAYWRIGHT_NATIVE).isNotEmpty())
    assertNotNull(override(TrailblazeDriverType.COMPOSE))
  }

  /**
   * Bounds the one driver the inner-agent override fires for, since the bridge now resolves it
   * through `getDriverType()` — which falls back to the platform's configured driver when the
   * bound device has no registered state.
   *
   * That fallback is `selectedTrailblazeDriverTypes[platform]`, so it can only answer COMPOSE for a
   * DESKTOP-platform device, and COMPOSE is the only DESKTOP driver — the widening is confined to
   * sessions where Compose tools are the right answer anyway. Give DESKTOP a second driver and it
   * stops being: a session on that driver with no device state would have its whole tool list
   * replaced by Compose's, which is a total replacement, not an addition. Asserted here rather than
   * on the bridge because the bridge needs a device manager, a logs repo and an LLM client factory
   * to construct.
   */
  @Test
  fun `the driver the override fires for is the only one its platform can fall back to`() {
    val overriding = TrailblazeDriverType.entries.filter { override(it) != null }
    assertEquals(listOf(TrailblazeDriverType.COMPOSE), overriding)

    assertEquals(
      listOf(TrailblazeDriverType.COMPOSE),
      TrailblazeDriverType.entries.filter { it.platform == TrailblazeDevicePlatform.DESKTOP },
      "a second DESKTOP driver would inherit Compose's inner-agent override via the " +
        "platform fallback in getDriverType()",
    )
  }
}
