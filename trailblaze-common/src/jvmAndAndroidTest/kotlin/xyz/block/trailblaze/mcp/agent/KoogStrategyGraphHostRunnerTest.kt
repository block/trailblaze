package xyz.block.trailblaze.mcp.agent

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.commands.Status
import kotlin.test.Test

/**
 * Unit tests for [resolveKoogObjectiveResult] — the pure mapping from the Koog agent's terminal
 * `objectiveStatus` outcome to a [TrailblazeToolResult]. Exercises every branch without standing up
 * the Koog graph.
 */
class KoogStrategyGraphHostRunnerTest {

  @Test
  fun `COMPLETED maps to Success carrying the explanation`() {
    val result = resolveKoogObjectiveResult(
      outcome = Status.COMPLETED,
      explanation = "All goals met",
      finalMessage = "final",
    )
    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
    assertThat((result as TrailblazeToolResult.Success).message).isEqualTo("All goals met")
  }

  @Test
  fun `IN_PROGRESS maps to Success`() {
    val result = resolveKoogObjectiveResult(
      outcome = Status.IN_PROGRESS,
      explanation = "still going",
      finalMessage = "final",
    )
    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
  }

  @Test
  fun `FAILED maps to an error mentioning the explanation`() {
    val result = resolveKoogObjectiveResult(
      outcome = Status.FAILED,
      explanation = "could not find the button",
      finalMessage = "final",
    )
    assertThat(result).isInstanceOf(TrailblazeToolResult.Error.ExceptionThrown::class)
    assertThat((result as TrailblazeToolResult.Error.ExceptionThrown).errorMessage)
      .contains("could not find the button")
  }

  @Test
  fun `null outcome (finished without objectiveStatus) maps to an error, not a hollow pass`() {
    val result = resolveKoogObjectiveResult(
      outcome = null,
      explanation = null,
      finalMessage = "agent stopped here",
    )
    assertThat(result).isInstanceOf(TrailblazeToolResult.Error.ExceptionThrown::class)
    // Surfaces the agent's final message so the failure is debuggable.
    assertThat((result as TrailblazeToolResult.Error.ExceptionThrown).errorMessage)
      .contains("agent stopped here")
  }

  @Test
  fun `null explanation falls back to the final message`() {
    val result = resolveKoogObjectiveResult(
      outcome = Status.COMPLETED,
      explanation = null,
      finalMessage = "fell back to this",
    )
    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
    assertThat((result as TrailblazeToolResult.Success).message).isEqualTo("fell back to this")
  }
}
