package xyz.block.trailblaze.playwright.recording

import org.junit.Test
import kotlin.test.assertEquals

/**
 * Unit tests for [PlaywrightDeviceScreenStream]'s pure helpers.
 *
 * The streaming + DOM-resolution paths require a live Playwright fixture and are exercised
 * elsewhere; this file is for the standalone helpers that produce CSS selectors. The
 * `cssEscape` rule is small (escape `\` and `"`, leave everything else alone) but
 * load-bearing — a regression here would silently corrupt every recorded `web_click` ref
 * when an id or test id contained one of those characters.
 */
class PlaywrightDeviceScreenStreamTest {

  @Test
  fun `cssEscape passes through plain values`() {
    assertEquals("submit-button", PlaywrightDeviceScreenStream.cssEscape("submit-button"))
    assertEquals("foo:bar", PlaywrightDeviceScreenStream.cssEscape("foo:bar"))
    assertEquals("123", PlaywrightDeviceScreenStream.cssEscape("123"))
    assertEquals("", PlaywrightDeviceScreenStream.cssEscape(""))
  }

  @Test
  fun `cssEscape escapes backslash`() {
    assertEquals("foo\\\\bar", PlaywrightDeviceScreenStream.cssEscape("foo\\bar"))
  }

  @Test
  fun `cssEscape escapes double quote`() {
    assertEquals("foo\\\"bar", PlaywrightDeviceScreenStream.cssEscape("foo\"bar"))
  }

  @Test
  fun `cssEscape escapes both backslash and double quote in same value`() {
    // Backslash must be escaped first or we'd double-escape the escapes for quotes.
    assertEquals("a\\\\b\\\"c", PlaywrightDeviceScreenStream.cssEscape("a\\b\"c"))
  }

  @Test
  fun `cssEscape produces a value safe to drop into an attribute selector`() {
    // The full surrounding form is `[id="<escaped>"]`. After escape, the resulting selector
    // string should still parse as a single attribute clause — no premature `"` terminating
    // the value.
    val raw = "weird\"id"
    val escaped = PlaywrightDeviceScreenStream.cssEscape(raw)
    val selector = "[id=\"$escaped\"]"
    // Only count the un-escaped `"` characters — the opening and closing of the attribute
    // string. Everything inside should be escaped.
    val openCloseQuotes = Regex("(?<!\\\\)\"").findAll(selector).count()
    assertEquals(2, openCloseQuotes, "selector $selector should have exactly two un-escaped quotes")
  }
}
