package xyz.block.trailblaze.llm.providers

import ai.koog.prompt.llm.LLMCapability
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmModelList
import xyz.block.trailblaze.llm.TrailblazeLlmProvider

object GoogleTrailblazeLlmModelList : TrailblazeLlmModelList {
  val GEMINI_2_5_FLASH = TrailblazeLlmModel(
    trailblazeLlmProvider = TrailblazeLlmProvider.GOOGLE,
    modelId = "gemini-2-5-flash",
    inputCostPerOneMillionTokens = 0.30,
    outputCostPerOneMillionTokens = 2.50,
    capabilityIds = listOf(
      LLMCapability.Audio,
      LLMCapability.Completion,
      LLMCapability.MultipleChoices,
      LLMCapability.Schema.JSON.Basic,
      LLMCapability.Schema.JSON.Standard,
      LLMCapability.Tools,
      LLMCapability.ToolChoice,
      LLMCapability.Temperature,
      LLMCapability.Vision.Image,
      LLMCapability.Vision.Video,
    ).map { it.id },
    contextLength = 1_048_576,
    maxOutputTokens = 65_536,
  )

  val GEMINI_2_5_PRO = TrailblazeLlmModel(
    trailblazeLlmProvider = TrailblazeLlmProvider.GOOGLE,
    modelId = "gemini-2-5-pro",
    inputCostPerOneMillionTokens = 1.25,
    outputCostPerOneMillionTokens = 10.00,
    capabilityIds = listOf(
      LLMCapability.Audio,
      LLMCapability.Completion,
      LLMCapability.MultipleChoices,
      LLMCapability.Schema.JSON.Basic,
      LLMCapability.Schema.JSON.Standard,
      LLMCapability.Tools,
      LLMCapability.ToolChoice,
      LLMCapability.Temperature,
      LLMCapability.Vision.Image,
      LLMCapability.Vision.Video,
    ).map { it.id },
    contextLength = 1_048_576,
    maxOutputTokens = 65_536,
  )

  override val entries = listOf(
    GEMINI_2_5_FLASH,
    GEMINI_2_5_PRO,
  )

  override val provider: TrailblazeLlmProvider = TrailblazeLlmProvider.GOOGLE
}
