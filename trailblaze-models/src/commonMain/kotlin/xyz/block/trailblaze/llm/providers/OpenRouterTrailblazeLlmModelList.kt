package xyz.block.trailblaze.llm.providers

import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmModelList
import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import xyz.block.trailblaze.llm.config.BuiltInLlmModelRegistry

object OpenRouterTrailblazeLlmModelList : TrailblazeLlmModelList {
  const val GPT_OSS_120B_FREE = "open_router/openai/gpt-oss-120b:free"

  override val entries: List<TrailblazeLlmModel>
    get() = BuiltInLlmModelRegistry.modelListForProvider(TrailblazeLlmProvider.OPEN_ROUTER)
      ?.entries ?: emptyList()

  override val provider: TrailblazeLlmProvider = TrailblazeLlmProvider.OPEN_ROUTER
}
