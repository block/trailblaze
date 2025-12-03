package xyz.block.trailblaze.examples

import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import xyz.block.trailblaze.android.AndroidTrailblazeRule
import xyz.block.trailblaze.android.openai.OpenAiInstrumentationArgUtil
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.providers.OpenAITrailblazeLlmModelList
import xyz.block.trailblaze.model.CustomTrailblazeTools
import xyz.block.trailblaze.model.TrailblazeConfig

class ExamplesAndroidTrailblazeRuleOpenAiTrailblazeRule(
  apiKey: String = OpenAiInstrumentationArgUtil.getApiKeyFromInstrumentationArg(),
  baseUrl: String = OpenAiInstrumentationArgUtil.getBaseUrlFromInstrumentationArg(),
  trailblazeLlmModel: TrailblazeLlmModel = OpenAITrailblazeLlmModelList.OPENAI_GPT_4_1,
  config: TrailblazeConfig = TrailblazeConfig.DEFAULT,
  customToolClasses: CustomTrailblazeTools = CustomTrailblazeTools(
    registeredAppSpecificLlmTools = setOf(),
    config = config,
  ),
) : AndroidTrailblazeRule(
  llmClient = OpenAILLMClient(
    apiKey = apiKey,
    settings = OpenAIClientSettings(baseUrl = baseUrl),
  ),
  trailblazeLlmModel = trailblazeLlmModel,
  config = config,
  customToolClasses = customToolClasses,
)
