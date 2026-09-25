package xyz.block.trailblaze.toolcalls

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool

/**
 * Pins the pure redaction contract behind [SensitiveValuesTrailblazeTool]: every occurrence of a
 * declared value is masked at any depth of the payload, and everything else — including the
 * surrounding elements of the same array — is preserved. This is what lets one token inside a
 * shell argv be masked without blanking the command, which is the whole reason value-based
 * masking exists beside name-based [withSensitiveArgsRedacted].
 */
class SensitiveValuesRedactionTest {

  private val redacted = JsonPrimitive(REDACTED_TOOL_ARG_PLACEHOLDER)

  @Test
  fun masksOneElementOfAnArrayAndKeepsItsNeighbours() {
    val payload = OtherTrailblazeTool(
      toolName = "fixture_tool",
      raw = buildJsonObject {
        putJsonArray("command") { listOf("service", "call", "s16", "tok-abc123").forEach { add(JsonPrimitive(it)) } }
      },
    )

    val out = payload.withSensitiveValuesRedacted(listOf("tok-abc123"))

    assertEquals(
      buildJsonArray { listOf("service", "call", "s16").forEach { add(JsonPrimitive(it)) }; add(redacted) },
      out.raw["command"],
    )
  }

  @Test
  fun masksAtAnyDepthAndInsideLongerStrings() {
    val payload = OtherTrailblazeTool(
      toolName = "fixture_tool",
      raw = buildJsonObject {
        putJsonObject("nested") { put("header", "Bearer tok-abc123") }
        put("plain", "tok-abc123")
      },
    )

    val out = payload.withSensitiveValuesRedacted(listOf("tok-abc123"))

    assertEquals(JsonPrimitive("Bearer <redacted>"), out.raw["nested"]!!.jsonObject["header"])
    assertEquals(redacted, out.raw["plain"])
  }

  @Test
  fun neverTouchesKeysOrNonStringPrimitives() {
    val payload = OtherTrailblazeTool(
      toolName = "fixture_tool",
      raw = buildJsonObject { put("42", 42); put("flag", true) },
    )

    assertSame(payload, payload.withSensitiveValuesRedacted(listOf("42", "true")))
  }

  @Test
  fun returnsTheSameInstanceWhenNothingMatches() {
    val payload = OtherTrailblazeTool(toolName = "fixture_tool", raw = buildJsonObject { put("a", "b") })

    assertSame(payload, payload.withSensitiveValuesRedacted(listOf("zzz")))
    assertSame(payload, payload.withSensitiveValuesRedacted(emptyList()))
    // Blank values are ignored rather than shredding every string into placeholders.
    assertSame(payload, payload.withSensitiveValuesRedacted(listOf("", "  ")))
  }

  @Test
  fun longestValueWinsWhenOneSecretContainsAnother() {
    // Folding shortest-first would destroy the longer value's only literal occurrence and leave
    // `sess-<redacted>-abcd` in the log.
    assertEquals("v=<redacted>", redactSensitiveValuesIn("v=sess-1234-abcd", listOf("1234", "sess-1234-abcd")))
  }

  @Test
  fun replacesEveryOccurrence() {
    assertEquals("<redacted> <redacted>", redactSensitiveValuesIn("tok tok", listOf("tok")))
  }
}
