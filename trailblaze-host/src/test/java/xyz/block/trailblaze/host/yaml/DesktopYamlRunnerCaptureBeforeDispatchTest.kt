package xyz.block.trailblaze.host.yaml

import xyz.block.trailblaze.host.jvmScopedDeviceId
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.llm.TrailblazeLlmModels
import xyz.block.trailblaze.llm.TrailblazeReferrer
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.model.TrailblazeConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * When a whole-trail run can start its session capture.
 *
 * A synchronous request's reply arrives after the trail, so capture started from the reply records
 * nothing. [DesktopYamlRunner.sessionIdToCaptureBeforeDispatch] names the id to start capture under
 * before the request goes out — only when the request pins the id the device will create.
 */
class DesktopYamlRunnerCaptureBeforeDispatchTest {

  private val deviceId = jvmScopedDeviceId("test-device-capture-before-dispatch")

  @Test
  fun `a synchronous run with a pinned session starts capture under that id before dispatch`() {
    val pinned = SessionId("pinned_trail_1234")

    assertEquals(pinned, DesktopYamlRunner.sessionIdToCaptureBeforeDispatch(request(pinned, awaitCompletion = true)))
  }

  @Test
  fun `an unpinned run waits for the reply, which is the only place its id exists`() {
    assertNull(DesktopYamlRunner.sessionIdToCaptureBeforeDispatch(request(null, awaitCompletion = true)))
  }

  @Test
  fun `a fire-and-forget run waits for the reply, which arrives before the trail runs`() {
    assertNull(
      DesktopYamlRunner.sessionIdToCaptureBeforeDispatch(
        request(SessionId("pinned_trail_1234"), awaitCompletion = false),
      ),
    )
  }

  private fun request(pinned: SessionId?, awaitCompletion: Boolean) = RunYamlRequest(
    testName = "capture-before-dispatch",
    yaml = "- pressBack",
    trailFilePath = null,
    targetAppName = null,
    useRecordedSteps = true,
    trailblazeDeviceId = deviceId,
    trailblazeLlmModel = TrailblazeLlmModels.GPT_4O_MINI,
    config = TrailblazeConfig(overrideSessionId = pinned),
    referrer = TrailblazeReferrer.YAML_TAB,
    awaitCompletion = awaitCompletion,
  )
}
