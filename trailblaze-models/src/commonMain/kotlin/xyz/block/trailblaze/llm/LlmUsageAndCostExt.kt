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
    val requestCostBreakdowns: List<LlmRequestUsageAndCost> = requests.map {
      it.llmResponse.calculateCost(it.trailblazeLlmModel)
    }
    val llmModel: TrailblazeLlmModel = requests.first().trailblazeLlmModel

    return LlmSessionUsageAndCost(
      llmModel = llmModel,
      totalRequestCount = requests.size,
      averageDurationMillis = requests.map { it.durationMs }.average(),
      averageInputTokens = requestCostBreakdowns.map { it.inputTokens }.average(),
      averageOutputTokens = requestCostBreakdowns.map { it.outputTokens }.average(),
      totalCostInUsDollars = requestCostBreakdowns.sumOf { it.totalCost }.roundTo2DecimalPlaces(),
      totalInputTokens = requestCostBreakdowns.sumOf { it.inputTokens },
      totalOutputTokens = requestCostBreakdowns.sumOf { it.outputTokens },
    )
  }
}
