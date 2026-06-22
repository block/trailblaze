package xyz.block.trailblaze.api

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ScreenshotScalingConfigTest {

  private val config = ScreenshotScalingConfig.DEFAULT // 1536x768

  @Test
  fun `image already within bounds is not scaled`() {
    // 768x1024: longSide=1024<=1536, shortSide=768<=768 → no scaling
    assertEquals(Pair(768, 1024), config.computeScaledDimensions(768, 1024))
  }

  @Test
  fun `landscape image exceeding long side is scaled down`() {
    // 3072x1536: longSide=3072, shortSide=1536
    // scaleLong=1536/3072=0.5, scaleShort=768/1536=0.5 → scale=0.5
    // result: 1536x768
    assertEquals(Pair(1536, 768), config.computeScaledDimensions(3072, 1536))
  }

  @Test
  fun `portrait image is scaled down correctly`() {
    // 1080x2400 (typical phone): longSide=2400, shortSide=1080
    // scaleLong=1536/2400=0.64, scaleShort=768/1080=0.711 → scale=0.64
    // result: 691x1536
    val (w, h) = config.computeScaledDimensions(1080, 2400)
    assertEquals(691, w)
    assertEquals(1536, h)
  }

  @Test
  fun `square image is scaled by short side constraint`() {
    // 2000x2000: longSide=2000, shortSide=2000
    // scaleLong=1536/2000=0.768, scaleShort=768/2000=0.384 → scale=0.384
    // result: 768x768
    assertEquals(Pair(768, 768), config.computeScaledDimensions(2000, 2000))
  }

  @Test
  fun `small image is not scaled up`() {
    // 320x480: both within bounds → returned as-is
    assertEquals(Pair(320, 480), config.computeScaledDimensions(320, 480))
  }

  @Test
  fun `web default keeps more vertical detail on a landscape capture`() {
    // 2560x1600 (1280x800 viewport at DPR 2.0), the common dev web capture.
    // mobile-tuned DEFAULT (1536x768) squeezes it to ~1228x768 — the grainy case.
    assertEquals(Pair(1228, 768), config.computeScaledDimensions(2560, 1600))
    // web-tuned WEB (1536x1024) keeps the long side at 1536 and lets the height reach 960.
    assertEquals(Pair(1536, 960), ScreenshotScalingConfig.WEB.computeScaledDimensions(2560, 1600))
  }

  @Test
  fun `web default raises quality without touching the long-side cap`() {
    assertEquals(0.90f, ScreenshotScalingConfig.WEB.compressionQuality)
    assertEquals(0.80f, ScreenshotScalingConfig.DEFAULT.compressionQuality)
    assertEquals(1536, ScreenshotScalingConfig.WEB.maxDimension1)
  }

  @AfterTest
  fun resetEffectiveConfig() {
    EffectiveScreenshotScalingConfig.clearForTests()
  }

  @Test
  fun `effectiveForWeb uses the web default when the user has set no override`() {
    EffectiveScreenshotScalingConfig.clearForTests()
    assertEquals(ScreenshotScalingConfig.WEB, EffectiveScreenshotScalingConfig.effectiveForWeb)
  }

  @Test
  fun `effectiveForWeb honors an explicit user customization unchanged`() {
    // e.g. a user who ran `trailblaze config screenshot-format png` — web must not override it.
    val userConfig = ScreenshotScalingConfig(imageFormat = TrailblazeImageFormat.PNG)
    EffectiveScreenshotScalingConfig.setEffectiveDefault(userConfig)
    assertSame(userConfig, EffectiveScreenshotScalingConfig.effectiveForWeb)
  }

  @Test
  fun `effectiveForWeb honors an explicit config even when it equals the framework default`() {
    // The fix for the value-equality gap: a user (or downstream distro) who explicitly pins the
    // framework defaults must NOT be silently switched to the web default.
    EffectiveScreenshotScalingConfig.setEffectiveDefault(ScreenshotScalingConfig.DEFAULT)
    assertEquals(ScreenshotScalingConfig.DEFAULT, EffectiveScreenshotScalingConfig.effectiveForWeb)
  }
}
