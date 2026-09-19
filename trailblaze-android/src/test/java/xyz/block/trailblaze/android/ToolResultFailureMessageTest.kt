package xyz.block.trailblaze.android

import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.commands.TapTrailblazeTool
import xyz.block.trailblaze.toolcalls.failureMessage
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Pins what `AndroidTrailblazeRule.tool` / `maestroCommands` raise when a tool fails: the tool's
 * own error text. The exception message travels verbatim to the host as
 * `RunYamlResponse.errorMessage`, and from there into `trailblaze tool` output and the LLM's
 * tool result — so a `toString()` of the result class there put
 * `ExceptionThrown(errorMessage=…, command=TapTrailblazeTool(…), stackTrace=null, …)` in front
 * of every CLI user hitting a stale ref.
 */
class ToolResultFailureMessageTest {

  @Test
  fun `an ExceptionThrown failure surfaces only its error message`() {
    val result = TrailblazeToolResult.Error.ExceptionThrown(
      errorMessage = "tap: Element ref 'a1b2' not found on current screen.",
      command = TapTrailblazeTool(ref = "a1b2"),
    )

    assertEquals("tap: Element ref 'a1b2' not found on current screen.", result.failureMessage())
  }

  @Test
  fun `the data class dump never leaks into the failure message`() {
    val result: TrailblazeToolResult = TrailblazeToolResult.Error.MaestroValidationError(
      errorMessage = "tapOn needs a selector",
      commandJsonObject = JsonObject(emptyMap()),
    )

    val message = result.failureMessage()
    assertEquals("tapOn needs a selector", message)
    assertFalse(message.contains("errorMessage="), message)
    assertFalse(message.contains("commandJsonObject="), message)
  }
}
