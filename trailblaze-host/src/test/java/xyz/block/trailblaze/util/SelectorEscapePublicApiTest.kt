package xyz.block.trailblaze.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Cross-module integration test: verifies that `escapeForSelector`, `escapeForIdentifier`,
 * `REGEX_METACHARACTERS`, and `IDENTIFIER_REGEX_METACHARACTERS` are reachable as part of
 * the public API of `trailblaze-models` from this consumer module (`trailblaze-host`),
 * and that their behavior is intact.
 *
 * Acts as a compile-time + runtime lock: if a future refactor changes the package, the
 * function signature, or the visibility, this test fails at `:trailblaze-host:test` rather
 * than at downstream consumer build time.
 *
 * Mirror in `trailblaze-playwright` covers `escapeForSelector` for ARIA names.
 */
class SelectorEscapePublicApiTest {

  @Test
  fun `escapeForIdentifier emits class names bare`() {
    // Used by WaypointSuggestSelectorCommand for classNameRegex output.
    assertEquals("android.widget.TextView", escapeForIdentifier("android.widget.TextView"))
  }

  @Test
  fun `escapeForIdentifier still wraps non-dot metacharacters`() {
    val result = escapeForIdentifier("com.example:id/price_\$field")
    assertFalse(result == "com.example:id/price_\$field", "dollar sign must trigger escaping")
    assertTrue(Regex(result).matches("com.example:id/price_\$field"))
  }

  @Test
  fun `escapeForSelector escapes dots in content`() {
    // Used in other code paths for textRegex / contentDescriptionRegex.
    val result = escapeForSelector("v1.2")
    assertFalse(result == "v1.2", "content dot must trigger escaping")
    assertTrue(Regex(result).matches("v1.2"))
    assertFalse(Regex(result).matches("v1x2"))
  }

  @Test
  fun `IDENTIFIER_REGEX_METACHARACTERS excludes dot, REGEX_METACHARACTERS includes it`() {
    assertTrue('.' in REGEX_METACHARACTERS)
    assertFalse('.' in IDENTIFIER_REGEX_METACHARACTERS)
  }
}
