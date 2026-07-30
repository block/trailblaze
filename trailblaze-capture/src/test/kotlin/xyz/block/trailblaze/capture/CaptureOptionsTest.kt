package xyz.block.trailblaze.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CaptureOptionsTest {

  @Test
  fun `hasAnyCaptureEnabled is false when video, logcat, and iosLogs all off`() {
    val options = CaptureOptions(
      captureVideo = false,
      captureLogcat = false,
      captureIosLogs = false,
    )
    assertFalse(options.hasAnyCaptureEnabled)
  }

  @Test
  fun `hasAnyCaptureEnabled is true when only video is on`() {
    val options = CaptureOptions(
      captureVideo = true,
      captureLogcat = false,
      captureIosLogs = false,
    )
    assertTrue(options.hasAnyCaptureEnabled)
  }

  @Test
  fun `hasAnyCaptureEnabled is true when only logcat is on`() {
    val options = CaptureOptions(
      captureVideo = false,
      captureLogcat = true,
      captureIosLogs = false,
    )
    assertTrue(options.hasAnyCaptureEnabled)
  }

  @Test
  fun `hasAnyCaptureEnabled is true when only iosLogs is on`() {
    val options = CaptureOptions(
      captureVideo = false,
      captureLogcat = false,
      captureIosLogs = true,
    )
    assertTrue(options.hasAnyCaptureEnabled)
  }

  @Test
  fun `default options enable video and both device-log streams`() {
    // Log capture is always-on by default (per-platform gating in CaptureSession.fromOptions
    // means logcat only acts on Android and iOS logs only on iOS); video is on too.
    val options = CaptureOptions()
    assertTrue(options.captureVideo)
    assertTrue(options.captureLogcat)
    assertTrue(options.captureIosLogs)
    assertTrue(options.hasAnyCaptureEnabled)
  }

  @Test
  fun `NONE has all capture flags off`() {
    // NONE is explicitly all-off (the default constructor is now all-ON), so it genuinely
    // means "no capture" — distinct from CaptureOptions().
    assertFalse(CaptureOptions.NONE.captureVideo)
    assertFalse(CaptureOptions.NONE.captureLogcat)
    assertFalse(CaptureOptions.NONE.captureIosLogs)
    assertFalse(CaptureOptions.NONE.hasAnyCaptureEnabled)
    assertNotEquals(CaptureOptions(), CaptureOptions.NONE)
  }

  @Test
  fun `web sprite tuning substitutes the larger defaults when the user has not overridden them`() {
    val options = CaptureOptions()
    assertEquals(CaptureOptions.WEB_SPRITE_HEIGHT, options.webSpriteFrameHeight())
    assertEquals(CaptureOptions.WEB_SPRITE_QUALITY, options.webSpriteQuality())
    // sanity: the web sprite is genuinely crisper than the mobile-tuned default
    assertTrue(CaptureOptions.WEB_SPRITE_HEIGHT > CaptureOptions.DEFAULT_SPRITE_HEIGHT)
    assertTrue(CaptureOptions.WEB_SPRITE_QUALITY > CaptureOptions.DEFAULT_SPRITE_QUALITY)
  }

  @Test
  fun `web sprite tuning honors an explicit user override`() {
    val options = CaptureOptions(spriteFrameHeight = 480, spriteQuality = 70)
    assertEquals(480, options.webSpriteFrameHeight())
    assertEquals(70, options.webSpriteQuality())
  }

  @Test
  fun `hostCaptureOptions defaults to the host sprite tuning when no env vars are set`() {
    val options = CaptureOptions.hostCaptureOptions(env = { null })
    assertEquals(CaptureOptions.HOST_SPRITE_FPS, options.spriteFrameFps)
    assertEquals(CaptureOptions.HOST_SPRITE_HEIGHT, options.spriteFrameHeight)
    assertEquals(CaptureOptions.HOST_SPRITE_QUALITY, options.spriteQuality)
    assertTrue(options.captureVideo)
  }

  @Test
  fun `hostCaptureOptions reads sprite tuning from the environment`() {
    val env = mapOf(
      CaptureOptions.ENV_SPRITE_FPS to "8",
      CaptureOptions.ENV_SPRITE_FRAME_HEIGHT to "1280",
      CaptureOptions.ENV_SPRITE_QUALITY to "90",
    )
    val options = CaptureOptions.hostCaptureOptions(captureVideo = false, env = env::get)
    assertEquals(8, options.spriteFrameFps)
    assertEquals(1280, options.spriteFrameHeight)
    assertEquals(90, options.spriteQuality)
    assertFalse(options.captureVideo)
  }

  @Test
  fun `hostCaptureOptions falls back to defaults on non-numeric, out-of-range, or blank env values`() {
    // A bad env var must never take down video capture — each variable degrades independently.
    val env = mapOf(
      CaptureOptions.ENV_SPRITE_FPS to "fast",
      CaptureOptions.ENV_SPRITE_FRAME_HEIGHT to "99999",
      CaptureOptions.ENV_SPRITE_QUALITY to "  ",
    )
    val options = CaptureOptions.hostCaptureOptions(env = env::get)
    assertEquals(CaptureOptions.HOST_SPRITE_FPS, options.spriteFrameFps)
    assertEquals(CaptureOptions.HOST_SPRITE_HEIGHT, options.spriteFrameHeight)
    assertEquals(CaptureOptions.HOST_SPRITE_QUALITY, options.spriteQuality)
  }
}
