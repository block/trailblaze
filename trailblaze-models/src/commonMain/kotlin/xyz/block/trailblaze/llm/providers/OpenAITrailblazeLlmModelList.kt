package xyz.block.trailblaze.llm.providers

import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmModelList
import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import xyz.block.trailblaze.llm.config.BuiltInLlmModelRegistry

/* https://developers.openai.com/api/docs/pricing */
object OpenAITrailblazeLlmModelList : TrailblazeLlmModelList {

  /**
   * The model named by `default_model` in `providers/openai.yaml`. Resolved through that
   * field rather than pinned to a model id, so refreshing the built-in catalog to newer
   * models can't leave this pointing at an id the registry no longer carries.
   */
  val OPENAI_DEFAULT: TrailblazeLlmModel
    get() {
      val providerId = TrailblazeLlmProvider.OPENAI.id
      val modelId = BuiltInLlmModelRegistry.defaultModelForProvider(TrailblazeLlmProvider.OPENAI)
        ?: error("No default_model declared in trails/config/providers/$providerId.yaml")
      return BuiltInLlmModelRegistry.require("$providerId/$modelId")
    }

  override val entries: List<TrailblazeLlmModel>
    get() = BuiltInLlmModelRegistry.modelListForProvider(TrailblazeLlmProvider.OPENAI)
      ?.entries ?: emptyList()

  override val provider: TrailblazeLlmProvider = TrailblazeLlmProvider.OPENAI
}
