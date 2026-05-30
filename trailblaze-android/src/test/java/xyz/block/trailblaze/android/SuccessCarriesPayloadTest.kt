package xyz.block.trailblaze.android

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the `carriesPayload()` predicate the `AndroidTrailblazeRule.runSuspend` trail-item fold
 * uses to decide whether a [TrailblazeToolResult.Success] should overwrite the trail's
 * `lastToolSuccess`. The bug this test guards against: an empty `Success()` produced by
 * `handleConfig(...)` or by a payload-less prompt step would clobber a previous tool's
 * `Success.message` / `Success.structuredContent`, surfacing `toolMessage=null` on
 * [xyz.block.trailblaze.llm.RunYamlResponse] for trails like `[adbShell→stdout, configBlock]`
 * (Codex P2 finding on PR #3507).
 *
 * Driving the rule's full `runSuspend` from a JVM unit test would require standing up the
 * Android instrumentation harness — much heavier than the pure predicate warrants. The fold
 * logic is one line in the rule that delegates to this helper; pinning the helper here covers
 * the regression vector without the harness overhead.
 */
class SuccessCarriesPayloadTest {

  @Test
  fun `empty Success does not carry payload`() {
    // The default-arg constructor produces `Success(message=null, structuredContent=null)` —
    // the shape returned by action-style tools (tap/swipe) and by `handleConfig(...)`. These
    // must not overwrite a payload-bearing Success in the trail-item fold.
    assertFalse(TrailblazeToolResult.Success().carriesPayload())
  }

  @Test
  fun `Success with non-null message carries payload`() {
    assertTrue(TrailblazeToolResult.Success(message = "stdout-from-adbShell").carriesPayload())
  }

  @Test
  fun `Success with non-null structuredContent carries payload`() {
    val structured = JsonObject(mapOf("appIds" to JsonPrimitive("com.example")))
    assertTrue(TrailblazeToolResult.Success(structuredContent = structured).carriesPayload())
  }

  @Test
  fun `Success with both message and structuredContent carries payload`() {
    val structured = JsonObject(mapOf("k" to JsonPrimitive(1)))
    assertTrue(
      TrailblazeToolResult.Success(
        message = "hello",
        structuredContent = structured,
      ).carriesPayload(),
    )
  }

  @Test
  fun `Success with empty-string message still carries payload`() {
    // Empty string is a deliberate value the producer chose — not the same as null. A tool
    // whose contract is "return stdout" and stdout happens to be empty must still mirror its
    // empty message onto `RunYamlResponse.toolMessage` rather than being treated as no-result.
    assertTrue(TrailblazeToolResult.Success(message = "").carriesPayload())
  }
}
