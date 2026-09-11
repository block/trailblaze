package xyz.block.trailblaze.host.driver

import org.junit.Test
import xyz.block.trailblaze.devices.TrailblazeDriverType
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The obligation that replaced `validateCovers`.
 *
 * That check asked whether a registry covered the drivers its app said it supported — but an app's
 * supported set is derived FROM its descriptors, so the two could never disagree and it could
 * never fail. The question worth asking is the one it looked like it was asking: does a real
 * distribution actually offer every driver the enum advertises?
 */
class ReferenceHostDriverDescriptorsTest {

  /**
   * A driver in the enum that the reference distribution doesn't register is advertised and
   * unrunnable — selectable in no listing, resolvable by no `forDriver`, and silent about it.
   *
   * This is the one check that has to name a distribution rather than reason from the enum alone.
   * A downstream app is entitled to omit a driver it has no use for, so "someone
   * registers it" is not an invariant — "the reference distribution registers all of them" is.
   */
  @Test
  fun `the reference distribution registers every driver`() {
    val registry = HostDriverDescriptorRegistry(ReferenceHostDriverDescriptors.all())

    val unregistered = TrailblazeDriverType.entries.filter { registry.forDriverOrNull(it) == null }

    assertTrue(
      unregistered.isEmpty(),
      "these drivers are in TrailblazeDriverType but plugged into nothing, so no device of theirs " +
        "is ever discovered and a run of one dies resolving a descriptor: " +
        "${unregistered.map { it.name }}. Write a HostDriverDescriptor for each and add it to " +
        "ReferenceHostDriverDescriptors.",
    )
  }

  /**
   * Construction is where the registry applies its own invariants — one claimant per driver, and
   * no [HostDriverDescriptor.OnDeviceTools] on a driver dispatch routes to the host run path. The
   * test above would pass on a set that violated either, because `forDriverOrNull` is only reached
   * if the constructor already accepted it; this says plainly that it does.
   */
  @Test
  fun `the reference set satisfies the registry's own invariants`() {
    HostDriverDescriptorRegistry(ReferenceHostDriverDescriptors.all())
  }

  /**
   * Descriptors are not guaranteed stateless — Playwright's hold live browser sessions, Revyl's a
   * CLI client. Handing two app configs the same instances would make one app's live sessions
   * reachable from the other, so each call has to build its own.
   */
  @Test
  fun `each call yields fresh descriptor instances`() {
    val first = ReferenceHostDriverDescriptors.all()
    val second = ReferenceHostDriverDescriptors.all()

    assertEquals(
      first.flatMap { it.driverTypes }.toSet(),
      second.flatMap { it.driverTypes }.toSet(),
      "the two calls should describe the same drivers",
    )
    assertTrue(
      first.none { descriptor -> second.any { it === descriptor } },
      "no descriptor instance may be shared between calls",
    )
  }
}
