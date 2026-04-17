package xyz.block.trailblaze.revyl

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import org.junit.Test
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType

class RevylModelTest {

  // ---------------------------------------------------------------------------
  // RevylActionResult parsing
  // ---------------------------------------------------------------------------

  @Test
  fun `fromJson parses a tap result`() {
    val json = """{"action":"tap","x":540,"y":1200,"target":"Sign In button","success":true}"""
    val result = RevylActionResult.fromJson(json)
    assertThat(result.action).isEqualTo("tap")
    assertThat(result.x).isEqualTo(540)
    assertThat(result.y).isEqualTo(1200)
    assertThat(result.target).isEqualTo("Sign In button")
    assertThat(result.success).isTrue()
  }

  @Test
  fun `fromJson returns failure for invalid JSON`() {
    val result = RevylActionResult.fromJson("not json")
    assertThat(result.success).isFalse()
    assertThat(result.x).isEqualTo(0)
    assertThat(result.y).isEqualTo(0)
  }

  @Test
  fun `fromJson handles empty string`() {
    val result = RevylActionResult.fromJson("")
    assertThat(result.success).isFalse()
  }

  // ---------------------------------------------------------------------------
  // RevylLiveStepResult parsing
  // ---------------------------------------------------------------------------

  @Test
  fun `live step result parses validation success`() {
    val json = """{"success":true,"step_type":"validation","step_id":"abc-123","step_output":{"status_reason":"Element is visible"}}"""
    val result = RevylLiveStepResult.fromJson(json)
    assertThat(result.success).isTrue()
    assertThat(result.stepType).isEqualTo("validation")
    assertThat(result.stepId).isEqualTo("abc-123")
    assertThat(result.statusReason).isEqualTo("Element is visible")
  }

  @Test
  fun `live step result returns failure for invalid JSON`() {
    val result = RevylLiveStepResult.fromJson("garbage")
    assertThat(result.success).isFalse()
  }

  @Test
  fun `live step result statusReason is null when step_output is null`() {
    val json = """{"success":true,"step_type":"instruction","step_id":"x"}"""
    val result = RevylLiveStepResult.fromJson(json)
    assertThat(result.statusReason).isNull()
  }

  // ---------------------------------------------------------------------------
  // RevylSession platform mapping
  // ---------------------------------------------------------------------------

  @Test
  fun `session maps ios platform to driver type`() {
    val session = RevylSession(
      index = 0, sessionId = "s1", workflowRunId = "w1",
      workerBaseUrl = "https://worker.test", viewerUrl = "https://viewer.test",
      platform = "ios",
    )
    assertThat(session.toDriverType()).isEqualTo(TrailblazeDriverType.REVYL_IOS)
    assertThat(session.toDevicePlatform()).isEqualTo(TrailblazeDevicePlatform.IOS)
  }

  @Test
  fun `session maps android platform to driver type`() {
    val session = RevylSession(
      index = 1, sessionId = "s2", workflowRunId = "w2",
      workerBaseUrl = "https://worker.test", viewerUrl = "https://viewer.test",
      platform = "android",
    )
    assertThat(session.toDriverType()).isEqualTo(TrailblazeDriverType.REVYL_ANDROID)
    assertThat(session.toDevicePlatform()).isEqualTo(TrailblazeDevicePlatform.ANDROID)
  }

  // ---------------------------------------------------------------------------
  // RevylDefaults
  // ---------------------------------------------------------------------------

  @Test
  fun `defaults returns iOS dimensions for ios platform`() {
    val (w, h) = RevylDefaults.dimensionsForPlatform("ios")
    assertThat(w).isEqualTo(RevylDefaults.IOS_DEFAULT_WIDTH)
    assertThat(h).isEqualTo(RevylDefaults.IOS_DEFAULT_HEIGHT)
  }

  @Test
  fun `defaults returns Android dimensions for android platform`() {
    val (w, h) = RevylDefaults.dimensionsForPlatform("android")
    assertThat(w).isEqualTo(RevylDefaults.ANDROID_DEFAULT_WIDTH)
    assertThat(h).isEqualTo(RevylDefaults.ANDROID_DEFAULT_HEIGHT)
  }

  @Test
  fun `defaults falls back to Android for unknown platform`() {
    val (w, h) = RevylDefaults.dimensionsForPlatform("web")
    assertThat(w).isEqualTo(RevylDefaults.ANDROID_DEFAULT_WIDTH)
    assertThat(h).isEqualTo(RevylDefaults.ANDROID_DEFAULT_HEIGHT)
  }
}
