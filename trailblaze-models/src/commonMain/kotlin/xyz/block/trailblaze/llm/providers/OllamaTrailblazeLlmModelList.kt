package xyz.block.trailblaze.llm.providers

import ai.koog.prompt.llm.LLMCapability
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmModelList
import xyz.block.trailblaze.llm.TrailblazeLlmProvider

object OllamaTrailblazeLlmModelList : TrailblazeLlmModelList {
  val OLLAMA_GPT_OSS_20B = TrailblazeLlmModel(
    trailblazeLlmProvider = TrailblazeLlmProvider.OLLAMA,
    modelId = "gpt-oss:20b",
    inputCostPerOneMillionTokens = 0.0,
    outputCostPerOneMillionTokens = 0.0,
    capabilityIds = listOf(
      LLMCapability.Temperature,
      LLMCapability.Schema.JSON.Standard,
      LLMCapability.Tools,
    ).map { it.id },
    contextLength = 131_072L, // 128K context window
    maxOutputTokens = 65_536L, // 64K output tokens
  )
  val OLLAMA_QWEN3_VL_2B = TrailblazeLlmModel(
    trailblazeLlmProvider = TrailblazeLlmProvider.OLLAMA,
    modelId = "qwen3-vl:2b",
    inputCostPerOneMillionTokens = 0.0,
    outputCostPerOneMillionTokens = 0.0,
    capabilityIds = listOf(
      LLMCapability.Temperature,
      LLMCapability.Schema.JSON.Basic,
      LLMCapability.Tools,
      LLMCapability.Vision.Image,
      LLMCapability.Document
    ).map { it.id },
    contextLength = 131_072L, // 128K context window
    maxOutputTokens = 8_192L, // 8K output tokens (reduced from 64K to prevent Ollama server errors)
  )
  val OLLAMA_QWEN3_VL_30B = OLLAMA_QWEN3_VL_2B.copy(
    modelId = "qwen3-vl:30b",
  )

  override val entries = listOf(
    OLLAMA_GPT_OSS_20B,
    OLLAMA_QWEN3_VL_2B,
    OLLAMA_QWEN3_VL_30B,
  )
  override val provider: TrailblazeLlmProvider = TrailblazeLlmProvider.OLLAMA
}
