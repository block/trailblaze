package xyz.block.trailblaze.llm

import xyz.block.trailblaze.logs.client.TrailblazeLog

/**
 * Enriches LLM request logs with accurate pricing from a host-side model lookup.
 *
 * Always recalculates costs using the host's pricing config before logs are saved to disk.
 * This ensures pricing is consistent regardless of whether the LLM call happened on-device
 * (where pricing config may be stale or absent) or on the host.
 *
 * Usage:
 * ```kotlin
 * val enricher = LlmLogCostEnricher { modelId ->
 *   resolvedConfig.findModelById(modelId)
 * }
 * val logsRepo = LogsRepo(logsDir, costEnricher = enricher::enrich)
 * ```
 */
class LlmLogCostEnricher(
  private val modelLookup: (modelId: String) -> TrailblazeLlmModel?,
) {

  /**
   * Enriches a log event by recalculating costs from the host's model config.
   * Returns the original log unchanged if it's not an LLM request or if the model
   * is not found in the lookup.
   */
  fun enrich(log: TrailblazeLog): TrailblazeLog {
    if (log !is TrailblazeLog.TrailblazeLlmRequestLog) return log
    val usageAndCost = log.llmRequestUsageAndCost ?: return log

    val modelId = usageAndCost.trailblazeLlmModel.modelId
    val hostModel = modelLookup(modelId) ?: return log

    return log.copy(
      llmRequestUsageAndCost = usageAndCost.withRecalculatedCosts(hostModel),
    )
  }
}
