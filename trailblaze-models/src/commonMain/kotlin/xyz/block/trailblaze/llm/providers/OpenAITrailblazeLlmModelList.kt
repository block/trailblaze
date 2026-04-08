package xyz.block.trailblaze.llm.providers

import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmModelList
import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import xyz.block.trailblaze.llm.config.BuiltInLlmModelRegistry

/* https://openai.com/api/pricing/ */
object OpenAITrailblazeLlmModelList : TrailblazeLlmModelList {

  val OPENAI_GPT_4_1: TrailblazeLlmModel get() = BuiltInLlmModelRegistry.require("openai/gpt-4.1")

  override val entries: List<TrailblazeLlmModel>
    get() = BuiltInLlmModelRegistry.modelListForProvider(TrailblazeLlmProvider.OPENAI)
      ?.entries ?: emptyList()

  override val provider: TrailblazeLlmProvider = TrailblazeLlmProvider.OPENAI
}
