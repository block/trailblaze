package xyz.block.trailblaze.trailrunner

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType

/**
 * `/api/run-tools` answers "which toolsets does this driver have?". Answering with a DIFFERENT
 * driver's catalog under `resolved = true` is worse than not answering: nothing in the response
 * records the substitution, so the caller reads the wrong catalog as authoritative.
 */
class RunToolsDriverResolutionTest {

  @Test
  fun `a named driver that does not match is refused rather than swapped for its platform`() {
    // The regression: the exact-match arm skips a retired driver, the platform arm then answers
    // with whatever else runs on ANDROID, and the caller sees `resolved = true`.
    TrailblazeDriverType.RETIRED_DRIVERS.forEach { retired ->
      assertNull(
        resolveDriverType(retired.name, retired.platform.name),
        "$retired was named explicitly and must not resolve to another driver on its platform",
      )
    }
  }

  @Test
  fun `a misspelled driver is refused too, for the same reason`() {
    // Same failure shape, and the one a caller is far more likely to hit. The platform arm exists
    // for a caller who named NO driver, not as a spelling correction.
    assertNull(resolveDriverType("ANDROID_ONDEVICE_ACCESSIBILTY", "ANDROID"))
  }

  @Test
  fun `naming no driver still falls back to the platform`() {
    // The fallback the route was built for must survive the stricter named-driver arm.
    val resolved = resolveDriverType("", TrailblazeDevicePlatform.ANDROID.name)
    assertEquals(TrailblazeDevicePlatform.ANDROID, resolved?.platform)
    assertNull(
      resolved?.takeIf { it in TrailblazeDriverType.RETIRED_DRIVERS },
      "the platform fallback must never land on a retired driver",
    )
  }

  @Test
  fun `a runnable driver named exactly still resolves, case-insensitively`() {
    assertEquals(
      TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
      resolveDriverType("android_ondevice_accessibility", ""),
    )
  }

  @Test
  fun `naming neither resolves to nothing`() {
    assertNull(resolveDriverType("", ""))
  }
}
