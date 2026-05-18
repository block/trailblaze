package xyz.block.trailblaze.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [escapeForSelector], [escapeForIdentifier], [REGEX_METACHARACTERS], and
 * [IDENTIFIER_REGEX_METACHARACTERS].
 *
 * Two escaping functions are provided:
 *  - [escapeForSelector] — for content fields (textRegex, contentDescriptionRegex, etc.).
 *    Includes '.' in metacharacters so dots in text like "v1.2" emit `\Q...\E` and match
 *    exactly.
 *  - [escapeForIdentifier] — for identifier fields (classNameRegex, resourceIdRegex, etc.).
 *    Excludes '.' so strings like `com.example:id/foo` are emitted without `\Q...\E` quoting.
 *    Dots match any char (wildcard), which is an accepted tradeoff: no real element has a
 *    class name or resource ID differing from another only at a dot position.
 *
 * The resolver applies patterns via `Regex(pattern).matches(text)` — full-string matching.
 * Every test that verifies a safe (unescaped) string also calls
 * `Regex(result).matches(original)` to catch regressions where an "as-is" string becomes
 * an invalid or wrong-matching pattern.
 *
 * On Kotlin/JVM, `Regex.escape()` wraps in `\Q...\E`. On Kotlin/JS/Native it escapes each
 * metachar individually. Format assertions (`assertEquals("\\Q...\\E", result)`) are only
 * valid on JVM and are labelled accordingly.
 */
class SelectorEscapeTest {

  // ---------------------------------------------------------------------------
  // Contract: both metacharacter sets are locked in here
  // ---------------------------------------------------------------------------

  @Test
  fun `REGEX_METACHARACTERS contains exactly the expected set including dot`() {
    val expected = setOf(
      '\\', '^', '$', '.', '|', '?', '*', '+', '(', ')', '[', ']', '{', '}',
    )
    assertEquals(
      expected,
      REGEX_METACHARACTERS,
      "REGEX_METACHARACTERS drifted — review and update this test intentionally.",
    )
  }

  @Test
  fun `IDENTIFIER_REGEX_METACHARACTERS is REGEX_METACHARACTERS minus dot`() {
    assertEquals(
      REGEX_METACHARACTERS - '.',
      IDENTIFIER_REGEX_METACHARACTERS,
      "IDENTIFIER_REGEX_METACHARACTERS must equal REGEX_METACHARACTERS minus '.'",
    )
    assertFalse('.' in IDENTIFIER_REGEX_METACHARACTERS, "dot must not be in identifier set")
  }

  // ---------------------------------------------------------------------------
  // escapeForSelector — content fields (text, contentDescription, ARIA names, etc.)
  // ---------------------------------------------------------------------------

  @Test
  fun `escapeForSelector - plain labels with no metacharacters are returned as-is`() {
    for (label in listOf("Orders", "Review sale", "Thanks", "Cancel", "Active", "Delivery")) {
      val result = escapeForSelector(label)
      assertEquals(label, result, "Expected as-is for '$label'")
      assertTrue(Regex(result).matches(label), "Pattern must match original: '$label'")
    }
  }

  @Test
  fun `escapeForSelector - text containing a dot is escaped (dot is a content metachar)`() {
    // "v1.2" should not match "v1x2" — content fields require exact dot matching.
    val result = escapeForSelector("v1.2")
    assertFalse(result == "v1.2", "dot in content text should trigger escaping")
    assertTrue(Regex(result).matches("v1.2"), "Pattern must match 'v1.2'")
    assertFalse(Regex(result).matches("v1x2"), "dot must be literal in content fields")
  }

  @Test
  fun `escapeForSelector - ARIA name with dot abbreviation is escaped`() {
    val result = escapeForSelector("Dr. Smith")
    assertFalse(result == "Dr. Smith", "dot in ARIA name should trigger escaping")
    assertTrue(Regex(result).matches("Dr. Smith"))
    assertFalse(Regex(result).matches("Dr_ Smith"), "dot must be literal")
  }

  @Test
  fun `escapeForSelector - each metacharacter triggers escaping`() {
    for (ch in REGEX_METACHARACTERS) {
      val input = "foo${ch}bar"
      val result = escapeForSelector(input)
      assertFalse(
        result == input,
        "escapeForSelector with '$ch' should have escaped but returned as-is",
      )
      assertTrue(
        Regex(result).matches(input),
        "Escaped pattern for '$ch' must match original \"$input\" — got: $result",
      )
    }
  }

  @Test
  fun `escapeForSelector - dollar sign in price (JVM format)`() {
    val result = escapeForSelector("Charge \$1.99")
    assertEquals("\\QCharge \$1.99\\E", result)
    assertTrue(Regex(result).matches("Charge \$1.99"))
    assertFalse(Regex(result).matches("Charge X1.99"))
  }

  @Test
  fun `escapeForSelector - parentheses in text (JVM format)`() {
    val result = escapeForSelector("Current sale (1)")
    assertEquals("\\QCurrent sale (1)\\E", result)
    assertTrue(Regex(result).matches("Current sale (1)"))
  }

  @Test
  fun `escapeForSelector - pipe is escaped`() {
    val result = escapeForSelector("Debit|Credit")
    assertEquals("\\QDebit|Credit\\E", result)
    assertTrue(Regex(result).matches("Debit|Credit"))
    assertFalse(Regex(result).matches("Debit"))
    assertFalse(Regex(result).matches("Credit"))
  }

  @Test
  fun `escapeForSelector - caret is escaped`() {
    val result = escapeForSelector("^required")
    assertEquals("\\Q^required\\E", result)
    assertTrue(Regex(result).matches("^required"))
  }

  @Test
  fun `escapeForSelector - question mark is escaped`() {
    val result = escapeForSelector("Are you sure?")
    assertEquals("\\QAre you sure?\\E", result)
    assertTrue(Regex(result).matches("Are you sure?"))
  }

  @Test
  fun `escapeForSelector - backslash-E in input is handled correctly by Regex-escape`() {
    // Regression: manual "\\Q$s\\E" would break when $s contains "\E".
    // Regex.escape() handles this by splitting the quote block.
    val input = "foo\\Ebar"
    val result = escapeForSelector(input)
    assertTrue(
      Regex(result).matches(input),
      "Pattern must match original even when input contains \\E: got $result",
    )
  }

  @Test
  fun `escapeForSelector - empty string`() {
    assertEquals("", escapeForSelector(""))
    assertTrue(Regex(escapeForSelector("")).matches(""))
  }

  // Real-world content strings
  @Test
  fun `escapeForSelector - real-world content strings`() {
    // Safe (no metacharacters) — returned as-is
    for (safe in listOf("Review sale", "Record Payment", "Thanks", "Active", "Submit")) {
      val r = escapeForSelector(safe)
      assertEquals(safe, r)
      assertTrue(Regex(r).matches(safe))
    }
    // Must be escaped
    for (unsafe in listOf("Charge \$1.99", "Current sale (1)", "Debit|Credit", "v1.2", "Dr. Smith")) {
      val r = escapeForSelector(unsafe)
      assertFalse(r == unsafe, "'$unsafe' should have been escaped")
      assertTrue(Regex(r).matches(unsafe), "Escaped pattern must match '$unsafe'")
    }
  }

  // ---------------------------------------------------------------------------
  // escapeForIdentifier — identifier fields (className, resourceId, type)
  // ---------------------------------------------------------------------------

  @Test
  fun `escapeForIdentifier - Android resource IDs with dots are returned as-is`() {
    val ids = listOf(
      "com.example.app:id/my_view",
      "com.example.checkout:id/checkout_button_title",
      "com.example.cart:id/cart_heading",
    )
    for (id in ids) {
      val result = escapeForIdentifier(id)
      assertEquals(id, result, "Expected as-is for resource ID '$id'")
      assertTrue(Regex(result).matches(id), "Pattern '$result' must match '$id'")
    }
  }

  @Test
  fun `escapeForIdentifier - Android class names with dots are returned as-is`() {
    val classNames = listOf(
      "android.widget.TextView",
      "android.view.View",
      "android.view.ViewGroup",
      "android.widget.FrameLayout",
      "androidx.compose.ui.platform.ComposeView",
    )
    for (cn in classNames) {
      val result = escapeForIdentifier(cn)
      assertEquals(cn, result, "Expected as-is for class name '$cn'")
      assertTrue(Regex(result).matches(cn), "Pattern '$result' must match '$cn'")
    }
  }

  @Test
  fun `escapeForIdentifier - dot-only tradeoff is documented`() {
    // As an identifier, "android.view.View" is emitted as-is, meaning the dot matches
    // any char. "androidXviewYView" would also match. This is the accepted tradeoff:
    // no real element has a class name differing only at a dot position.
    val result = escapeForIdentifier("android.view.View")
    assertEquals("android.view.View", result)
    assertTrue(Regex(result).matches("android.view.View"))
    // Document (not enforce) the wildcard tradeoff:
    assertTrue(
      Regex(result).matches("androidXviewYView"),
      "Documented: identifier dot matches any char — accepted tradeoff",
    )
  }

  @Test
  fun `escapeForIdentifier - each non-dot metacharacter still triggers escaping`() {
    for (ch in IDENTIFIER_REGEX_METACHARACTERS) {
      val input = "foo${ch}bar"
      val result = escapeForIdentifier(input)
      assertFalse(
        result == input,
        "escapeForIdentifier with '$ch' should escape but returned as-is",
      )
      assertTrue(
        Regex(result).matches(input),
        "Escaped pattern for '$ch' must match \"$input\" — got: $result",
      )
    }
  }

  @Test
  fun `escapeForIdentifier - identifier with dollar sign is escaped`() {
    // Hypothetical: resource ID containing '$' must still be escaped correctly.
    val input = "com.example:id/price_\$field"
    val result = escapeForIdentifier(input)
    assertFalse(result == input)
    assertTrue(Regex(result).matches(input))
  }

  @Test
  fun `escapeForSelector vs escapeForIdentifier - dot handling differs`() {
    val dotString = "v1.2"
    val selectorResult = escapeForSelector(dotString)
    val identifierResult = escapeForIdentifier(dotString)

    // escapeForSelector wraps — dot is treated as a metachar for content fields
    assertFalse(selectorResult == dotString, "escapeForSelector should wrap strings with dots")
    assertTrue(Regex(selectorResult).matches(dotString))
    assertFalse(Regex(selectorResult).matches("v1x2"), "content: dot must be literal")

    // escapeForIdentifier returns as-is — dot is accepted wildcard for identifier fields
    assertEquals(dotString, identifierResult, "escapeForIdentifier should leave dots unescaped")
    assertTrue(Regex(identifierResult).matches("v1x2"), "identifier: dot matches any char")
  }
}
