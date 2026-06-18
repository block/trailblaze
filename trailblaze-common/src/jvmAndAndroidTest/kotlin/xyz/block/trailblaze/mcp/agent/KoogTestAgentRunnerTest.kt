package xyz.block.trailblaze.mcp.agent

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import kotlinx.datetime.Instant
import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.agent.model.AgentTaskStatusData
import xyz.block.trailblaze.logs.model.TaskId
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import kotlin.test.Test

/**
 * Unit tests for [toKoogAgentTaskStatus] — the pure mapping from a step's [TrailblazeToolResult] to an
 * [AgentTaskStatus] that lets KOOG plug into the per-step [xyz.block.trailblaze.api.TestAgentRunner] loop.
 */
class KoogTestAgentRunnerTest {

  private fun statusData() = AgentTaskStatusData(
    taskId = TaskId.generate(),
    prompt = "do the thing",
    callCount = 0,
    taskStartTime = Instant.fromEpochMilliseconds(0L),
    totalDurationMs = 0L,
  )

  @Test
  fun `Success maps to ObjectiveComplete carrying the message`() {
    val status = TrailblazeToolResult.Success(message = "done").toKoogAgentTaskStatus(statusData())
    assertThat(status).isInstanceOf(AgentTaskStatus.Success.ObjectiveComplete::class)
    assertThat((status as AgentTaskStatus.Success.ObjectiveComplete).llmExplanation).isEqualTo("done")
  }

  @Test
  fun `Success with null message falls back to a default explanation`() {
    val status = TrailblazeToolResult.Success(message = null).toKoogAgentTaskStatus(statusData())
    assertThat(status).isInstanceOf(AgentTaskStatus.Success.ObjectiveComplete::class)
  }

  @Test
  fun `Error maps to ObjectiveFailed carrying the error message`() {
    val status = TrailblazeToolResult.Error.ExceptionThrown(errorMessage = "boom")
      .toKoogAgentTaskStatus(statusData())
    assertThat(status).isInstanceOf(AgentTaskStatus.Failure.ObjectiveFailed::class)
    assertThat((status as AgentTaskStatus.Failure.ObjectiveFailed).llmExplanation).contains("boom")
  }
}
