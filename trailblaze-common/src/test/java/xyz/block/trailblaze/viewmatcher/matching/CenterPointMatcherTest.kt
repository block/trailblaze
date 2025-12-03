package xyz.block.trailblaze.viewmatcher.matching

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for CenterPointMatcher utility for comparing center points with tolerance.
 */
class CenterPointMatcherTest {

  @Test
  fun `centerPointsMatch returns true for exact matches`() {
    assertTrue(
      CenterPointMatcher.centerPointsMatch("500,500", "500,500"),
      "Exact matches should return true",
    )
  }

  @Test
  fun `centerPointsMatch returns true for points within default 1px tolerance`() {
    // Within tolerance
    assertTrue(
      CenterPointMatcher.centerPointsMatch("500,500", "501,501"),
      "Should match when both X and Y differ by 1px (default tolerance)",
    )

    assertTrue(
      CenterPointMatcher.centerPointsMatch("500,500", "499,499"),
      "Should match with negative differences within tolerance",
    )
  }

  @Test
  fun `centerPointsMatch returns false for points outside tolerance`() {
    assertFalse(
      CenterPointMatcher.centerPointsMatch("500,500", "502,502", tolerancePx = 1),
      "Should not match when both coordinates differ by 2px with 1px tolerance",
    )

    assertFalse(
      CenterPointMatcher.centerPointsMatch("500,500", "600,600", tolerancePx = 1),
      "Should not match with large coordinate differences",
    )
  }

  @Test
  fun `centerPointsMatch handles null values`() {
    assertTrue(
      CenterPointMatcher.centerPointsMatch(null, null),
      "Two null values should match",
    )

    assertFalse(
      CenterPointMatcher.centerPointsMatch(null, "500,500"),
      "Null should not match non-null",
    )

    assertFalse(
      CenterPointMatcher.centerPointsMatch("500,500", null),
      "Non-null should not match null",
    )
  }

  @Test
  fun `default tolerance constant is 5px`() {
    assertEquals(
      5,
      CenterPointMatcher.DEFAULT_TOLERANCE_PX,
      "Default tolerance should be 1px",
    )
  }
}
