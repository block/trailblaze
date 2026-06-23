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
}
