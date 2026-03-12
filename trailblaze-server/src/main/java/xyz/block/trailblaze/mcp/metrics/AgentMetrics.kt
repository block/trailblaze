package xyz.block.trailblaze.mcp.metrics

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.mcp.LlmCallStrategy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Tracks metrics for agent execution to compare DIRECT vs MCP_SAMPLING performance.
 *
 * This enables gradual rollout monitoring by collecting:
 * - Success/failure rates per agent implementation
 * - Average iterations per objective
 * - Execution time statistics
 */
@Serializable
data class AgentExecutionMetric(
  val agentImplementation: String,
  val objective: String,
  val success: Boolean,
  val iterations: Int,
  val durationMs: Long,
  val errorMessage: String? = null,
  val timestamp: Long = System.currentTimeMillis(),
)

/**
 * Aggregated statistics for an agent implementation.
 */
@Serializable
data class AgentStats(
  val agentImplementation: String,
  val totalExecutions: Int,
  val successCount: Int,
  val failureCount: Int,
  val errorCount: Int,
  val successRate: Double,
  val avgIterations: Double,
  val avgDurationMs: Double,
  val minDurationMs: Long,
  val maxDurationMs: Long,
)

/**
 * Collects and aggregates agent execution metrics.
 *
 * Thread-safe implementation for concurrent access from multiple sessions.
 */
class AgentMetricsCollector {

  private val metrics = ConcurrentHashMap<LlmCallStrategy, MutableList<AgentExecutionMetric>>()

  // Counters for quick stats without iterating
  private val executionCounts = ConcurrentHashMap<LlmCallStrategy, AtomicInteger>()
  private val successCounts = ConcurrentHashMap<LlmCallStrategy, AtomicInteger>()
  private val failureCounts = ConcurrentHashMap<LlmCallStrategy, AtomicInteger>()
  private val errorCounts = ConcurrentHashMap<LlmCallStrategy, AtomicInteger>()
  private val totalIterations = ConcurrentHashMap<LlmCallStrategy, AtomicLong>()
  private val totalDurationMs = ConcurrentHashMap<LlmCallStrategy, AtomicLong>()

  /**
   * Records a successful agent execution.
   */
  fun recordSuccess(
    llmCallStrategy: LlmCallStrategy,
    objective: String,
    iterations: Int,
    durationMs: Long,
  ) {
    val metric = AgentExecutionMetric(
      agentImplementation = llmCallStrategy.name,
      objective = objective,
      success = true,
      iterations = iterations,
      durationMs = durationMs,
    )
    addMetric(llmCallStrategy, metric)
    incrementCounter(executionCounts, llmCallStrategy)
    incrementCounter(successCounts, llmCallStrategy)
    addToCounter(totalIterations, llmCallStrategy, iterations.toLong())
    addToCounter(totalDurationMs, llmCallStrategy, durationMs)
  }

  /**
   * Records a failed agent execution (LLM determined failure).
   */
  fun recordFailure(
    llmCallStrategy: LlmCallStrategy,
    objective: String,
    iterations: Int,
    durationMs: Long,
    reason: String,
  ) {
    val metric = AgentExecutionMetric(
      agentImplementation = llmCallStrategy.name,
      objective = objective,
      success = false,
      iterations = iterations,
      durationMs = durationMs,
      errorMessage = reason,
    )
    addMetric(llmCallStrategy, metric)
    incrementCounter(executionCounts, llmCallStrategy)
    incrementCounter(failureCounts, llmCallStrategy)
    addToCounter(totalIterations, llmCallStrategy, iterations.toLong())
    addToCounter(totalDurationMs, llmCallStrategy, durationMs)
  }

  /**
   * Records an error during agent execution.
   */
  fun recordError(
    llmCallStrategy: LlmCallStrategy,
    objective: String,
    durationMs: Long,
    errorMessage: String,
  ) {
    val metric = AgentExecutionMetric(
      agentImplementation = llmCallStrategy.name,
      objective = objective,
      success = false,
      iterations = 0,
      durationMs = durationMs,
      errorMessage = errorMessage,
    )
    addMetric(llmCallStrategy, metric)
    incrementCounter(executionCounts, llmCallStrategy)
    incrementCounter(errorCounts, llmCallStrategy)
    addToCounter(totalDurationMs, llmCallStrategy, durationMs)
  }

  /**
   * Gets aggregated stats for a specific LLM call strategy.
   */
  fun getStats(llmCallStrategy: LlmCallStrategy): AgentStats {
    val executions = executionCounts[llmCallStrategy]?.get() ?: 0
    val successes = successCounts[llmCallStrategy]?.get() ?: 0
    val failures = failureCounts[llmCallStrategy]?.get() ?: 0
    val errors = errorCounts[llmCallStrategy]?.get() ?: 0
    val iterations = totalIterations[llmCallStrategy]?.get() ?: 0L
    val duration = totalDurationMs[llmCallStrategy]?.get() ?: 0L

    val strategyMetrics = metrics[llmCallStrategy] ?: emptyList()
    val durations = strategyMetrics.map { it.durationMs }

    return AgentStats(
      agentImplementation = llmCallStrategy.name,
      totalExecutions = executions,
      successCount = successes,
      failureCount = failures,
      errorCount = errors,
      successRate = if (executions > 0) successes.toDouble() / executions else 0.0,
      avgIterations = if (successes + failures > 0) iterations.toDouble() / (successes + failures) else 0.0,
      avgDurationMs = if (executions > 0) duration.toDouble() / executions else 0.0,
      minDurationMs = durations.minOrNull() ?: 0L,
      maxDurationMs = durations.maxOrNull() ?: 0L,
    )
  }

  /**
   * Gets stats for all agent implementations.
   */
  fun getAllStats(): List<AgentStats> {
    return LlmCallStrategy.entries
      .map { getStats(it) }
      .filter { it.totalExecutions > 0 }
  }

  /**
   * Gets recent metrics for debugging/analysis.
   *
   * @param limit Maximum number of metrics to return per agent
   */
  fun getRecentMetrics(limit: Int = 10): Map<String, List<AgentExecutionMetric>> {
    return metrics.mapKeys { it.key.name }
      .mapValues { it.value.takeLast(limit) }
  }

  /**
   * Clears all collected metrics.
   */
  fun clear() {
    metrics.clear()
    executionCounts.clear()
    successCounts.clear()
    failureCounts.clear()
    errorCounts.clear()
    totalIterations.clear()
    totalDurationMs.clear()
  }

  /**
   * Returns a human-readable summary of agent metrics.
   */
  fun describeSummary(): String = buildString {
    appendLine("Agent Execution Metrics Summary")
    appendLine("=" .repeat(50))

    val allStats = getAllStats()
    if (allStats.isEmpty()) {
      appendLine("No metrics collected yet.")
      return@buildString
    }

    allStats.forEach { stats ->
      appendLine()
      appendLine("${stats.agentImplementation}:")
      appendLine("  Total executions: ${stats.totalExecutions}")
      appendLine("  Success rate: ${String.format("%.1f", stats.successRate * 100)}%")
      appendLine("  Successes: ${stats.successCount}, Failures: ${stats.failureCount}, Errors: ${stats.errorCount}")
      appendLine("  Avg iterations: ${String.format("%.1f", stats.avgIterations)}")
      appendLine("  Avg duration: ${String.format("%.0f", stats.avgDurationMs)}ms")
      appendLine("  Duration range: ${stats.minDurationMs}ms - ${stats.maxDurationMs}ms")
    }

    // Comparison if both agents have data
    val koogStats = allStats.find { it.agentImplementation == "DIRECT" }
    val legacyStats = allStats.find { it.agentImplementation == "MCP_SAMPLING" }
    if (koogStats != null && legacyStats != null) {
      appendLine()
      appendLine("Comparison (DIRECT vs MCP_SAMPLING):")
      val successDiff = (koogStats.successRate - legacyStats.successRate) * 100
      val durationDiff = koogStats.avgDurationMs - legacyStats.avgDurationMs
      appendLine("  Success rate: ${if (successDiff >= 0) "+" else ""}${String.format("%.1f", successDiff)}%")
      appendLine("  Avg duration: ${if (durationDiff >= 0) "+" else ""}${String.format("%.0f", durationDiff)}ms")
    }
  }

  private fun addMetric(llmCallStrategy: LlmCallStrategy, metric: AgentExecutionMetric) {
    val list = metrics.computeIfAbsent(llmCallStrategy) { CopyOnWriteArrayList() }
    synchronized(list) {
      list.add(metric)
      if (list.size > MAX_METRICS_PER_STRATEGY) {
        list.removeAt(0)
      }
    }
  }

  private fun incrementCounter(counters: ConcurrentHashMap<LlmCallStrategy, AtomicInteger>, key: LlmCallStrategy) {
    counters.computeIfAbsent(key) { AtomicInteger(0) }.incrementAndGet()
  }

  private fun addToCounter(counters: ConcurrentHashMap<LlmCallStrategy, AtomicLong>, key: LlmCallStrategy, value: Long) {
    counters.computeIfAbsent(key) { AtomicLong(0) }.addAndGet(value)
  }

  companion object {
    private const val MAX_METRICS_PER_STRATEGY = 1000

    /**
     * Global metrics collector instance.
     * Shared across all MCP sessions to aggregate metrics.
     */
    val GLOBAL = AgentMetricsCollector()
  }
}
