package xyz.block.trailblaze.exception

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * The message on this exception is what a person reads when a tool fails. The on-device runner
 * copies it onto `RunYamlResponse.errorMessage`, the host raises it out of `trailblaze tool`, and
 * the agent loop hands it to the LLM as the tool result — so anything but the tool's own sentence
 * here is a data-class dump in front of all three.
 */
class TrailblazeToolExecutionExceptionTest {

  /** Stands in for a ref-taking tool: its `toString()` is exactly what must not leak. */
  @Serializable
  private data class FakeTapTool(val ref: String) : TrailblazeTool

  private val tool = FakeTapTool(ref = "a1b2")
  private val staleRef = "tap: Element ref 'a1b2' not found on current screen."

  @Test
  fun `the message is the tool's own error text`() {
    val exception = TrailblazeToolExecutionException(
      tool = tool,
      trailblazeToolResult = TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = staleRef,
        command = tool,
      ),
    )

    assertEquals(staleRef, exception.message)
  }

  @Test
  fun `the result data class never renders into the message`() {
    val exception = TrailblazeToolExecutionException(
      tool = tool,
      trailblazeToolResult = TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = staleRef,
        command = tool,
        stackTrace = "at xyz.block.trailblaze.Whatever.run(Whatever.kt:1)",
      ),
    )

    val message = assertNotNull(exception.message)
    assertFalse(message.contains("ExceptionThrown("), message)
    assertFalse(message.contains("errorMessage="), message)
    assertFalse(message.contains("stackTrace="), message)
    assertFalse(message.contains("FakeTapTool("), message)
  }

  @Test
  fun `the structured result stays reachable for callers that want it`() {
    val result = TrailblazeToolResult.Error.ExceptionThrown(errorMessage = "boom", command = tool)

    val exception = TrailblazeToolExecutionException(tool = tool, trailblazeToolResult = result)

    assertEquals(result, exception.trailblazeToolResult)
    assertEquals(tool, exception.tool)
  }
}
