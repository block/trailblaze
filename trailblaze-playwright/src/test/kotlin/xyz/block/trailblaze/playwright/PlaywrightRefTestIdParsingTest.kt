package xyz.block.trailblaze.playwright

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for [dataTestIdFromCssRef], the bridge between recorded/LLM refs
 * (which embed the CSS.escape()d testid so the ref is a valid selector) and
 * [xyz.block.trailblaze.api.DriverNodeDetail.Web.dataTestId] (the raw attribute).
 *
 * Selector-enrichment matching goes through this parser; if it returns null or a
 * wrong value, recordings silently lose their most durable selector.
 */
class PlaywrightRefTestIdParsingTest {

  @Test
  fun `plain testid ref parses to its raw value`() {
    assertEquals("submit-button", dataTestIdFromCssRef("""css=[data-testid="submit-button"]"""))
  }

  @Test
  fun `data-test-id attribute variant parses too`() {
    assertEquals("submit-button", dataTestIdFromCssRef("""css=[data-test-id="submit-button"]"""))
  }

  @Test
  fun `character escapes are undone so the value matches the raw attribute`() {
    // CSS.escape("foo.bar:baz") === "foo\.bar\:baz" — the tree stores the raw value.
    assertEquals("foo.bar:baz", dataTestIdFromCssRef("""css=[data-testid="foo\.bar\:baz"]"""))
  }

  @Test
  fun `hex escape for a leading digit is undone including its terminator space`() {
    // CSS.escape("1total") === "\31 total".
    assertEquals("1total", dataTestIdFromCssRef("""css=[data-testid="\31 total"]"""))
  }

  @Test
  fun `an out-of-range hex escape yields the replacement char instead of throwing`() {
    // Six hex digits can exceed the last valid code point. The contract for a ref this
    // parser can't make sense of is a value or null — never an exception thrown at a
    // caller that is only trying to match a tree node.
    assertEquals("�", dataTestIdFromCssRef("""css=[data-testid="\ffffff"]"""))
    assertEquals("�", dataTestIdFromCssRef("""css=[data-testid="\0"]"""))
  }

  @Test
  fun `non-testid refs return null instead of a mangled string`() {
    assertNull(dataTestIdFromCssRef("css=#my-id"))
    assertNull(dataTestIdFromCssRef("""css=[aria-label="Submit"]"""))
    assertNull(dataTestIdFromCssRef("e5"))
    assertNull(dataTestIdFromCssRef("""button "Submit""""))
  }
}
