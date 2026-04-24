package xyz.block.trailblaze.logs.client

import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.block.trailblaze.logs.client.TrailblazeSessionManager.Companion.generateSessionId
import xyz.block.trailblaze.logs.model.SessionId

class TrailblazeSessionManagerTest {

  private val manager = TrailblazeSessionManager(logEmitter = LogEmitter { })

  @Test
  fun `generateSessionId preserves long seeds without truncation`() {
    val longSeed = "a".repeat(200) + "__suite_1__section_2__case_3"
    val sessionId = generateSessionId(longSeed)

    assert(sessionId.value.contains("__suite_1__section_2__case_3")) {
      "TestRail-style suffix must survive generateSessionId; got: ${sessionId.value}"
    }
    assert(sessionId.value.length > 200) {
      "Expected un-truncated session id; got length ${sessionId.value.length}"
    }
  }

  @Test
  fun `createSessionWithId preserves long TestRail IDs without truncation`() {
    // Regression pin: the previous 100-char cap on externally-provided override
    // IDs also dropped TestRail suffixes. Now both paths go through the single
    // SessionId.sanitized source of truth.
    val longOverride = SessionId(
      "2026_04_20_11_16_18_example_suite_long_test_name_" +
        "verify_that_the_action_buttons_appear_for_active_items" +
        "__suite_1__section_2__case_3_1234",
    )

    val session = manager.createSessionWithId(longOverride)

    assertEquals(longOverride, session.sessionId)
  }

  @Test
  fun `createSessionWithId actually applies SessionId sanitized to the override`() {
    // The method's contract says the input "will be sanitized". Guard against
    // a future refactor that drops the sanitization call: pass an override with
    // characters that must be transformed, and assert the output is the
    // canonical sanitized form (not a pass-through of the original value).
    val unsanitizedOverride = SessionId("Test.ID-With.MIXED/Case 123")

    val session = manager.createSessionWithId(unsanitizedOverride)

    assertEquals(SessionId("test_id_with_mixed_case_123"), session.sessionId)
  }

  @Test
  fun `host-generated ID round-trips through createSessionWithId unchanged`() {
    // Regression guard: host generates an ID via generateSessionId and forwards it
    // to the on-device MCP handler as an override. The device feeds it back through
    // createSessionWithId -> SessionId.sanitized. Both sides must land on the same
    // session directory.
    val hostGenerated = generateSessionId(
      "example_suite_my_module_consolidated_tests_longButtonTest" +
        "__suite_123__section_456__case_789",
    )

    val deviceSession = manager.createSessionWithId(hostGenerated)

    assertEquals(hostGenerated, deviceSession.sessionId)
  }
}
