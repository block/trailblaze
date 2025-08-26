package xyz.block.trailblaze.examples

import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.llm.LLModel
import xyz.block.trailblaze.android.AndroidTrailblazeRule
import xyz.block.trailblaze.android.openai.OpenAiInstrumentationArgUtil
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import kotlin.reflect.KClass

class ExamplesAndroidTrailblazeRuleOpenAiTrailblazeRule(
  apiKey: String = OpenAiInstrumentationArgUtil.getApiKeyFromInstrumentationArg(),
  baseUrl: String = OpenAiInstrumentationArgUtil.getBaseUrlFromInstrumentationArg(),
  llmModel: LLModel = OpenAIModels.CostOptimized.GPT4_1Mini,
  customToolClasses: Set<KClass<out TrailblazeTool>> = setOf(),
) : AndroidTrailblazeRule(
  llmClient = OpenAILLMClient(
    apiKey = apiKey,
    settings = OpenAIClientSettings(baseUrl = baseUrl),
  ),
  llmModel = llmModel,
  customToolClasses = customToolClasses,
)
