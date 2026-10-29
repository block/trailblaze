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
 * Which reserved session a run ends even when its setup failed before the session started.
 *
 * MCP reserves a session on the device before a call runs. A browser-launched configuration ends
 * its session on return, so a later call continuing it is refused; a reservation left behind by a
 * failed setup would get the caller's plain retry refused.
 */
class DesktopYamlRunnerReservedSessionEndTest {

  private val deviceId = jvmScopedDeviceId("test-device-reserved-session-end")
  private val reserved = SessionId("mcp_reserved_1234")

  @Test
  fun `a browser-launched MCP call ends the session it reserved`() {
    assertEquals(
      reserved,
      DesktopYamlRunner.reservedSessionEndedOnReturn(
        request(startsSession = true, sendsSessionEndLog = false),
        endsSession = true,
      ),
    )
  }

  @Test
  fun `a call that keeps its session leaves the reservation for the next call`() {
    assertNull(
      DesktopYamlRunner.reservedSessionEndedOnReturn(
        request(startsSession = true, sendsSessionEndLog = false),
        endsSession = false,
      ),
    )
  }

  @Test
  fun `a call continuing a session it did not open never ends it here`() {
    assertNull(
      DesktopYamlRunner.reservedSessionEndedOnReturn(
        request(startsSession = false, sendsSessionEndLog = false),
        endsSession = true,
      ),
    )
  }

  @Test
  fun `a run that sends its own session end is unchanged`() {
    assertNull(
      DesktopYamlRunner.reservedSessionEndedOnReturn(
        request(startsSession = true, sendsSessionEndLog = true),
        endsSession = true,
      ),
    )
  }

  private fun request(startsSession: Boolean, sendsSessionEndLog: Boolean) = RunYamlRequest(
    testName = "reserved-session-end",
    yaml = "- pressBack",
    trailFilePath = null,
    targetAppName = null,
    useRecordedSteps = true,
    trailblazeDeviceId = deviceId,
    trailblazeLlmModel = TrailblazeLlmModels.GPT_4O_MINI,
    config = TrailblazeConfig(
      overrideSessionId = reserved,
      sendSessionStartLog = startsSession,
      sendSessionEndLog = sendsSessionEndLog,
    ),
    referrer = TrailblazeReferrer.YAML_TAB,
  )
}
