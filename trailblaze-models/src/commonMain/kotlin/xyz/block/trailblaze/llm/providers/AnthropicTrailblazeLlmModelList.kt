package xyz.block.trailblaze.llm.providers

import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.llm.LLMCapability
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmModel.Companion.toTrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmModelList
import xyz.block.trailblaze.llm.TrailblazeLlmProvider

object AnthropicTrailblazeLlmModelList : TrailblazeLlmModelList {

  /**
   * Claude Haiku 4.5 is Anthropic's fastest and most intelligent Haiku model.
   * It has the near-frontier intelligence at blazing speeds with extended thinking and exceptional cost-efficiency.
   *
   * 200K context window
   * Knowledge cutoff: July 2025
   *
   * @see <a href="https://docs.anthropic.com/claude/docs/models-overview">
   */
  val CLAUDE_HAIKU_4_5 = AnthropicModels.Haiku_4_5.toTrailblazeLlmModel(
    inputCostPerOneMillionTokens = 1.00,
    outputCostPerOneMillionTokens = 5.00,
  )

  /**
   * Claude Sonnet 4.5 is Anthropic's best model for complex agents and coding.
   * It has the highest level of intelligence across most tasks with exceptional agent and coding capabilities.
   *
   * 200K context window
   * Knowledge cutoff: July 2025
   *
   * @see <a href="https://docs.anthropic.com/claude/docs/models-overview">
   */
  val CLAUDE_SONNET_4_5 = AnthropicModels.Sonnet_4_5.toTrailblazeLlmModel(
    inputCostPerOneMillionTokens = 3.00,
    outputCostPerOneMillionTokens = 15.00,
  )

  override val entries = listOf(
    CLAUDE_HAIKU_4_5,
    CLAUDE_SONNET_4_5,
  )

  override val provider: TrailblazeLlmProvider = TrailblazeLlmProvider.ANTHROPIC
}
