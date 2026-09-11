package xyz.block.trailblaze.host.driver

import org.junit.Test
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.rules.BasePlaywrightElectronTest
import xyz.block.trailblaze.logs.client.TrailblazeSerializationInitializer
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What each driver adds to the tool vocabulary, asked of the descriptors rather than of a
 * `when (driverType)` in whichever file happened to need the answer.
 *
 * The consumer is recording generation: a tool that appears in a session log but not in the set
 * handed to the serializer deserializes as unrecognized, so the recorded trail silently loses the
 * step. That failure is invisible until someone replays the recording, which is why these are
 * pinned here rather than left to the one caller.
 */
class HostDriverDescriptorToolClassesTest {

  private val registry = HostDriverDescriptorRegistry(ReferenceHostDriverDescriptors.all())

  private fun toolClassesFor(driverType: TrailblazeDriverType) =
    registry.forDriver(driverType).toolClasses(driverType)

  /**
   * The whole seam in one assertion: a driver contributes tools exactly when it reaches its device
   * some way other than the platform's own UI automation.
   *
   * Both directions matter. A driver that should contribute and doesn't loses steps from its
   * recordings; one that shouldn't and does would teach the serializer tools that cannot appear in
   * its logs. Stated as the exact set so adding a driver forces a deliberate answer here.
   */
  @Test
  fun `only the drivers that reach their device off-platform contribute tools`() {
    val contributing = TrailblazeDriverType.entries
      .filter { toolClassesFor(it).isNotEmpty() }
      .toSet()

    assertEquals(
      setOf(
        TrailblazeDriverType.COMPOSE,
        TrailblazeDriverType.PLAYWRIGHT_NATIVE,
        TrailblazeDriverType.PLAYWRIGHT_ELECTRON,
        TrailblazeDriverType.REVYL_ANDROID,
        TrailblazeDriverType.REVYL_IOS,
      ),
      contributing,
      "Compose speaks over its RPC server, Playwright over CDP, and Revyl over its cloud API, so " +
        "each ships tools of its own. Every other driver acts through the built-in tools and adds " +
        "nothing. A driver moving in or out of this set is a real change to what its recordings " +
        "can express.",
    )
  }

  /**
   * The reason [HostDriverDescriptor.toolClasses] takes a driver type at all: one descriptor, two
   * entries, two answers. Asserting the difference is exactly the Electron extras — and that those
   * extras are neither empty nor already in the web set — is what makes the `+` load-bearing.
   */
  @Test
  fun `electron contributes the web tools plus the ones for driving a shell`() {
    val web = toolClassesFor(TrailblazeDriverType.PLAYWRIGHT_NATIVE)
    val electron = toolClassesFor(TrailblazeDriverType.PLAYWRIGHT_ELECTRON)
    val electronOnly = BasePlaywrightElectronTest.ELECTRON_BUILT_IN_TOOL_CLASSES

    assertTrue(electronOnly.isNotEmpty(), "the Electron extras should be a real set of tools")
    assertTrue(
      electronOnly.none { it in web },
      "the Electron extras should be tools a plain browser tab has no equivalent of, so none of " +
        "them should already be in the web set: ${electronOnly.filter { it in web }}",
    )
    assertEquals(web + electronOnly, electron, "Electron should add to the web set, not replace it")
  }

  /**
   * Every contributing driver must bring tools *of its own*, not just the memory and assertion
   * tools every driver shares.
   *
   * This is the assertion that catches a descriptor resolving another driver's catalog ids, and it
   * has to be phrased this way because that mistake does not produce an empty set.
   * `resolveForDriver` filters the catalog by driver compatibility before it applies the requested
   * ids, so asking for the web tool sets on behalf of Compose quietly yields the shared tools
   * alone — non-empty, plausible, and missing every `ComposeClickTool` and `ComposeScrollTool` a
   * Compose recording is made of.
   */
  @Test
  fun `every contributing driver brings tools no other driver brings`() {
    val contributions = TrailblazeDriverType.entries
      .associateWith { toolClassesFor(it) }
      .filterValues { it.isNotEmpty() }

    // Guards the reduce below, which throws UnsupportedOperationException on an empty collection.
    // That would be the shape of a total regression — no driver contributing anything — and the
    // generic exception says nothing about what broke.
    assertTrue(
      contributions.isNotEmpty(),
      "no driver contributes any tools at all, so every Playwright, Compose and Revyl recording " +
        "is losing its steps. Start with the descriptor overrides, not this test.",
    )

    // Tools every contributing driver has are the driver-agnostic ones — memory, assertions.
    // What is left is what makes the driver's vocabulary its own.
    val shared = contributions.values.reduce { acc, classes -> acc intersect classes }

    val withNothingOfItsOwn = contributions
      .filterValues { (it - shared).isEmpty() }
      .keys

    assertTrue(
      withNothingOfItsOwn.isEmpty(),
      "these drivers contribute only the tools every driver already has, which means their " +
        "descriptor resolved someone else's catalog ids: $withNothingOfItsOwn. Recordings on them " +
        "keep the assertion steps and silently drop every step that actually drove the device.",
    )
  }

  /**
   * Whether the recording callers actually need what they are handed.
   *
   * `createTrailblazeYaml` unions a caller's tool classes with the serializers it discovers from
   * the `.tool.yaml` resources on the classpath, and today that discovery already covers every
   * class a descriptor names — measured, not assumed. So passing these to the serializer is belt
   * and braces, and the honest way to hold that claim is to assert it rather than to write KDoc
   * about a silent step drop that cannot currently happen.
   *
   * If this ever fails, the failure is the useful kind: some driver ships a class-backed tool with
   * no `.tool.yaml`, and the sets handed to the serializer became load-bearing.
   */
  @Test
  fun `every driver's tool classes are already in the bundled serialization registry`() {
    val bundled = TrailblazeSerializationInitializer.buildAllTools().values.toSet()

    val notBundled = TrailblazeDriverType.entries.associateWith { toolClassesFor(it) - bundled }
      .filterValues { it.isNotEmpty() }

    assertTrue(
      notBundled.isEmpty(),
      "these driver tool classes are not discoverable from the classpath's `.tool.yaml` resources, " +
        "so the sets the recording paths pass to createTrailblazeYaml are now load-bearing rather " +
        "than belt and braces: " +
        notBundled.entries.joinToString(", ") { (driver, classes) ->
          "$driver -> ${classes.mapNotNull { it.simpleName }.sorted()}"
        } +
        ". Either give each tool its `.tool.yaml`, or update the KDoc that calls these sets " +
        "redundant.",
    )
  }

  /**
   * The other multi-entry descriptor answers uniformly, unlike Playwright's. Revyl is one backend
   * with one tool vocabulary; the device's platform is a property of the device, not of the API
   * used to drive it.
   */
  @Test
  fun `both Revyl platforms contribute the same tools`() {
    assertEquals(
      toolClassesFor(TrailblazeDriverType.REVYL_ANDROID),
      toolClassesFor(TrailblazeDriverType.REVYL_IOS),
    )
  }
}
