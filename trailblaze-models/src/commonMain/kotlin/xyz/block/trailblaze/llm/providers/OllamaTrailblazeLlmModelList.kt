package xyz.block.trailblaze.llm.providers

import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmModelList
import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import xyz.block.trailblaze.llm.config.BuiltInLlmModelRegistry

object OllamaTrailblazeLlmModelList : TrailblazeLlmModelList {

  override val entries: List<TrailblazeLlmModel>
    get() = BuiltInLlmModelRegistry.modelListForProvider(TrailblazeLlmProvider.OLLAMA)
      ?.entries ?: emptyList()

  override val provider: TrailblazeLlmProvider = TrailblazeLlmProvider.OLLAMA
}
