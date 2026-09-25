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
    val resolution = CliRunDriverResolver.resolve(driverString = null)
    assertIs<CliRunDriverResolution.Resolved>(resolution)
    assertNull(resolution.driverType)
  }

  @Test
  fun `every runnable driver enum name resolves to its driver type`() {
    for (driver in TrailblazeDriverType.entries - TrailblazeDriverType.RETIRED_DRIVERS) {
      val resolution = CliRunDriverResolver.resolve(driver.name)
      assertIs<CliRunDriverResolution.Resolved>(resolution)
      assertEquals(driver, resolution.driverType)
    }
  }

  /**
   * A retired driver's enum value is kept so old recordings and session logs still deserialize, so
   * it PARSES here and would otherwise resolve to a runtime that no longer exists. Rejecting it at
   * this seam is what makes a stale `--driver` flag, a stale `/cli/run` request and a stale trail
   * pin all fail with the same sentence naming the replacement, instead of each dying further
   * downstream on a missing descriptor or a missing on-device agent.
   */
  @Test
  fun `a retired driver is rejected, naming the replacement`() {
    for (driver in TrailblazeDriverType.RETIRED_DRIVERS) {
      val resolution = CliRunDriverResolver.resolve(driver.name)
      assertIs<CliRunDriverResolution.Unrecognized>(resolution)
      assertTrue(
        resolution.message.contains(driver.name),
        "message should name the retired driver: ${resolution.message}",
      )
      assertTrue(
        resolution.message.contains("retired"),
        "message should say the driver is retired, not merely unknown: ${resolution.message}",
      )
      assertTrue(
        resolution.message.contains(TrailblazeDriverType.DEFAULT_ANDROID.name),
        "message should name what to use instead: ${resolution.message}",
      )
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
    for (driver in TrailblazeDriverType.entries - TrailblazeDriverType.RETIRED_DRIVERS) {
      assertTrue(
        resolution.message.contains(driver.name),
        "message should list valid driver ${driver.name}: ${resolution.message}",
      )
    }
    // The hint is the list a user retypes from. Offering a retired driver there sends them
    // straight back into the rejection above.
    for (retired in TrailblazeDriverType.RETIRED_DRIVERS) {
      assertTrue(
        !resolution.message.contains(retired.name),
        "hint must not advertise the retired driver ${retired.name}: ${resolution.message}",
      )
    }
  }

  /**
   * The already-parsed overload is the one a decoded trail pin and a persisted app setting reach,
   * neither of which ever was a driver string — it must reject a retired driver exactly as the
   * string form does, or those two paths become the way around the check.
   */
  @Test
  fun `the driver-type overload rejects a retired driver and passes a runnable one`() {
    for (driver in TrailblazeDriverType.RETIRED_DRIVERS) {
      val resolution = CliRunDriverResolver.resolve(driver)
      assertIs<CliRunDriverResolution.Unrecognized>(resolution)
      assertTrue(
        resolution.message.contains("retired"),
        "message should say the driver is retired: ${resolution.message}",
      )
    }
    val runnable = CliRunDriverResolver.resolve(TrailblazeDriverType.DEFAULT_ANDROID)
    assertIs<CliRunDriverResolution.Resolved>(runnable)
    assertEquals(TrailblazeDriverType.DEFAULT_ANDROID, runnable.driverType)

    val absent = CliRunDriverResolver.resolve(driverType = null)
    assertIs<CliRunDriverResolution.Resolved>(absent)
    assertNull(absent.driverType)
  }
}
