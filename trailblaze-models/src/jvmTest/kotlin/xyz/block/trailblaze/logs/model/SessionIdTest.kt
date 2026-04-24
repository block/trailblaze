package xyz.block.trailblaze.logs.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SessionIdTest {

  @Test
  fun `sanitized replaces non-alphanumeric characters with underscores`() {
    assertEquals(
      SessionId("my_test_id_1_2_3"),
      SessionId.sanitized("my.test-id 1/2:3"),
    )
  }

  @Test
  fun `sanitized lowercases`() {
    assertEquals(
      SessionId("myuppertest"),
      SessionId.sanitized("MyUpperTest"),
    )
  }

  @Test
  fun `sanitized preserves long TestRail-style suffixes without truncation`() {
    // Regression pin: the previous 100-char cap dropped __suite/__section/__case
    // suffixes that downstream result-mapping tooling relies on.
    val longTestRailId =
      "2026_04_20_11_16_18_example_suite_long_test_name_" +
        "verify_that_the_action_buttons_appear_for_active_items" +
        "__suite_1__section_2__case_3_1234"

    val sanitized = SessionId.sanitized(longTestRailId)

    assertEquals(longTestRailId.length, sanitized.value.length)
    assertTrue(
      sanitized.value.contains("__suite_1__section_2__case_3"),
      "TestRail suffix must survive sanitization; got: ${sanitized.value}",
    )
  }

  @Test
  fun `sanitized is idempotent across representative inputs`() {
    // The on-device handler re-sanitizes any override ID. If sanitization is
    // not idempotent for any real-world input, host and device end up writing
    // to two different session directories for the same logical session.
    // Test across mixed case, dots, hyphens, whitespace, slashes, and an
    // already-canonical value — not just a single canonical input.
    val inputs = listOf(
      "2026_04_20_11_16_18_test__suite_1__section_2__case_3_1234",
      "Test.ID-v1.2",
      "path/to/test_case",
      "Foo Bar Baz",
      "MixedCASE.with-special:chars",
      "already_canonical_form",
    )
    for (raw in inputs) {
      val once = SessionId.sanitized(raw)
      val twice = SessionId.sanitized(once.value)
      assertEquals(once, twice, "not idempotent for input: $raw")
    }
  }

  @Test
  fun `sanitized maps all-non-alphanumeric input to underscores`() {
    // Edge case pin: input with no alphanumeric characters still produces a
    // valid (non-blank) SessionId of all underscores. Not pretty, but it
    // doesn't throw — a future change that makes this throw should be a
    // deliberate decision, not an accident.
    assertEquals(SessionId("___"), SessionId.sanitized("!!!"))
    assertEquals(SessionId("___"), SessionId.sanitized("   "))
  }

  @Test
  fun `sanitized throws on empty input via the blank invariant`() {
    // sanitized("") -> "" -> SessionId("") -> require(isNotBlank) fails. The
    // throw is the right behavior (fail fast); pin it so a future change that
    // silently relaxes it is visible.
    assertFailsWith<IllegalArgumentException> { SessionId.sanitized("") }
  }
}
