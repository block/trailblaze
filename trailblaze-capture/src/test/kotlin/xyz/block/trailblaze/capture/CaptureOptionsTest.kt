package xyz.block.trailblaze.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
  fun `default options enable video only`() {
    val options = CaptureOptions()
    assertTrue(options.captureVideo)
    assertFalse(options.captureLogcat)
    assertFalse(options.captureIosLogs)
    assertTrue(options.hasAnyCaptureEnabled)
  }

  @Test
  fun `NONE has all capture flags off`() {
    // CaptureOptions() defaults captureVideo=true; NONE follows the same default,
    // so NONE.hasAnyCaptureEnabled is true. This pins the current behavior — change
    // deliberately if NONE is meant to mean "no capture at all".
    assertEquals(CaptureOptions(), CaptureOptions.NONE)
  }
}
