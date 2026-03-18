package xyz.block.trailblaze.examples.rules

import xyz.block.trailblaze.android.AndroidTrailblazeRule
import xyz.block.trailblaze.android.InstrumentationArgUtil
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.providers.OpenAITrailblazeLlmModelList
import xyz.block.trailblaze.llm.providers.OpenRouterTrailblazeLlmModelList
import xyz.block.trailblaze.model.CustomTrailblazeTools
import xyz.block.trailblaze.model.TrailblazeConfig

class ExamplesAndroidTrailblazeRule(
  trailblazeLlmModel: TrailblazeLlmModel =
    InstrumentationArgUtil.resolveTrailblazeLlmModel(
      "OPENROUTER_API_KEY" to OpenRouterTrailblazeLlmModelList.GPT_OSS_120B_FREE,
      "OPENAI_API_KEY" to OpenAITrailblazeLlmModelList.OPENAI_GPT_4_1,
    ),
  config: TrailblazeConfig = TrailblazeConfig.DEFAULT,
  customToolClasses: CustomTrailblazeTools = CustomTrailblazeTools(
    registeredAppSpecificLlmTools = setOf(),
    config = config,
  ),
) : AndroidTrailblazeRule(
  llmClient = ExamplesDefaultDynamicLlmClient(trailblazeLlmModel).createLlmClient(),
  trailblazeLlmModel = trailblazeLlmModel,
  config = config,
  customToolClasses = customToolClasses,
)
