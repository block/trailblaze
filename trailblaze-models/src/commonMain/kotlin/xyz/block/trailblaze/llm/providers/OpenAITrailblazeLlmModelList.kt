package xyz.block.trailblaze.llm.providers

import ai.koog.prompt.llm.LLMCapability
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmModelList
import xyz.block.trailblaze.llm.TrailblazeLlmProvider

object OpenAITrailblazeLlmModelList : TrailblazeLlmModelList {

  val OPENAI_GPT_5_2 = TrailblazeLlmModel(
    trailblazeLlmProvider = TrailblazeLlmProvider.OPENAI,
    modelId = "gpt-5.2",
    inputCostPerOneMillionTokens = 1.75,
    outputCostPerOneMillionTokens = 14.00,
    capabilityIds = listOf(
      LLMCapability.Temperature,
      LLMCapability.Schema.JSON.Basic,
      LLMCapability.Schema.JSON.Standard,
      LLMCapability.Speculation,
      LLMCapability.Tools,
      LLMCapability.ToolChoice,
      LLMCapability.Vision.Image,
      LLMCapability.Document,
      LLMCapability.Completion,
      LLMCapability.MultipleChoices,
      LLMCapability.OpenAIEndpoint.Completions,
      LLMCapability.OpenAIEndpoint.Responses,
    ).map { it.id },
    contextLength = 400_000,
    maxOutputTokens = 128_000,
  )

  val OPENAI_GPT_5 = TrailblazeLlmModel(
    trailblazeLlmProvider = TrailblazeLlmProvider.OPENAI,
    modelId = "gpt-5",
    inputCostPerOneMillionTokens = 1.25,
    outputCostPerOneMillionTokens = 10.00,
    capabilityIds = listOf(
      LLMCapability.Temperature,
      LLMCapability.Schema.JSON.Basic,
      LLMCapability.Schema.JSON.Standard,
      LLMCapability.Speculation,
      LLMCapability.Tools,
      LLMCapability.ToolChoice,
      LLMCapability.Vision.Image,
      LLMCapability.Document,
      LLMCapability.Completion,
      LLMCapability.MultipleChoices,
      LLMCapability.OpenAIEndpoint.Completions,
      LLMCapability.OpenAIEndpoint.Responses,
    ).map { it.id },
    contextLength = 400_000,
    maxOutputTokens = 128_000,
  )
  val OPENAI_GPT_5_MINI = TrailblazeLlmModel(
    trailblazeLlmProvider = TrailblazeLlmProvider.OPENAI,
    modelId = "gpt-5-mini",
    inputCostPerOneMillionTokens = 0.25,
    outputCostPerOneMillionTokens = 12.00,
    capabilityIds = listOf(
      LLMCapability.Temperature,
      LLMCapability.Schema.JSON.Basic,
      LLMCapability.Schema.JSON.Standard,
      LLMCapability.Speculation,
      LLMCapability.Tools,
      LLMCapability.ToolChoice,
      LLMCapability.Vision.Image,
      LLMCapability.Document,
      LLMCapability.Completion,
      LLMCapability.MultipleChoices,
      LLMCapability.OpenAIEndpoint.Completions,
      LLMCapability.OpenAIEndpoint.Responses,
    ).map { it.id },
    contextLength = 400_000,
    maxOutputTokens = 128_000,
  )
  val OPENAI_GPT_4_1 = TrailblazeLlmModel(
    trailblazeLlmProvider = TrailblazeLlmProvider.OPENAI,
    modelId = "gpt-4.1",
    inputCostPerOneMillionTokens = 2.00,
    outputCostPerOneMillionTokens = 8.00,
    capabilityIds = listOf(
      LLMCapability.Temperature,
      LLMCapability.Schema.JSON.Basic,
      LLMCapability.Schema.JSON.Standard,
      LLMCapability.Speculation,
      LLMCapability.Tools,
      LLMCapability.ToolChoice,
      LLMCapability.Vision.Image,
      LLMCapability.Document,
      LLMCapability.Completion,
      LLMCapability.MultipleChoices,
      LLMCapability.OpenAIEndpoint.Completions,
      LLMCapability.OpenAIEndpoint.Responses,
    ).map { it.id },
    contextLength = 1_047_576,
    maxOutputTokens = 32_768,
  )
  val OPENAI_GPT_4_1_MINI = TrailblazeLlmModel(
    trailblazeLlmProvider = TrailblazeLlmProvider.OPENAI,
    modelId = "gpt-4.1-mini",
    inputCostPerOneMillionTokens = 0.40,
    outputCostPerOneMillionTokens = 1.60,
    capabilityIds = listOf(
      LLMCapability.Temperature,
      LLMCapability.Schema.JSON.Basic,
      LLMCapability.Schema.JSON.Standard,
      LLMCapability.Speculation,
      LLMCapability.Tools,
      LLMCapability.ToolChoice,
      LLMCapability.Vision.Image,
      LLMCapability.Document,
      LLMCapability.Completion,
      LLMCapability.MultipleChoices,
      LLMCapability.OpenAIEndpoint.Completions,
      LLMCapability.OpenAIEndpoint.Responses,
    ).map { it.id },
    contextLength = 1_047_576,
    maxOutputTokens = 32_768,
  )

  override val entries = listOf(
    OPENAI_GPT_5_2,
    OPENAI_GPT_5,
    OPENAI_GPT_5_MINI,
    OPENAI_GPT_4_1,
    OPENAI_GPT_4_1_MINI,
  )
  override val provider: TrailblazeLlmProvider = TrailblazeLlmProvider.OPENAI
}
