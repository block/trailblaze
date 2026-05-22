package xyz.block.trailblaze.playwright

import com.microsoft.playwright.Playwright
import org.junit.After
import org.junit.Test
import xyz.block.trailblaze.devices.WebViewportSpec
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration-style coverage for [PlaywrightDeviceRegistry].
 *
 * Uses a real [Playwright] instance — same pattern as [PlaywrightBrowserManager] —
 * because the registry reaches through the public interface into Playwright's impl
 * to read `deviceDescriptors()`. A unit-test mock would re-encode the very
 * assumptions we want to pin: if a future Playwright Java bump reshapes the JSON
 * array of descriptors, the cast inside [PlaywrightDeviceRegistry] will throw and
 * these tests will fail loudly — exactly what we want from this layer.
 */
class PlaywrightDeviceRegistryTest {

  private val playwright: Playwright = Playwright.create()

  @After
  fun tearDown() {
    playwright.close()
  }

  @Test
  fun `null spec returns the supplied defaults`() {
    val resolved = PlaywrightDeviceRegistry.resolve(
      playwright = playwright,
      spec = null,
      defaultWidth = 1280,
      defaultHeight = 800,
    )
    assertEquals(1280, resolved.width)
    assertEquals(800, resolved.height)
    assertNull(resolved.presetName, "default viewport has no preset name attribution")
    assertNull(resolved.userAgent, "default viewport leaves UA at Playwright defaults")
    assertNull(resolved.deviceScaleFactor, "default viewport leaves DPR at Playwright defaults")
    assertNull(resolved.isMobile)
    assertNull(resolved.hasTouch)
  }

  @Test
  fun `raw dimensions pass through without consulting the registry`() {
    val resolved = PlaywrightDeviceRegistry.resolve(
      playwright = playwright,
      spec = WebViewportSpec.Dimensions(width = 375, height = 812),
      defaultWidth = 1280,
      defaultHeight = 800,
    )
    assertEquals(375, resolved.width)
    assertEquals(812, resolved.height)
    assertNull(resolved.presetName, "raw dimensions are not preset-attributed")
    assertNull(resolved.userAgent, "raw dimensions do NOT borrow Playwright preset UA")
    assertNull(resolved.deviceScaleFactor)
    assertNull(resolved.isMobile)
    assertNull(resolved.hasTouch)
  }

  @Test
  fun `known iPhone preset resolves with mobile emulation flags`() {
    val resolved = PlaywrightDeviceRegistry.resolve(
      playwright = playwright,
      spec = WebViewportSpec.Preset("iPhone 14"),
      defaultWidth = 1280,
      defaultHeight = 800,
    )
    // We pin the preset NAME but not its specific viewport — those are
    // Playwright-bundled values that can legitimately shift between
    // dependency versions and aren't a Trailblaze contract.
    assertEquals("iPhone 14", resolved.presetName)
    assertTrue(resolved.width > 0, "iPhone 14 must have a positive width (got ${resolved.width})")
    assertTrue(resolved.height > 0, "iPhone 14 must have a positive height (got ${resolved.height})")
    assertNotNull(resolved.userAgent, "iPhone 14 must surface a mobile User-Agent")
    assertNotNull(resolved.deviceScaleFactor, "iPhone 14 must surface a DPR")
    assertEquals(true, resolved.isMobile, "iPhone 14 must report isMobile=true")
    assertEquals(true, resolved.hasTouch, "iPhone 14 must report hasTouch=true")
  }

  @Test
  fun `unknown preset throws IllegalArgumentException with actionable message`() {
    val error = assertFailsWith<IllegalArgumentException> {
      PlaywrightDeviceRegistry.resolve(
        playwright = playwright,
        spec = WebViewportSpec.Preset("iPhne 14"),
        defaultWidth = 1280,
        defaultHeight = 800,
      )
    }
    val message = error.message.orEmpty()
    assertTrue(message.contains("iPhne 14"), "error must include the typo: '$message'")
    assertTrue(
      message.contains("iPhone 14") || message.contains("Pixel 7") || message.contains("iPad"),
      "error should suggest known alternatives so the user can recover: '$message'",
    )
  }
}
