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
      LLMCapability.Schema.JSON.Simple,
      LLMCapability.Tools,
    ).map { it.id },
  )
  override val entries = listOf(
    OLLAMA_GPT_OSS_20B,
  )
  override val provider: TrailblazeLlmProvider = TrailblazeLlmProvider.OLLAMA
}
