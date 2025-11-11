package xyz.block.trailblaze.host.rules

import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.providers.OpenAITrailblazeLlmModelList

/**
 * Some centralized defaults for "Host" mode to avoid specifying these all over the place.
 */
object TrailblazeHostLlmConfig {

  /**
   * This is our "default" model.  If we want to do a global change, this is the place to do it for Host based tests.
   */
  val DEFAULT_TRAILBLAZE_LLM_MODEL: TrailblazeLlmModel = OpenAITrailblazeLlmModelList.OPENAI_GPT_4_1
}
