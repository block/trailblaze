package xyz.block.trailblaze.toolcalls

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool

/**
 * Pins the pure redaction contract behind [SensitiveArgsTrailblazeTool]: a payload's named
 * top-level keys are replaced with [REDACTED_TOOL_ARG_PLACEHOLDER] while everything else is
 * preserved byte-for-byte. The log-encode boundary (`toLogPayload()` and the
 * `TrailblazeToolLog` construction sites in `trailblaze-common`) builds on exactly this
 * function, so masking correctness is provable here without any logger wiring.
 */
class SensitiveArgsRedactionTest {

  private fun payload(vararg entries: Pair<String, String>): OtherTrailblazeTool = OtherTrailblazeTool(
    toolName = "fixture_tool",
    raw = buildJsonObject { entries.forEach { (k, v) -> put(k, v) } },
  )

  @Test
  fun masksOnlyTheNamedKeysAndPreservesTheRest() {
    val redacted = payload("password" to "hunter2", "email" to "qa@example.com")
      .withSensitiveArgsRedacted(setOf("password"))

    assertEquals(JsonPrimitive(REDACTED_TOOL_ARG_PLACEHOLDER), redacted.raw["password"])
    assertEquals(JsonPrimitive("qa@example.com"), redacted.raw["email"])
    assertEquals("fixture_tool", redacted.toolName)
  }

  @Test
  fun masksEveryNamedKeyThatIsPresent() {
    val redacted = payload("password" to "hunter2", "token" to "tok-123", "path" to "/tmp/x")
      .withSensitiveArgsRedacted(setOf("password", "token"))

    assertEquals(JsonPrimitive(REDACTED_TOOL_ARG_PLACEHOLDER), redacted.raw["password"])
    assertEquals(JsonPrimitive(REDACTED_TOOL_ARG_PLACEHOLDER), redacted.raw["token"])
    assertEquals(JsonPrimitive("/tmp/x"), redacted.raw["path"])
  }

  @Test
  fun absentKeysAreIgnoredAndTheSameInstanceIsReturned() {
    val original = payload("email" to "qa@example.com")
    assertSame(original, original.withSensitiveArgsRedacted(setOf("password")))
  }

  @Test
  fun emptyKeySetReturnsTheSameInstance() {
    val original = payload("password" to "hunter2")
    assertSame(original, original.withSensitiveArgsRedacted(emptySet()))
  }

  @Test
  fun emptyPayloadIsUntouched() {
    val original = OtherTrailblazeTool(toolName = "fixture_tool", raw = JsonObject(emptyMap()))
    assertSame(original, original.withSensitiveArgsRedacted(setOf("password")))
  }
}
