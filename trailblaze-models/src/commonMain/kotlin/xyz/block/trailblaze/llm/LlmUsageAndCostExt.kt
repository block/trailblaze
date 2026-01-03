package xyz.block.trailblaze.llm

import xyz.block.trailblaze.llm.LlmRequestUsageAndCost.Companion.calculateCost
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
    val requestCostBreakdowns: List<LlmRequestUsageAndCost> = requests.map {
      it.llmRequestUsageAndCost ?: it.llmResponse.calculateCost(it.trailblazeLlmModel)
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
      averageInputTokens = requestCostBreakdowns.map { it.inputTokens }.average(),
      averageOutputTokens = requestCostBreakdowns.map { it.outputTokens }.average(),
      totalCostInUsDollars = requestCostBreakdowns.sumOf { it.totalCost }.roundTo2DecimalPlaces(),
      totalInputTokens = requestCostBreakdowns.sumOf { it.inputTokens },
      totalOutputTokens = requestCostBreakdowns.sumOf { it.outputTokens },
      aggregatedInputTokenBreakdown = aggregatedBreakdown,
      requestBreakdowns = requestCostBreakdowns,
    )
  }
}
