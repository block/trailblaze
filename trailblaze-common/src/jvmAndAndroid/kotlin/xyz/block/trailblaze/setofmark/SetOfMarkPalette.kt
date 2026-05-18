package xyz.block.trailblaze.setofmark

/**
 * Atoms shared between [HostCanvasSetOfMark] (JVM, AWT `Graphics2D`) and
 * [xyz.block.trailblaze.setofmark.android.AndroidCanvasSetOfMark] (Android, `Canvas`).
 *
 * **What's shared** (defined here):
 * - [BASE_TEXT_SIZE] — the base label text size. Both canvases multiply this by their own
 *   per-platform scale factor.
 * - [BASE_PADDING] — the in-label-box padding (text → background-rect edge). Same value
 *   on both platforms; kept here so a bump on one side bumps the other.
 * - [BORDER_AND_BACKGROUND_COLORS_RGB] — the 10-color palette used to cycle border +
 *   label-background colors across annotation elements. Stored as plain RGB ints (no alpha
 *   byte); each canvas wraps them into its native color type at draw time.
 *
 * **What's intentionally NOT shared** (each canvas keeps its own):
 * - **Scale math.** Android scales by `min(bitmap.w / device.w, bitmap.h / device.h)` —
 *   the actual `scaleAndEncode` downscale ratio — because Android screenshots are
 *   pre-downscaled by `ScreenshotScalingConfig` before the draw path sees them. Host scales
 *   by an area-threshold step function (`COMPACT_MODE_IMAGE_AREA = 1_000_000L` flips text
 *   from 24f to 14f) because Host screenshots come from Compose/Playwright at native
 *   resolution and the dense-desktop case has visibly different aesthetic requirements than
 *   the mobile-portrait case. The two scale models address different platform realities;
 *   trying to unify them on one rule would either drop Host's compact-mode density cap +
 *   collision-avoidance (real features guarded by regression tests) or force the bitmap/
 *   device ratio onto a Host pipeline where there is no upstream downscale to compensate
 *   for. Leaving the math local also means an LLM consuming both Android and Host
 *   screenshots in the same trail context still sees label sizes calibrated to each
 *   platform's UI density.
 * - **Outline thickness.** Android uses `4f` base (matching the Arbigent reference);
 *   Host uses `2f` base + `1.25f` compact. Different aesthetic call — desktop bitmaps with
 *   thin UI strokes look better with thinner outlines; Android's chunkier touch-target UI
 *   tolerates the heavier 4f line.
 * - **Compact-mode behaviors on Host:** density cap (`MAX_ANNOTATIONS_COMPACT_MODE`),
 *   collision-avoiding label placement. These have no Android-side analog because Android
 *   screenshots come from a single app foreground at a time, not dense desktop pages.
 *
 * If you find yourself wanting to add another field here, the test is: would diverging
 * values on the two platforms produce a meaningful UX or LLM-quality regression? If yes,
 * share it. If no (e.g. it's a Host-specific aesthetic choice with no Android equivalent),
 * leave it local.
 */
object SetOfMarkPalette {

  /**
   * Base label text size, in the unit each platform's text-rendering API expects:
   * - Android `Paint.textSize`: pixels
   * - AWT `Font.deriveFont(Float)`: points (typographic), which in default contexts maps
   *   to pixels at 72 DPI — close enough to be a wash relative to the per-canvas scale
   *   factor each side applies on top.
   *
   * 24 is the historical value both implementations have used; sharing it here means a
   * future tuning pass touches one number, not two.
   */
  const val BASE_TEXT_SIZE = 24f

  /**
   * In-label-box padding (between the rendered text and the colored background rectangle's
   * edge), in pixels. Both canvases use this as the inset on all four sides of the label
   * box, so a 5-pixel padding produces a label rect 10 pixels wider + 10 pixels taller
   * than the raw text bounds.
   */
  const val BASE_PADDING = 5

  /**
   * Ten-color palette cycled across annotation indices (`colors[index % size]`). Stored
   * as plain RGB ints — no alpha byte — so the same list works for both
   * `java.awt.Color(rgbInt)` (Host: defaults to opaque) and Android's color int
   * conventions (where `0xFF000000 or rgbInt` produces an opaque ARGB int).
   *
   * The specific hues were inherited from the original Arbigent reference
   * (https://github.com/takahirom/arbigent). They're chosen for visual distinctness across
   * a 10-element rotation; bumping them is fine if a future palette pass picks values
   * with better contrast against typical app backgrounds, but the count (10) is load-
   * bearing for both canvases' modulo indexing — don't change it without auditing both
   * call sites.
   */
  val BORDER_AND_BACKGROUND_COLORS_RGB: List<Int> = listOf(
    0x3F9101, // Green
    0x0E4A8E, // Blue
    0xBCBF01, // Yellowish
    0xBC0BA2, // Pink
    0x61AA0D, // Light Green
    0x3D017A, // Purple
    0xD6A60A, // Orange-Yellow
    0x7710A3, // Deep Purple
    0xA502CE, // Magenta
    0xEB5A00, // Orange
  )

  /**
   * Modulo color cycling. Returns the RGB int at `index % palette.size`. Both canvases
   * call this for every annotation element, so the cycle pattern stays consistent —
   * `index = 0` always gets Green, `index = 10` wraps back to Green, `index = 11` gets
   * Blue, and so on across both Android and Host renders.
   */
  fun colorRgbAtIndex(index: Int): Int =
    BORDER_AND_BACKGROUND_COLORS_RGB[index.mod(BORDER_AND_BACKGROUND_COLORS_RGB.size)]
}
