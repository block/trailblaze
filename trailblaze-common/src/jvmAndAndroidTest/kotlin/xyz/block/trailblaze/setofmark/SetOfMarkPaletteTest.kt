package xyz.block.trailblaze.setofmark

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression guards on the values shared between `HostCanvasSetOfMark` (AWT) and
 * `AndroidCanvasSetOfMark`. The point of this test class is to fail loudly if a future
 * change drifts any of the shared atoms — values that would be hard to spot via visual
 * review of either canvas alone.
 */
class SetOfMarkPaletteTest {

  @Test
  fun `BASE_TEXT_SIZE is 24f — both canvases multiply this by their own scale factor`() {
    // The 24f value is the historical default from the Arbigent reference. Either canvas
    // tuning this lower (compact desktop) or higher (low-DPI phone) should happen through
    // each platform's local scale math, not by mutating the shared base value — otherwise
    // the other platform silently inherits the tuning. Bump intentionally if both platforms
    // genuinely want a new base; the test failure is the reminder.
    assertEquals(24f, SetOfMarkPalette.BASE_TEXT_SIZE, 0f)
  }

  @Test
  fun `BASE_PADDING is 5 px on both axes`() {
    // The in-label-box padding (text→background-rect edge). Inset on all four sides, so a
    // 5-pixel padding produces a label rect 10 wider + 10 taller than the raw text bounds.
    // Same comment as BASE_TEXT_SIZE — local-tuning vs. shared-value calls happen via
    // each canvas's scale path, not by drifting this constant.
    assertEquals(5, SetOfMarkPalette.BASE_PADDING)
  }

  @Test
  fun `palette has exactly 10 colors — both canvases rely on this for modulo indexing`() {
    // Both canvases do `colors[index % colors.size]` to cycle border + background colors
    // across annotation elements. The visual "color rhythm" depends on this count staying
    // at 10 — bumping to 8 or 12 changes which adjacent boxes share a color on dense
    // pages. Don't change the count without a deliberate UX decision; this test pins it.
    assertEquals(10, SetOfMarkPalette.BORDER_AND_BACKGROUND_COLORS_RGB.size)
  }

  @Test
  fun `palette colors fit in 24-bit RGB — no stray alpha bytes from accidental ARGB inputs`() {
    // The palette is documented as plain RGB (no alpha byte). In practice an alpha-baked
    // entry (e.g. `0xFF3F9101`) wouldn't visibly break either consumer — `java.awt.Color(int)`
    // single-arg uses bits 0-23 as RGB and forces alpha=0xFF, ignoring the high byte; and
    // Android's `0xFF000000 or 0xFF…` is a no-op on bits already set to 0xFF. The visible
    // bug is the contract drift, not the rendered output: code reading
    // `BORDER_AND_BACKGROUND_COLORS_RGB` directly (e.g. equality comparisons across
    // platforms, hashes, future serializers) would see different ints than the comment
    // claims it should. Pinning the 24-bit invariant keeps that contract honest.
    SetOfMarkPalette.BORDER_AND_BACKGROUND_COLORS_RGB.forEachIndexed { index, rgb ->
      assertTrue(
        rgb in 0..0xFFFFFF,
        "Palette entry at index $index = ${rgb.toString(16)} is outside 24-bit RGB range. " +
          "Did you accidentally include an alpha byte (e.g. 0xFF3F9101)? Strip the high byte.",
      )
    }
  }

  @Test
  fun `palette colors are all distinct — modulo cycling produces 10 visually different boxes`() {
    // The 10 hues were chosen for visual distinctness across a 10-element rotation; a
    // duplicate in the list silently shrinks the effective color cycle (10 entries but
    // only 9 distinct rendered hues), and if the duplicate entries land next to each other
    // in palette order, adjacent annotations on a dense page render with the same color.
    // Either case is a UX regression — pin the count so a duplicate-by-typo fails loudly.
    val distinct = SetOfMarkPalette.BORDER_AND_BACKGROUND_COLORS_RGB.toSet()
    assertEquals(
      10,
      distinct.size,
      "Expected 10 distinct palette colors, got ${distinct.size}. " +
        "Duplicates: ${SetOfMarkPalette.BORDER_AND_BACKGROUND_COLORS_RGB.groupingBy { it }.eachCount().filter { it.value > 1 }}",
    )
  }

  @Test
  fun `colorRgbAtIndex wraps via modulo — index 10 returns the same color as index 0`() {
    // The N-th annotation gets `colorRgbAtIndex(N)`. With 10 palette colors, indices
    // 10/20/30/… should cycle back to the first color. This is the contract both
    // canvases rely on; a bug where one side used `index % size` and the other used a
    // sliced/truncated approach would produce different color rhythms on dense pages.
    assertEquals(
      SetOfMarkPalette.colorRgbAtIndex(0),
      SetOfMarkPalette.colorRgbAtIndex(10),
    )
    assertEquals(
      SetOfMarkPalette.colorRgbAtIndex(3),
      SetOfMarkPalette.colorRgbAtIndex(13),
    )
    assertEquals(
      SetOfMarkPalette.colorRgbAtIndex(9),
      SetOfMarkPalette.colorRgbAtIndex(99),
    )
  }

  @Test
  fun `colorRgbAtIndex handles negative indices via Kotlin's mod (always non-negative)`() {
    // `Int.mod(Int)` (Kotlin) differs from `%` (Java): mod always returns a non-negative
    // value for a positive divisor, while `%` matches the sign of the dividend. The
    // helper uses `mod` so negative indices (e.g. from a future caller that pre-decrements
    // a counter) don't IOOBE on a negative array index. Pin the behavior so a refactor
    // back to `%` gets caught.
    assertEquals(
      SetOfMarkPalette.colorRgbAtIndex(0),
      SetOfMarkPalette.colorRgbAtIndex(-10),
    )
    assertEquals(
      SetOfMarkPalette.colorRgbAtIndex(7),
      SetOfMarkPalette.colorRgbAtIndex(-3),
    )
  }
}
