package xyz.block.trailblaze.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.block.trailblaze.devices.TrailblazeDriverType

/**
 * Pins the shared driver-string validation to its fail-loud contract: no driver requested
 * resolves to "run on the default", a known driver name resolves to its type, and an
 * unrecognized name is rejected with an error naming the bad value and every valid driver —
 * never a silent fallback to the default driver.
 */
class CliRunDriverResolverTest {

  @Test
  fun `no requested driver resolves to null driver type`() {
    val resolution = CliRunDriverResolver.resolve(null)
    assertIs<CliRunDriverResolution.Resolved>(resolution)
    assertNull(resolution.driverType)
  }

  @Test
  fun `every driver enum name resolves to its driver type`() {
    for (driver in TrailblazeDriverType.entries) {
      val resolution = CliRunDriverResolver.resolve(driver.name)
      assertIs<CliRunDriverResolution.Resolved>(resolution)
      assertEquals(driver, resolution.driverType)
    }
  }

  @Test
  fun `driver name matching is case-insensitive`() {
    val resolution = CliRunDriverResolver.resolve("ios_axe")
    assertIs<CliRunDriverResolution.Resolved>(resolution)
    assertEquals(TrailblazeDriverType.IOS_AXE, resolution.driverType)
  }

  @Test
  fun `unrecognized driver name is rejected, naming the bad value and the valid drivers`() {
    val resolution = CliRunDriverResolver.resolve("axe")
    assertIs<CliRunDriverResolution.Unrecognized>(resolution)
    assertTrue(resolution.message.contains("'axe'"), "message should name the bad value: ${resolution.message}")
    for (driver in TrailblazeDriverType.entries) {
      assertTrue(
        resolution.message.contains(driver.name),
        "message should list valid driver ${driver.name}: ${resolution.message}",
      )
    }
  }
}
