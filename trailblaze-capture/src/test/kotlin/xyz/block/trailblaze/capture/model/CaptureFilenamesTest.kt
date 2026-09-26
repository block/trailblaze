package xyz.block.trailblaze.capture.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * How a companion device's recording is named in a multi-device session. The names come from
 * trail YAML, so they have to become safe path segments without two devices colliding on the
 * start device's canonical file.
 */
class CaptureFilenamesTest {

  @Test
  fun `a companion recording is named after its device and never over the start device's file`() {
    assertEquals("video-buyer", CaptureFilenames.companionVideoBasename("buyer"))
    assertNotEquals(CaptureFilenames.VIDEO_BASENAME, CaptureFilenames.companionVideoBasename("video"))
  }

  @Test
  fun `an operator's device name is reduced to a safe path segment`() {
    assertEquals("video-Register_2", CaptureFilenames.companionVideoBasename("Register 2"))
    assertEquals("video-kiosk_front", CaptureFilenames.companionVideoBasename("kiosk/front"))
    assertEquals("video-device", CaptureFilenames.companionVideoBasename(""))
    assertEquals("video-a.b-c_d", CaptureFilenames.companionVideoBasename("a.b-c_d"), "safe punctuation is kept as written")
  }
}
