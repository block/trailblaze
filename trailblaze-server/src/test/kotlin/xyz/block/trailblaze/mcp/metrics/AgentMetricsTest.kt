package xyz.block.trailblaze.mcp.metrics

import org.junit.Before
import org.junit.Test
import xyz.block.trailblaze.mcp.LlmCallStrategy
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [AgentMetricsCollector].
 */
class AgentMetricsTest {

  private lateinit var collector: AgentMetricsCollector

  @Before
  fun setup() {
    collector = AgentMetricsCollector()
  }

  // region recordSuccess tests

  @Test
  fun `recordSuccess increments counters`() {
    collector.recordSuccess(
      llmCallStrategy = LlmCallStrategy.DIRECT,
      objective = "Test objective",
      iterations = 5,
      durationMs = 1000,
    )

    val stats = collector.getStats(LlmCallStrategy.DIRECT)
    assertEquals(1, stats.totalExecutions)
    assertEquals(1, stats.successCount)
    assertEquals(0, stats.failureCount)
    assertEquals(0, stats.errorCount)
    assertEquals(1.0, stats.successRate)
  }

  @Test
  fun `recordSuccess tracks iterations and duration`() {
    collector.recordSuccess(LlmCallStrategy.DIRECT, "Test 1", iterations = 3, durationMs = 500)
    collector.recordSuccess(LlmCallStrategy.DIRECT, "Test 2", iterations = 7, durationMs = 1500)

    val stats = collector.getStats(LlmCallStrategy.DIRECT)
    assertEquals(5.0, stats.avgIterations) // (3+7)/2
    assertEquals(1000.0, stats.avgDurationMs) // (500+1500)/2
    assertEquals(500, stats.minDurationMs)
    assertEquals(1500, stats.maxDurationMs)
  }

  // endregion

  // region recordFailure tests

  @Test
  fun `recordFailure increments failure counter`() {
    collector.recordFailure(
      llmCallStrategy = LlmCallStrategy.MCP_SAMPLING,
      objective = "Failed objective",
      iterations = 10,
      durationMs = 2000,
      reason = "Element not found",
    )

    val stats = collector.getStats(LlmCallStrategy.MCP_SAMPLING)
    assertEquals(1, stats.totalExecutions)
    assertEquals(0, stats.successCount)
    assertEquals(1, stats.failureCount)
    assertEquals(0, stats.errorCount)
    assertEquals(0.0, stats.successRate)
  }

  // endregion

  // region recordError tests

  @Test
  fun `recordError increments error counter`() {
    collector.recordError(
      llmCallStrategy = LlmCallStrategy.DIRECT,
      objective = "Error objective",
      durationMs = 100,
      errorMessage = "Sampling failed",
    )

    val stats = collector.getStats(LlmCallStrategy.DIRECT)
    assertEquals(1, stats.totalExecutions)
    assertEquals(0, stats.successCount)
    assertEquals(0, stats.failureCount)
    assertEquals(1, stats.errorCount)
    assertEquals(0.0, stats.successRate)
  }

  // endregion

  // region getAllStats tests

  @Test
  fun `getAllStats returns stats for all agents with data`() {
    collector.recordSuccess(LlmCallStrategy.DIRECT, "Koog test", 5, 1000)
    collector.recordSuccess(LlmCallStrategy.MCP_SAMPLING, "Legacy test", 3, 800)

    val allStats = collector.getAllStats()

    assertEquals(2, allStats.size)
    assertTrue(allStats.any { it.agentImplementation == "DIRECT" })
    assertTrue(allStats.any { it.agentImplementation == "MCP_SAMPLING" })
  }

  @Test
  fun `getAllStats includes all LLM call strategies`() {
    collector.recordSuccess(LlmCallStrategy.DIRECT, "Test", 1, 100)

    val allStats = collector.getAllStats()

    assertTrue(allStats.any { it.agentImplementation == "DIRECT" })
  }

  @Test
  fun `getAllStats excludes agents with no data`() {
    collector.recordSuccess(LlmCallStrategy.DIRECT, "Test", 1, 100)

    val allStats = collector.getAllStats()

    assertEquals(1, allStats.size)
    assertEquals("DIRECT", allStats[0].agentImplementation)
  }

  // endregion

  // region getRecentMetrics tests

  @Test
  fun `getRecentMetrics returns limited results`() {
    repeat(15) { i ->
      collector.recordSuccess(LlmCallStrategy.DIRECT, "Test $i", 1, 100)
    }

    val recent = collector.getRecentMetrics(limit = 5)

    assertEquals(1, recent.size) // Only DIRECT
    assertEquals(5, recent["DIRECT"]?.size)
  }

  // endregion

  // region clear tests

  @Test
  fun `clear removes all data`() {
    collector.recordSuccess(LlmCallStrategy.DIRECT, "Test 1", 1, 100)
    collector.recordFailure(LlmCallStrategy.MCP_SAMPLING, "Test 2", 2, 200, "Failed")

    collector.clear()

    val allStats = collector.getAllStats()
    assertTrue(allStats.isEmpty())
  }

  // endregion

  // region describeSummary tests

  @Test
  fun `describeSummary returns formatted output`() {
    collector.recordSuccess(LlmCallStrategy.DIRECT, "Test 1", 5, 1000)
    collector.recordSuccess(LlmCallStrategy.DIRECT, "Test 2", 3, 800)
    collector.recordFailure(LlmCallStrategy.DIRECT, "Test 3", 10, 2000, "Failed")

    val summary = collector.describeSummary()

    assertTrue(summary.contains("DIRECT"))
    assertTrue(summary.contains("Total executions: 3"))
    assertTrue(summary.contains("Success rate:"))
    assertTrue(summary.contains("Avg iterations:"))
  }

  @Test
  fun `describeSummary shows comparison when both agents have data`() {
    collector.recordSuccess(LlmCallStrategy.DIRECT, "Test", 5, 1000)
    collector.recordSuccess(LlmCallStrategy.MCP_SAMPLING, "Test", 3, 800)

    val summary = collector.describeSummary()

    assertTrue(summary.contains("Comparison (DIRECT vs MCP_SAMPLING)"))
  }

  @Test
  fun `describeSummary handles no data gracefully`() {
    val summary = collector.describeSummary()

    assertTrue(summary.contains("No metrics collected yet"))
  }

  // endregion

  // region success rate calculation tests

  @Test
  fun `success rate calculated correctly with mixed results`() {
    collector.recordSuccess(LlmCallStrategy.DIRECT, "Success 1", 1, 100)
    collector.recordSuccess(LlmCallStrategy.DIRECT, "Success 2", 1, 100)
    collector.recordFailure(LlmCallStrategy.DIRECT, "Failure 1", 1, 100, "Failed")
    collector.recordError(LlmCallStrategy.DIRECT, "Error 1", 100, "Error")

    val stats = collector.getStats(LlmCallStrategy.DIRECT)

    assertEquals(4, stats.totalExecutions)
    assertEquals(2, stats.successCount)
    assertEquals(1, stats.failureCount)
    assertEquals(1, stats.errorCount)
    assertEquals(0.5, stats.successRate) // 2 successes out of 4 total
  }

  // endregion
}
