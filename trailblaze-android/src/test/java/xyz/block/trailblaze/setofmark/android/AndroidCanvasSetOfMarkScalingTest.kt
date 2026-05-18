package xyz.block.trailblaze.setofmark.android

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure-math tests for [AndroidCanvasSetOfMark] label sizing.
 *
 * The drawing path scales `BASE_TEXT_SIZE` (24f), `BASE_BOX_OUTLINE_THICKNESS` (4f), and
 * `BASE_PADDING` (5f) by [AndroidCanvasSetOfMark.drawScaleFor]. The full draw call needs an
 * Android `Bitmap` + `Canvas` (covered by the androidTest integration), but the math itself
 * is platform-agnostic — pinning it as JVM unit tests means a regression on the canonical
 * scenarios fails the build deterministically, instead of needing a human to eyeball a CI
 * screenshot.
 *
 * Each test asserts against the actual production scenario, not idealized math. The bitmap
 * dimensions a test passes in are the dimensions that reach `drawAnnotationsOnBitmap` AFTER
 * `ScreenshotScalingConfig.DEFAULT.scale()` has downscaled the raw device capture (max
 * 1536×768 per long/short side) — see the kdoc on `Bitmap.drawScale` in the production
 * class for the upstream pipeline.
 */
class AndroidCanvasSetOfMarkScalingTest {

  @Test
  fun `phone 1080x1920 portrait — no downscale, scale=1_0 (canonical reference)`() {
    // 1080×1920 device produces a 1080×1920 bitmap (within the scaleAndEncode bounds on
    // typical phone-density configs). scale=1.0 is the reference point: text=24px,
    // outlines=4px, padding=5px (the BASE_* constants).
    assertEquals(1.0f, AndroidCanvasSetOfMark.drawScaleFor(1080, 1920, 1080, 1920), 1e-6f)
  }

  @Test
  fun `phone 720x1280 portrait — no downscale, scale=1_0 (low-res device, not a downscale)`() {
    // A 720×1280 device is a real (low-density) device, not a downscale of something bigger.
    // The bitmap=device math gives scale=1.0 → text=24 device-pixels. This is a deliberate
    // behavior change from earlier rounds of PR #3032 (which produced scale=0.67 → text=16px
    // via the magic `min(w,h)/1080` algorithm). The bitmap=device rule is the principled
    // answer: labels render at the same 24-device-pixel size a user would see if they
    // looked at the device directly.
    assertEquals(1.0f, AndroidCanvasSetOfMark.drawScaleFor(720, 1280, 720, 1280), 1e-6f)
  }

  @Test
  fun `phone 1440x2960 portrait — short-side downscale via scaleAndEncode`() {
    // 1440×2960 device → `ScreenshotScalingConfig.DEFAULT` rule picks the smaller of
    // 768/1440 (=0.533) and 1536/2960 (=0.519) — short side dominates at 0.519. Resulting
    // bitmap: 1440×0.519 ≈ 748 wide, 2960×0.519 = 1536 tall. Labels scale by the same ratio.
    val expected = minOf(748f / 1440f, 1536f / 2960f)
    assertEquals(expected, AndroidCanvasSetOfMark.drawScaleFor(748, 1536, 1440, 2960), 1e-6f)
  }

  @Test
  fun `tablet 1920x1080 landscape — downscaled to 1366x768, scale=0_71 (the bug case)`() {
    // The regression case this PR fixes. Pre-PR (raw width / 1080): drawScale = 1366/1080
    // ≈ 1.27 — text 30px, outlines 5.1px, padding 6.4px — labels visibly swamped launcher-
    // grid icons. Post-PR: scale = min(1366/1920, 768/1080) = 0.711 — text 17px. Asserting
    // 0.711 here pins the fix; any future refactor that abandons the bitmap-to-device
    // ratio would break this test.
    assertEquals(768f / 1080f, AndroidCanvasSetOfMark.drawScaleFor(1366, 768, 1920, 1080), 1e-6f)
  }

  @Test
  fun `tablet 2560x1600 landscape — downscaled to 1228x768, scale near 0_48`() {
    // Higher-res tablet → more aggressive downscale via scaleAndEncode (short side 1600 →
    // 768 forces ratio 0.48). Labels scale by the same ratio: text = 24 × 0.48 ≈ 11.5px.
    // The icons in this bitmap are also at 48% of their device-pixel size, so the
    // label-to-icon ratio is preserved end-to-end.
    //
    // Note: bitmap is 1228 wide (the int floor of 2560 × 0.48), so width_ratio = 1228/2560
    // = 0.4797 is fractionally smaller than height_ratio = 768/1600 = 0.48. The algorithm
    // picks the smaller, which gives 0.4797. The 1-pixel rounding from upstream `scale()`
    // produces this fractional mismatch — drawScale ends up at most 1 device-pixel
    // tighter than the upstream scaleAmount, which is fine for label sizing.
    assertEquals(1228f / 2560f, AndroidCanvasSetOfMark.drawScaleFor(1228, 768, 2560, 1600), 1e-6f)
  }

  @Test
  fun `phone 1920x1080 landscape — same downscale as the landscape tablet, scale=0_71`() {
    // A phone rotated to landscape (or a fold/unfold transition) produces the same
    // dimensions as a landscape tablet. The algorithm doesn't (and shouldn't) try to
    // distinguish form factor from pixels alone.
    assertEquals(768f / 1080f, AndroidCanvasSetOfMark.drawScaleFor(1366, 768, 1920, 1080), 1e-6f)
  }

  @Test
  fun `orientation-invariant — swapping both bitmap and device dimensions gives the same scale`() {
    val landscape = AndroidCanvasSetOfMark.drawScaleFor(1366, 768, 1920, 1080)
    val portrait = AndroidCanvasSetOfMark.drawScaleFor(768, 1366, 1080, 1920)
    assertEquals(
      landscape,
      portrait,
      1e-6f,
      "drawScale must be symmetric under rotation; otherwise the same device renders SoM " +
        "at different sizes depending on orientation.",
    )
  }

  @Test
  fun `square 1080x1080 device, no downscale — scale=1_0`() {
    // Boundary case: width == height. Both bitmap_w/device_w and bitmap_h/device_h are 1.0.
    // Square bitmaps don't happen in practice on Android but the math holds; pinned so a
    // future refactor that special-cases landscape-vs-portrait can't break this corner.
    assertEquals(1.0f, AndroidCanvasSetOfMark.drawScaleFor(1080, 1080, 1080, 1080), 1e-6f)
  }

  @Test
  fun `degenerate 1x1 bitmap on a real device — scale collapses with the downscale`() {
    // Production callers never produce 1×1 bitmaps via `BitmapFactory.decodeByteArray`,
    // which rejects malformed input. This test pins the algorithm's behavior at the
    // boundary so a future guard (e.g. a clamp to a minimum scale) gets caught — the
    // contract change must be intentional, not silent.
    assertEquals(
      1f / 1080f,
      AndroidCanvasSetOfMark.drawScaleFor(1, 1, 1080, 1080),
      1e-6f,
    )
  }

  @Test
  fun `drawScaleFor picks the smaller of the two axis ratios`() {
    // When the bitmap's width/device ratio and height/device ratio differ (atypical — most
    // production downscales preserve aspect ratio), the algorithm picks the smaller, since
    // `ScreenshotScalingConfig.scale()` upstream picks the smaller too. Pinning this
    // explicitly so a future refactor that takes `max` or an average gets caught.
    // Width ratio = 1000/2000 = 0.5; height ratio = 800/1000 = 0.8. min = 0.5.
    assertEquals(0.5f, AndroidCanvasSetOfMark.drawScaleFor(1000, 800, 2000, 1000), 1e-6f)
    // Width ratio = 1600/2000 = 0.8; height ratio = 800/1000 = 0.8. tied — both axes 0.8.
    assertEquals(0.8f, AndroidCanvasSetOfMark.drawScaleFor(1600, 800, 2000, 1000), 1e-6f)
  }
}
