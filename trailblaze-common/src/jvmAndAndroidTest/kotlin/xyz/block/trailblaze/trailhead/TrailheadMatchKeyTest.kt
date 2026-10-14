package xyz.block.trailblaze.trailhead

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class TrailheadMatchKeyTest {

  private fun key(pin: String = "1234", persona: String = "merchant", codeVersion: String = "v1") = TrailheadMatchKey(
    toolName = "signIn",
    codeVersion = codeVersion,
    argDigests = TrailheadMatchKey.digestArgs(buildJsonObject { put("persona", persona); put("pin", pin) }),
    deviceId = "ANDROID:emulator-5554",
    deviceSettings = "driver=x;locale=en;app=a",
  )

  @Test
  fun `identical args match whatever their key order`() {
    val reordered = TrailheadMatchKey.digestArgs(buildJsonObject { put("pin", "1234"); put("persona", "merchant") })
    assertEquals(key().argDigests, reordered)
    assertNull(key().mismatchReason(key()))
  }

  @Test
  fun `a mismatch names the differing args and never their values`() {
    val reason = key().mismatchReason(key(pin = "9999"))!!
    assertEquals("args differed: pin", reason)
    assertEquals("the trailhead's code changed", key().mismatchReason(key(codeVersion = "v2")))
  }

  @Test
  fun `digests do not contain the arg value`() {
    val digests = key().argDigests.values
    assertFalse(digests.any { it.contains("1234") })
    assertTrue(digests.all { it.length == 64 })
  }
}
