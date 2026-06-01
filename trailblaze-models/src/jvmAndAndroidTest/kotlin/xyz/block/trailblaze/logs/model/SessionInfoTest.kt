package xyz.block.trailblaze.logs.model

import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Coverage for [SessionInfo.displayName] resolution — especially the MCP-marker
 * suppression branch that drops the `testClass:testName` prefix when
 * `testClass` is [MCP_TEST_CLASS_NAME]. Without this, the user-facing
 * `trailblaze session list` would render every CLI-initiated session as
 * "MCP:Capture screen state" / "MCP:What's on screen?" etc.
 */
class SessionInfoTest {

  private fun info(
    testClass: String? = null,
    testName: String? = null,
    sessionId: String = "test_session_42",
  ): SessionInfo =
    SessionInfo(
      sessionId = SessionId(sessionId),
      latestStatus = SessionStatus.Unknown,
      timestamp = Instant.fromEpochMilliseconds(0),
      durationMs = 0,
      trailFilePath = null,
      hasRecordedSteps = false,
      testName = testName,
      testClass = testClass,
    )

  @Test
  fun `displayName drops MCP prefix when testClass is the MCP sentinel`() {
    val si = info(testClass = MCP_TEST_CLASS_NAME, testName = "Capture screen state")
    assertEquals("Capture screen state", si.displayName)
  }

  @Test
  fun `displayName keeps real testClass prefix`() {
    val si = info(testClass = "LoginFlowTest", testName = "tapsSignIn")
    assertEquals("LoginFlowTest:tapsSignIn", si.displayName)
  }

  @Test
  fun `displayName tolerates lowercase mcp`() {
    // Defensive: a future producer typo or wire-format drift writing "mcp"
    // (or "MCP " with a stray space) shouldn't defeat the suppression.
    val si = info(testClass = "mcp", testName = "Capture screen state")
    assertEquals("Capture screen state", si.displayName)
  }

  @Test
  fun `displayName tolerates surrounding whitespace on the MCP marker`() {
    val si = info(testClass = "  MCP  ", testName = "Capture screen state")
    assertEquals("Capture screen state", si.displayName)
  }

  @Test
  fun `displayName falls back to sessionId when MCP marker and testName both absent`() {
    val si = info(testClass = MCP_TEST_CLASS_NAME, testName = null, sessionId = "session_abc")
    assertEquals("session_abc", si.displayName)
  }

  @Test
  fun `displayName falls back to testClass alone when testName is blank and class is not MCP`() {
    val si = info(testClass = "SomeTest", testName = null)
    assertEquals("SomeTest", si.displayName)
  }

  @Test
  fun `displayName uses just testName when testClass is null`() {
    val si = info(testClass = null, testName = "lonely test")
    assertEquals("lonely test", si.displayName)
  }
}
