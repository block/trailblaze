package xyz.block.trailblaze.report.models

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.block.trailblaze.logs.model.SessionId
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * [failureCodeOf] is the single owner of the payload → `failure_code` lifting rule shared
 * by both report paths (gradle CLI and daemon CLI). The rule is deliberately narrow: a
 * top-level STRING `code` member of a JSON-object payload, nothing else — the framework
 * lifts one field and never interprets the payload beyond that.
 */
class FailureCodeOfTest {

  @Test
  fun `lifts a top-level string code from an object payload`() {
    val payload = buildJsonObject {
      put("schema", "example-repo/trailhead-error/v1")
      put("code", "session")
      put("ticket", "TICKET-123")
    }
    assertEquals("session", failureCodeOf(payload))
  }

  @Test
  fun `null payload lifts nothing`() {
    assertEquals(null, failureCodeOf(null))
  }

  @Test
  fun `object payload without a code member lifts nothing`() {
    assertEquals(null, failureCodeOf(buildJsonObject { put("detail", "no code here") }))
  }

  @Test
  fun `non-string code lifts nothing`() {
    // A number or boolean `code` is a producer bug; lifting `"7"` would launder it into
    // the open string enum consumers match on.
    assertEquals(null, failureCodeOf(buildJsonObject { put("code", 7) }))
    assertEquals(null, failureCodeOf(buildJsonObject { put("code", true) }))
  }

  @Test
  fun `non-object payloads lift nothing`() {
    assertEquals(null, failureCodeOf(JsonPrimitive("session")))
    assertEquals(null, failureCodeOf(buildJsonArray { add(JsonPrimitive("session")) }))
  }

  @Test
  fun `payload-less rows keep their exact legacy JSON shape under encodeDefaults`() {
    // Both report writers encode with `encodeDefaults = true`. The new fields carry
    // `@EncodeDefault(NEVER)` so every row written today — no payload — stays
    // byte-identical: no explicit-null `failure_code` / `failure_payload` keys for
    // strict consumers that distinguish absent from null.
    val json = Json { encodeDefaults = true }
    val row = json.encodeToString(SessionResult.serializer(), sessionResult())
    assertFalse("failure_code" in row, row)
    assertFalse("failure_payload" in row, row)
  }

  @Test
  fun `rows with a payload serialize both fields`() {
    val json = Json { encodeDefaults = true }
    val payload = buildJsonObject { put("code", "session") }
    val row = json.encodeToString(
      SessionResult.serializer(),
      sessionResult().copy(failure_code = failureCodeOf(payload), failure_payload = payload),
    )
    assertContains(row, "\"failure_code\":\"session\"")
    assertContains(row, "\"failure_payload\":{\"code\":\"session\"}")
  }

  private fun sessionResult() = SessionResult(
    session_id = SessionId("test-session"),
    title = "test session",
    platform = "android",
    outcome = Outcome.FAILED,
    execution_mode = ExecutionMode.RECORDING_ONLY,
    trail_source = "trail.yaml",
    duration_ms = 1_000L,
  )
}
