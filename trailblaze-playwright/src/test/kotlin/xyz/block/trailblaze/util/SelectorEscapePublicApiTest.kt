package xyz.block.trailblaze.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Cross-module integration test: verifies that `escapeForSelector` and the metacharacter
 * sets are reachable as part of the public API of `trailblaze-models` from this consumer
 * module (`trailblaze-playwright`), and that their behavior is intact.
 *
 * `escapeForSelector` is used here for `ariaNameRegex` in `PlaywrightInteractionToolFactory`.
 * Mirror in `trailblaze-host` covers `escapeForIdentifier` for classNameRegex.
 */
class SelectorEscapePublicApiTest {

  @Test
  fun `escapeForSelector wraps ARIA names containing a dot`() {
    // Real-world ARIA names like "Dr. Smith" or "v1.2" must escape '.' so they
    // match literally — content fields treat '.' as a metacharacter.
    val result = escapeForSelector("Dr. Smith")
    assertFalse(result == "Dr. Smith", "ARIA name with dot must trigger escaping")
    assertTrue(Regex(result).matches("Dr. Smith"))
    assertFalse(Regex(result).matches("Dr_ Smith"), "dot must not become wildcard for content")
  }

  @Test
  fun `escapeForSelector wraps parentheses`() {
    val result = escapeForSelector("Name (required)")
    assertFalse(result == "Name (required)")
    assertTrue(Regex(result).matches("Name (required)"))
  }

  @Test
  fun `escapeForSelector returns plain labels as-is`() {
    assertEquals("Submit", escapeForSelector("Submit"))
    assertEquals("Close dialog", escapeForSelector("Close dialog"))
  }

  @Test
  fun `REGEX_METACHARACTERS includes dot for content fields`() {
    assertTrue('.' in REGEX_METACHARACTERS)
  }
}
