package xyz.block.trailblaze.capture.video

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform

/**
 * The decision that routes a session to the streaming recorder or the screencap fallback.
 *
 * Getting it wrong is expensive in one direction only, so the cases below pin both the answer and
 * the bias: an unreadable probe keeps the device on the cheap path.
 */
class AndroidScreenrecordSupportTest {

  @AfterTest
  fun tearDown() = AndroidScreenrecordSupport.resetCacheForTests()

  private fun device(serial: String) = TrailblazeDeviceId(serial, TrailblazeDevicePlatform.ANDROID)

  @Test
  fun `a device that answers with the binary's path can stream its screen`() {
    assertTrue(AndroidScreenrecordSupport.interpret("/system/bin/screenrecord"))
  }

  @Test
  fun `a device whose only mention of the binary is an error cannot stream its screen`() {
    // The discriminating case. Both answers contain the same path, so a substring match would
    // call this device healthy and leave the session with an unplayable empty recording.
    assertFalse(
      AndroidScreenrecordSupport.interpret("ls: /system/bin/screenrecord: No such file or directory"),
    )
  }

  @Test
  fun `an empty answer means the binary is absent`() {
    assertFalse(AndroidScreenrecordSupport.interpret(""))
  }

  @Test
  fun `a binary kept somewhere other than system bin still counts`() {
    assertTrue(AndroidScreenrecordSupport.interpret("/vendor/bin/screenrecord"))
  }

  @Test
  fun `a probe that could not run leaves the device on the streaming path`() {
    // Biased toward yes: a flaky round trip must not downgrade a healthy device to the slow path.
    assertTrue(AndroidScreenrecordSupport.interpret(null))
  }

  @Test
  fun `the device is asked once, not once per session`() {
    var probes = 0
    val probe: (TrailblazeDeviceId) -> String? = { probes++; "/system/bin/screenrecord" }

    repeat(3) { assertTrue(AndroidScreenrecordSupport.isAvailable(device("serial-a"), probe)) }

    assertEquals(1, probes, "firmware does not grow a binary mid-run; one round trip is enough")
  }

  @Test
  fun `each device gets its own answer`() {
    val answers = mapOf("has-it" to "/system/bin/screenrecord", "lacks-it" to "")
    val probe: (TrailblazeDeviceId) -> String? = { answers[it.instanceId] }

    assertTrue(AndroidScreenrecordSupport.isAvailable(device("has-it"), probe))
    assertFalse(AndroidScreenrecordSupport.isAvailable(device("lacks-it"), probe))
  }
}
