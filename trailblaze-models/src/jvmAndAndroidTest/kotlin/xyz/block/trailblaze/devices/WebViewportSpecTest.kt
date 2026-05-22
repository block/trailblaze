package xyz.block.trailblaze.devices

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Unit coverage for the user-facing viewport string parser.
 *
 * The parser is responsible for distinguishing the two surface forms — raw
 * `WIDTHxHEIGHT` dimensions and a Playwright preset name like `"iPhone 14"` —
 * without needing a Playwright runtime to validate preset names. The "preset
 * name" branch is permissive on purpose: bad names surface at browser launch
 * with a more useful error than parser code could produce here.
 */
class WebViewportSpecTest {

  @Test
  fun `null and blank strings parse to null`() {
    assertNull(WebViewportSpec.parse(null))
    assertNull(WebViewportSpec.parse(""))
    assertNull(WebViewportSpec.parse("   "))
  }

  @Test
  fun `raw WxH parses to Dimensions`() {
    assertEquals(
      WebViewportSpec.Dimensions(width = 375, height = 812),
      WebViewportSpec.parse("375x812"),
    )
    assertEquals(
      WebViewportSpec.Dimensions(width = 1280, height = 800),
      WebViewportSpec.parse("1280x800"),
    )
  }

  @Test
  fun `WxH trims whitespace and accepts upper-case X separator`() {
    assertEquals(
      WebViewportSpec.Dimensions(width = 414, height = 896),
      WebViewportSpec.parse("  414X896  "),
    )
  }

  @Test
  fun `preset name passes through verbatim`() {
    assertEquals(
      WebViewportSpec.Preset("iPhone 14"),
      WebViewportSpec.parse("iPhone 14"),
    )
    assertEquals(
      WebViewportSpec.Preset("Pixel 7"),
      WebViewportSpec.parse("Pixel 7"),
    )
    assertEquals(
      WebViewportSpec.Preset("iPad Pro 11"),
      WebViewportSpec.parse("  iPad Pro 11  "),
    )
  }

  @Test
  fun `dimension-shaped input with zero or missing values is rejected`() {
    assertFailsWith<IllegalArgumentException> { WebViewportSpec.parse("0x800") }
    assertFailsWith<IllegalArgumentException> { WebViewportSpec.parse("375x0") }
    assertFailsWith<IllegalArgumentException> { WebViewportSpec.parse("375x") }
    assertFailsWith<IllegalArgumentException> { WebViewportSpec.parse("x812") }
    assertFailsWith<IllegalArgumentException> { WebViewportSpec.parse("x") }
  }
}
