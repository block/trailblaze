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
  fun `default options enable both device-log streams but not video`() {
    // Log capture is always-on by default (per-platform gating in CaptureSession.fromOptions
    // means logcat only acts on Android and iOS logs only on iOS). Video is opt-in — it writes
    // large files and sprite extraction is expensive, so a run must ask for it explicitly.
    val options = CaptureOptions()
    assertFalse(options.captureVideo)
    assertTrue(options.captureLogcat)
    assertTrue(options.captureIosLogs)
    assertTrue(options.hasAnyCaptureEnabled)
  }

  @Test
  fun `NONE has all capture flags off`() {
    // NONE is explicitly all-off (the default constructor still enables the log streams), so it
    // genuinely means "no capture" — distinct from CaptureOptions().
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
    assertFalse(options.captureVideo)
  }

  @Test
  fun `hostCaptureOptions reads sprite tuning from the environment`() {
    val env = mapOf(
      CaptureOptions.ENV_SPRITE_FPS to "8",
      CaptureOptions.ENV_SPRITE_FRAME_HEIGHT to "1280",
      CaptureOptions.ENV_SPRITE_QUALITY to "90",
    )
    val options = CaptureOptions.hostCaptureOptions(captureVideo = true, env = env::get)
    assertEquals(8, options.spriteFrameFps)
    assertEquals(1280, options.spriteFrameHeight)
    assertEquals(90, options.spriteQuality)
    // The explicit opt-in must thread through — video defaults off, so this can only be true
    // if the caller's value is honored.
    assertTrue(options.captureVideo)
  }

  @Test
  fun `TRAILBLAZE_CAPTURE_VIDEO turns video on for a caller that did not ask for it`() {
    // The lever CI reaches for: a pipeline can't pass --capture-video (its trails are launched by
    // scripts it doesn't own), so the env var has to be able to override the off default alone.
    for (truthy in listOf("1", "true", "TRUE", "True")) {
      val env = mapOf(CaptureOptions.ENV_CAPTURE_VIDEO to truthy)
      assertTrue(
        CaptureOptions.hostCaptureOptions(captureVideo = null, env = env::get).captureVideo,
        "'$truthy' should read as an opt-in",
      )
    }
  }

  @Test
  fun `an absent, blank, or falsey TRAILBLAZE_CAPTURE_VIDEO leaves video off`() {
    // A malformed value must never silently switch a large-artifact stream on.
    for (falsey in listOf(null, "", "  ", "0", "false", "no", "yes", "on")) {
      val env = mapOf(CaptureOptions.ENV_CAPTURE_VIDEO to falsey)
      assertFalse(
        CaptureOptions.hostCaptureOptions(captureVideo = null, env = env::get).captureVideo,
        "'$falsey' should not read as an opt-in",
      )
    }
  }

  @Test
  fun `TRAILBLAZE_CAPTURE_VIDEO does not disturb a caller that already opted in`() {
    val env = mapOf(CaptureOptions.ENV_CAPTURE_VIDEO to "0")
    assertTrue(CaptureOptions.hostCaptureOptions(captureVideo = true, env = env::get).captureVideo)
  }

  @Test
  fun `an explicit no-video beats TRAILBLAZE_CAPTURE_VIDEO and the saved config`() {
    // `--no-capture-video` is how a developer opts out of a lane that exports the env var, or of
    // their own `trailblaze config capture-video true`. If the lower tiers could override it, the
    // documented CLI-over-environment precedence would be a lie and the flag would do nothing.
    val env = mapOf(CaptureOptions.ENV_CAPTURE_VIDEO to "1")
    assertFalse(
      CaptureOptions.hostCaptureOptions(
        captureVideo = false,
        persistedCaptureVideo = true,
        env = env::get,
      ).captureVideo,
    )
  }

  @Test
  fun `the saved config turns video on when nothing higher has an opinion`() {
    // `trailblaze config capture-video true` is the only opt-in reachable from interactive
    // `session start` and MCP, which have no per-run flag to pass.
    assertTrue(
      CaptureOptions.hostCaptureOptions(
        captureVideo = null,
        persistedCaptureVideo = true,
        env = { null },
      ).captureVideo,
    )
  }

  @Test
  fun `TRAILBLAZE_CAPTURE_VIDEO turns video on over a saved config that leaves it off`() {
    // The tier between the flag and the config: a CI lane exports the env var without touching
    // the developer's persisted settings.
    val env = mapOf(CaptureOptions.ENV_CAPTURE_VIDEO to "1")
    assertTrue(
      CaptureOptions.hostCaptureOptions(
        captureVideo = null,
        persistedCaptureVideo = false,
        env = env::get,
      ).captureVideo,
    )
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
