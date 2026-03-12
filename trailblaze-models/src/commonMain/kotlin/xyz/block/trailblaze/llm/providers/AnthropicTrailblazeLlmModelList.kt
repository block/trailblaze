package xyz.block.trailblaze.llm.providers

import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import xyz.block.trailblaze.llm.TrailblazeLlmModel.Companion.toTrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmModelList
import xyz.block.trailblaze.llm.TrailblazeLlmProvider

/**
 * https://docs.anthropic.com/claude/docs/models-overview
 */
object AnthropicTrailblazeLlmModelList : TrailblazeLlmModelList {


  /** 90% discount on cached inputs */
  const val ANTHROPIC_CACHED_INPUT_DISCOUNT_MULTIPLIER = 0.10 // Anthropic cache read = 10% of input

  val CLAUDE_OPUS_4_6 = AnthropicModels.Opus_4_5.toTrailblazeLlmModel(
    inputCostPerOneMillionTokens = 5.00,
    outputCostPerOneMillionTokens = 25.00,
    cachedInputDiscountMultiplier = ANTHROPIC_CACHED_INPUT_DISCOUNT_MULTIPLIER,
  ).copy(
    modelId = "claude-opus-4-6"
  )

  val CLAUDE_SONNET_4_6 = AnthropicModels.Sonnet_4_5.toTrailblazeLlmModel(
    inputCostPerOneMillionTokens = 3.00,
    outputCostPerOneMillionTokens = 15.00,
    cachedInputDiscountMultiplier = ANTHROPIC_CACHED_INPUT_DISCOUNT_MULTIPLIER,
  ).copy(
    modelId = "claude-sonnet-4-6"
  )

  val CLAUDE_HAIKU_4_5 = AnthropicModels.Haiku_4_5.toTrailblazeLlmModel(
    inputCostPerOneMillionTokens = 1.00,
    outputCostPerOneMillionTokens = 5.00,
    cachedInputDiscountMultiplier = ANTHROPIC_CACHED_INPUT_DISCOUNT_MULTIPLIER,
  ).copy(
    modelId = "claude-haiku-4-5"
  )

  override val entries = listOf(
    CLAUDE_HAIKU_4_5,
    CLAUDE_OPUS_4_6,
    CLAUDE_SONNET_4_6,
  )

  override val provider: TrailblazeLlmProvider = TrailblazeLlmProvider.ANTHROPIC
}
