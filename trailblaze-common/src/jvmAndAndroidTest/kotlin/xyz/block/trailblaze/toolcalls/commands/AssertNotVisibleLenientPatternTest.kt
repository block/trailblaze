package xyz.block.trailblaze.toolcalls.commands

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import xyz.block.trailblaze.toolcalls.commands.AssertNotVisibleWithTextTrailblazeTool.Companion.toLenientPattern

/**
 * Behavioral contract of [AssertNotVisibleWithTextTrailblazeTool.toLenientPattern]: the tool's
 * `text` arg matches case-insensitively, honoring both its regex reading and its literal
 * reading. Asserted through actual regex matching (full-string, like the resolvers), not
 * through the transformed pattern's string shape.
 */
class AssertNotVisibleLenientPatternTest {

  private fun matches(text: String, screenText: String): Boolean =
    Regex(toLenientPattern(text)).matches(screenText)

  @Test
  fun `plain word matches any casing`() {
    assertTrue(matches("Allow", "Allow"))
    assertTrue(matches("Allow", "ALLOW"))
    assertTrue(matches("Save", "SAVE"))
    assertFalse(matches("Allow", "Don't Allow")) // still full-string
  }

  @Test
  fun `screen text with regex metacharacters matches via literal reading`() {
    assertTrue(matches("\$5.00", "\$5.00"))
    assertTrue(matches("Dollar Off (\$1.00)", "Dollar Off (\$1.00)"))
    assertFalse(matches("\$5.00", "\$4.00"))
  }

  @Test
  fun `deliberate regex keeps its regex reading`() {
    assertTrue(matches(".*debit 1582.*", "Visa debit 1582 ending"))
    assertTrue(matches("150.*", "150 points earned")) // interpolated {{points}}.* shape
    assertTrue(matches("^Ho\$", "Ho"))
    assertFalse(matches("^Ho\$", "Hot"))
  }

  @Test
  fun `regex reading is also case-insensitive`() {
    assertTrue(matches(".*offline.*", "You're OFFLINE"))
  }

  @Test
  fun `leading (?-i) restores case-sensitivity`() {
    assertTrue(matches("(?-i)Allow", "Allow"))
    assertFalse(matches("(?-i)Allow", "ALLOW"))
  }

  @Test
  fun `invalid regex matches its literal text, any casing`() {
    assertTrue(matches("[unclosed", "[unclosed"))
    assertTrue(matches("[unclosed", "[UNCLOSED"))
    assertTrue(matches("+47 Points", "+47 points"))
    assertFalse(matches("[unclosed", "unclosed"))
  }

  @Test
  fun `a line break in the text matches only a line break`() {
    assertTrue(matches("Name: Jane Doe\nEmail:", "Name: Jane Doe\nEmail:"))
    assertFalse(matches("Name: Jane Doe Email:", "Name: Jane Doe\nEmail:"))
  }

  /** The snapshot trims what it prints, so a quote of it never carries the element's edge whitespace. */
  @Test
  fun `whitespace at either end of the screen text is ignored`() {
    assertTrue(matches("Name: Jane Doe\nEmail:", "Name: Jane Doe\nEmail: "))
    assertTrue(matches("Total", "\u00A0Total\n"))
    assertTrue(matches("\$5.00", " \$5.00 "))
    assertFalse(matches("Total", "Total due"))
  }

  @Test
  fun `an anchored regex still ignores whitespace at either end`() {
    assertTrue(matches("^Ho$", " Ho "))
    assertTrue(matches("(?-i)^Ho$", "Ho\n"))
    assertFalse(matches("(?-i)^Ho$", "HO"))
    assertFalse(matches("^Ho$", "Hoo"))
  }

  @Test
  fun `an escaped trailing dollar stays literal`() {
    assertTrue(matches("Total 5\\$", "Total 5$ "))
    assertFalse(matches("Total 5\\$", "Total 5"))
  }
}
