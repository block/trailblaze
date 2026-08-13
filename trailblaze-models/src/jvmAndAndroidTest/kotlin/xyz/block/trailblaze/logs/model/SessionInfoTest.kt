package xyz.block.trailblaze.logs.model

import kotlinx.datetime.Instant
import xyz.block.trailblaze.yaml.TrailConfig
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
    trailFilePath: String? = null,
    trailConfig: TrailConfig? = null,
  ): SessionInfo =
    SessionInfo(
      sessionId = SessionId(sessionId),
      latestStatus = SessionStatus.Unknown,
      timestamp = Instant.fromEpochMilliseconds(0),
      durationMs = 0,
      trailFilePath = trailFilePath,
      hasRecordedSteps = false,
      testName = testName,
      testClass = testClass,
      trailConfig = trailConfig,
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

  // --- trailFilePath shortening (the CLI absolute-path regression) -----------------------

  @Test
  fun `displayName shortens an absolute trailFilePath to its trails-relative name`() {
    // A trail run via the CLI records file.absolutePath, which doesn't start with "trails/".
    // Before the fix this leaked the whole filesystem path into the Sessions list.
    val si = info(
      trailFilePath =
        "/var/ci/workspace/checkout/src/test/resources/trails/ExperimentalIosTests/" +
          "set_feature_flag.trail.yaml",
    )
    assertEquals("ExperimentalIosTests/set_feature_flag", si.displayName)
  }

  @Test
  fun `displayName shortens a relative trails-prefixed path`() {
    val si = info(trailFilePath = "trails/EvaluationLongTest/tenKey.trail.yaml")
    assertEquals("EvaluationLongTest/tenKey", si.displayName)
  }

  @Test
  fun `displayName prefers an explicit title over the trail path`() {
    val si = info(
      trailFilePath = "/abs/trails/ExperimentalIosTests/set_feature_flag.trail.yaml",
      trailConfig = TrailConfig(title = "Set Feature Flag"),
    )
    assertEquals("Set Feature Flag", si.displayName)
  }

  @Test
  fun `stableTestKey shortens an absolute trailFilePath`() {
    // Retries must group by a stable key, not the machine-specific absolute path.
    val si = info(
      trailFilePath = "/abs/path/trails/ExperimentalIosTests/set_feature_flag.trail.yaml",
    )
    assertEquals("ExperimentalIosTests/set_feature_flag", si.stableTestKey)
  }

  @Test
  fun `displayName ignores a blank trailFilePath and falls through`() {
    // A blank (non-null) path must not short-circuit displayName to an empty string — it
    // should fall through to testName, matching the takeIf guard the other tiers use.
    val si = info(trailFilePath = "   ", testName = "fallback test")
    assertEquals("fallback test", si.displayName)
  }

  // --- blank author-supplied tiers ---------------------------------------------------------
  // `trailConfig.title` / `trailConfig.id` come straight from YAML, where `id:` with nothing
  // after it parses to an empty string rather than to null. They were the only tiers in either
  // chain without the blank guard every derived tier already had.

  @Test
  fun `stableTestKey ignores a blank trail id and falls through`() {
    val si = info(
      trailConfig = TrailConfig(id = "   "),
      trailFilePath = "trails/EvaluationLongTest/tenKey.trail.yaml",
    )
    assertEquals("EvaluationLongTest/tenKey", si.stableTestKey)
  }

  @Test
  fun `displayName ignores a blank trail title and falls through to the id`() {
    val si = info(trailConfig = TrailConfig(title = "  ", id = "sample-app/taps/simple-tap"))
    assertEquals("sample-app/taps/simple-tap", si.displayName)
  }

  @Test
  fun `stableTestKey is never blank, whatever the session carries`() {
    // The invariant the whole key rests on, and the only one whose failure is silent.
    // Retries are grouped on this value by both the report generator (`test_key ?: title`) and
    // the step summary (jq `.test_key // .title`), and BOTH treat "" as a present value — so a
    // blank key does not fall back, it groups. Every session that produced one would collapse
    // into a single result and the losers' verdicts would disappear from the count.
    //
    // Enumerated over the blank spellings and over which tiers are present, rather than written
    // out per case, so a tier that loses its guard later is caught here even if nobody thought
    // to add a case for it.
    val blanks = listOf("", " ", "   ", "\t", "\n")
    for (blank in blanks) {
      val candidates = listOf(
        info(trailConfig = TrailConfig(id = blank)),
        info(trailConfig = TrailConfig(title = blank)),
        info(trailConfig = TrailConfig(title = blank, id = blank)),
        info(trailConfig = TrailConfig(title = blank, id = blank), trailFilePath = blank),
        info(
          trailConfig = TrailConfig(title = blank, id = blank),
          trailFilePath = blank,
          testName = blank,
          testClass = blank,
        ),
      )
      candidates.forEach { si ->
        assertEquals(
          false,
          si.stableTestKey.isBlank(),
          "stableTestKey resolved to a blank string for a session whose tiers were [$blank]",
        )
      }
    }
  }

  @Test
  fun `a session with nothing but blanks keys on its session id`() {
    val si = info(
      sessionId = "session_only_identity",
      trailConfig = TrailConfig(title = "", id = ""),
      trailFilePath = "",
      testName = "",
      testClass = "",
    )
    assertEquals("session_only_identity", si.stableTestKey)
    assertEquals("session_only_identity", si.displayName)
  }

  @Test
  fun `two blank-tier sessions do not share a key`() {
    // The consequence stated as behavior rather than as a property: before the guard both of
    // these keyed on "" and grouped together, which is one result where two tests ran.
    val first = info(sessionId = "session_one", trailConfig = TrailConfig(id = ""))
    val second = info(sessionId = "session_two", trailConfig = TrailConfig(id = ""))
    assertEquals(
      false,
      first.stableTestKey == second.stableTestKey,
      "two unrelated sessions with blank ids collapsed onto the same key",
    )
  }
}
