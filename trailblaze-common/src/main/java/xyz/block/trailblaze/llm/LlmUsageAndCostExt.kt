package xyz.block.trailblaze.llm

import xyz.block.trailblaze.llm.LlmRequestUsageAndCost.Companion.calculateCost
import xyz.block.trailblaze.logs.client.TrailblazeLog
import java.math.BigDecimal
import java.math.RoundingMode

object LlmUsageAndCostExt {
  fun List<TrailblazeLog>.computeUsageSummary(): LlmSessionUsageAndCost? {
    val requests = this.filterIsInstance<TrailblazeLog.TrailblazeLlmRequestLog>()
    if (requests.isEmpty()) {
      // Short Circuit if there are no requests
      return null
    }
    val requestCostBreakdowns: List<LlmRequestUsageAndCost> = requests.map {
      it.llmResponse.calculateCost()
    }
    val modelName = requests.first().llmResponse.model.id

    return LlmSessionUsageAndCost(
      modelName = modelName,
      totalRequestCount = requests.size,
      averageDurationMillis = requests.map { it.duration }.average(),
      averageInputTokens = requestCostBreakdowns.map { it.inputTokens }.average(),
      averageOutputTokens = requestCostBreakdowns.map { it.outputTokens }.average(),
      totalCostInUsDollars = requestCostBreakdowns.sumOf { it.totalCost }.let { BigDecimal(it).setScale(2, RoundingMode.HALF_EVEN).toDouble() },
      totalInputTokens = requestCostBreakdowns.sumOf { it.inputTokens },
      totalOutputTokens = requestCostBreakdowns.sumOf { it.outputTokens },
    )
  }
}
