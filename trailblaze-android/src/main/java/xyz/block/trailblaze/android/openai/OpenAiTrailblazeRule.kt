package xyz.block.trailblaze.android.openai

import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.llm.LLModel
import xyz.block.trailblaze.android.AndroidTrailblazeRule

/**
 * OpenAI-specific implementation of AndroidTrailblazeRule.
 *
 * This class provides convenient defaults for OpenAI configuration while keeping
 * the base AndroidTrailblazeRule provider-agnostic. It handles OpenAI-specific
 * configuration like API keys and base URLs through instrumentation arguments.
 *
 * Usage:
 * ```kotlin
 * @get:Rule
 * val trailblazeRule = OpenAiTrailblazeRule(
 *   llmModel = OpenAIModels.Chat.GPT4_1
 * )
 * ```
 *
 * Or with custom configuration:
 * ```kotlin
 * @get:Rule
 * val trailblazeRule = OpenAiTrailblazeRule(
 *   apiKey = "custom-key",
 *   baseUrl = "https://custom-gateway.com/",
 *   llmModel = OpenAIModels.Chat.GPT4_1
 * )
 * ```
 */
class OpenAiTrailblazeRule(
  apiKey: String = OpenAiInstrumentationArgUtil.getApiKeyFromInstrumentationArg(),
  baseUrl: String = OpenAiInstrumentationArgUtil.getBaseUrlFromInstrumentationArg(),
  llmModel: LLModel = OpenAIModels.Chat.GPT4_1,
) : AndroidTrailblazeRule(
  llmClient = OpenAILLMClient(
    apiKey = apiKey,
    settings = OpenAIClientSettings(baseUrl = baseUrl),
  ),
  llmModel = llmModel,
)
