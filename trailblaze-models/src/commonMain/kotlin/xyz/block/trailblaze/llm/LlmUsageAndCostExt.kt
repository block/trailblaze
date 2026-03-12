package xyz.block.trailblaze.llm

import xyz.block.trailblaze.logs.client.TrailblazeLog
import kotlin.math.round

object LlmUsageAndCostExt {
  private fun Double.roundTo2DecimalPlaces(): Double = round(this * 100) / 100

  fun List<TrailblazeLog>.computeUsageSummary(): LlmSessionUsageAndCost? {
    val requests = this.filterIsInstance<TrailblazeLog.TrailblazeLlmRequestLog>()
    if (requests.isEmpty()) {
      // Short Circuit if there are no requests
      return null
    }

    // Use pre-calculated usage data from logs if available, otherwise calculate
    val requestCostBreakdowns: List<LlmRequestUsageAndCost> = requests.mapNotNull {
      it.llmRequestUsageAndCost
    }

    val llmModel: TrailblazeLlmModel = requests.first().trailblazeLlmModel

    // Aggregate token breakdown across all requests
    val breakdownsWithData = requestCostBreakdowns.mapNotNull { it.inputTokenBreakdown }
    val aggregatedBreakdown = if (breakdownsWithData.isNotEmpty()) {
      LlmInputTokenBreakdown(
        systemPrompt = CategoryBreakdown(
          tokens = breakdownsWithData.sumOf { it.systemPrompt.tokens },
          count = breakdownsWithData.sumOf { it.systemPrompt.count },
        ),
        userPrompt = CategoryBreakdown(
          tokens = breakdownsWithData.sumOf { it.userPrompt.tokens },
          count = breakdownsWithData.sumOf { it.userPrompt.count },
        ),
        toolDescriptors = CategoryBreakdown(
          tokens = breakdownsWithData.sumOf { it.toolDescriptors.tokens },
          count = breakdownsWithData.sumOf { it.toolDescriptors.count },
        ),
        images = CategoryBreakdown(
          tokens = breakdownsWithData.sumOf { it.images.tokens },
          count = breakdownsWithData.sumOf { it.images.count },
        ),
        assistantMessageCount = breakdownsWithData.sumOf { it.assistantMessageCount },
        toolMessageCount = breakdownsWithData.sumOf { it.toolMessageCount },
      )
    } else {
      null
    }

    return LlmSessionUsageAndCost(
      llmModel = llmModel,
      totalRequestCount = requests.size,
      averageDurationMillis = requests.map { it.durationMs }.average(),
      averageInputTokens = if (requestCostBreakdowns.isNotEmpty()) requestCostBreakdowns.map { it.inputTokens }.average() else 0.0,
      averageOutputTokens = if (requestCostBreakdowns.isNotEmpty()) requestCostBreakdowns.map { it.outputTokens }.average() else 0.0,
      totalCostInUsDollars = requestCostBreakdowns.sumOf { it.totalCost }.roundTo2DecimalPlaces(),
      totalInputTokens = requestCostBreakdowns.sumOf { it.inputTokens },
      totalOutputTokens = requestCostBreakdowns.sumOf { it.outputTokens },
      totalCacheReadInputTokens = requestCostBreakdowns.sumOf { it.cacheReadInputTokens },
      totalCacheCreationInputTokens = requestCostBreakdowns.sumOf { it.cacheCreationInputTokens },
      totalCacheSavings = requestCostBreakdowns.sumOf { it.cacheSavings }.roundTo2DecimalPlaces(),
      aggregatedInputTokenBreakdown = aggregatedBreakdown,
      requestBreakdowns = requestCostBreakdowns,
    )
  }
}
