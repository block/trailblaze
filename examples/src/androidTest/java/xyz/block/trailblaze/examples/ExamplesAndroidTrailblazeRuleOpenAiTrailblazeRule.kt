package xyz.block.trailblaze.examples

import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import xyz.block.trailblaze.android.AndroidTrailblazeRule
import xyz.block.trailblaze.android.openai.OpenAiInstrumentationArgUtil
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.providers.OpenAITrailblazeLlmModelList
import xyz.block.trailblaze.model.CustomTrailblazeTools

class ExamplesAndroidTrailblazeRuleOpenAiTrailblazeRule(
  apiKey: String = OpenAiInstrumentationArgUtil.getApiKeyFromInstrumentationArg(),
  baseUrl: String = OpenAiInstrumentationArgUtil.getBaseUrlFromInstrumentationArg(),
  trailblazeLlmModel: TrailblazeLlmModel = OpenAITrailblazeLlmModelList.OPENAI_GPT_4_1_MINI,
  customToolClasses: CustomTrailblazeTools = CustomTrailblazeTools(setOf()),
) : AndroidTrailblazeRule(
  llmClient = OpenAILLMClient(
    apiKey = apiKey,
    settings = OpenAIClientSettings(baseUrl = baseUrl),
  ),
  trailblazeLlmModel = trailblazeLlmModel,
  customToolClasses = customToolClasses,
)
