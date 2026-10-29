package xyz.block.trailblaze.capture.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

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
  fun `a companion's logcat shares its recording's suffix`() {
    assertEquals("device-buyer.log", CaptureFilenames.companionDeviceLog("video-buyer"))
    assertEquals("device-buyer-2.log", CaptureFilenames.companionDeviceLog("video-buyer-2"), "a reclaimed basename keeps its -2")
    assertNotEquals(CaptureFilenames.DEVICE_LOG, CaptureFilenames.companionDeviceLog(CaptureFilenames.companionVideoBasename("")))
  }

  @Test
  fun `the start device's and the companions' logcats are device logs, other files are not`() {
    assertTrue(CaptureFilenames.isDeviceLog(CaptureFilenames.DEVICE_LOG))
    assertTrue(CaptureFilenames.isDeviceLog(CaptureFilenames.companionDeviceLog("video-buyer-2")))
    assertFalse(CaptureFilenames.isDeviceLog("device-buyer.json"))
    assertFalse(CaptureFilenames.isDeviceLog("devices.log"))
  }

  @Test
  fun `an operator's device name is reduced to a safe path segment`() {
    assertEquals("video-Register_2", CaptureFilenames.companionVideoBasename("Register 2"))
    assertEquals("video-kiosk_front", CaptureFilenames.companionVideoBasename("kiosk/front"))
    assertEquals("video-device", CaptureFilenames.companionVideoBasename(""))
    assertEquals("video-a.b-c_d", CaptureFilenames.companionVideoBasename("a.b-c_d"), "safe punctuation is kept as written")
  }
}
