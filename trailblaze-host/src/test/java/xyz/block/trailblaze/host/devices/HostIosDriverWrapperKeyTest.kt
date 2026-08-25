package xyz.block.trailblaze.host.devices

import xyz.block.trailblaze.host.FakeHostAppTarget
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * Pins when a cached iOS driver may be handed to a connect for a different target.
 *
 * The driver is cached per device + port, and a target declaring `hasCustomIosDriver` wraps the base
 * `IOSDriver` in its own subclass, so without a target in the cache key the FIRST connect for a
 * device won its wrapper for the whole JVM, and every later connect drove the app through whatever
 * driver that one happened to build.
 *
 * Pure decision, driven with plain targets: no simulator, no XCUITest.
 */
class HostIosDriverWrapperKeyTest {

  @Test
  fun `a target with no custom driver is indistinguishable from no target at all`() {
    // Both produce the identical base IOSDriver, so treating them as different wrappers would throw
    // away a live XCUITest connection (a ~40s rebuild) to build the exact same driver.
    assertNull(HostIosDriverFactory.driverWrapperKey(null))
    assertNull(HostIosDriverFactory.driverWrapperKey(FakeHostAppTarget("plain", hasCustomIosDriver = false)))
    assertNull(HostIosDriverFactory.driverWrapperKey(TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget))
  }

  @Test
  fun `two plain targets share a driver`() {
    assertEquals(
      HostIosDriverFactory.driverWrapperKey(FakeHostAppTarget("alpha", hasCustomIosDriver = false)),
      HostIosDriverFactory.driverWrapperKey(FakeHostAppTarget("beta", hasCustomIosDriver = false)),
    )
  }

  @Test
  fun `a custom-driver target does not share a driver with a plain one`() {
    // Either order is wrong: reusing the plain driver drives the app unwrapped, and reusing the
    // wrapped one applies another app's driver behaviour to this target.
    val custom = HostIosDriverFactory.driverWrapperKey(FakeHostAppTarget("square", hasCustomIosDriver = true))
    val plain = HostIosDriverFactory.driverWrapperKey(FakeHostAppTarget("square", hasCustomIosDriver = false))
    assertEquals("square", custom)
    assertNotEquals(plain, custom)
  }

  @Test
  fun `two different custom-driver targets do not share a driver`() {
    assertNotEquals(
      HostIosDriverFactory.driverWrapperKey(FakeHostAppTarget("alpha", hasCustomIosDriver = true)),
      HostIosDriverFactory.driverWrapperKey(FakeHostAppTarget("beta", hasCustomIosDriver = true)),
    )
  }

  @Test
  fun `the same custom-driver target reuses its driver`() {
    // The common case by far: connect, disconnect, connect again for the same target. Rebuilding
    // here would make every reconnect pay for the XCUITest install.
    assertEquals(
      HostIosDriverFactory.driverWrapperKey(FakeHostAppTarget("square", hasCustomIosDriver = true)),
      HostIosDriverFactory.driverWrapperKey(FakeHostAppTarget("square", hasCustomIosDriver = true)),
    )
  }
}
