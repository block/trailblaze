package xyz.block.trailblaze.llm.providers

import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmModelList
import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import xyz.block.trailblaze.llm.config.BuiltInLlmModelRegistry

/**
 * https://docs.anthropic.com/claude/docs/models-overview
 */
object AnthropicTrailblazeLlmModelList : TrailblazeLlmModelList {

  override val entries: List<TrailblazeLlmModel>
    get() = BuiltInLlmModelRegistry.modelListForProvider(TrailblazeLlmProvider.ANTHROPIC)
      ?.entries ?: emptyList()

  override val provider: TrailblazeLlmProvider = TrailblazeLlmProvider.ANTHROPIC
}
