package xyz.block.trailblaze.llm.providers

import ai.koog.prompt.executor.clients.openrouter.OpenRouterModels
import xyz.block.trailblaze.llm.TrailblazeLlmModel.Companion.toTrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmModelList
import xyz.block.trailblaze.llm.TrailblazeLlmProvider

object OpenRouterTrailblazeLlmModelList : TrailblazeLlmModelList {

  /** https://openrouter.ai/qwen/qwen3-vl-8b-instruct */
  val QWEN3_VL_8B_INSTRUCT = OpenRouterModels.Qwen3VL.toTrailblazeLlmModel(
    inputCostPerOneMillionTokens = 0.08,
    outputCostPerOneMillionTokens = 0.50
  )
  
  /** https://openrouter.ai/openai/gpt-oss-120b:free */
  val GPT_OSS_120B_FREE = OpenRouterModels.GPT_OSS_120b.toTrailblazeLlmModel(
    inputCostPerOneMillionTokens = 0.00,
    outputCostPerOneMillionTokens = 0.00,
    maxOutputTokens = 131_072
  ).copy(
    modelId = "openai/gpt-oss-120b:free",
  )

  override val entries = listOf(
    QWEN3_VL_8B_INSTRUCT,
    GPT_OSS_120B_FREE,
  )

  override val provider: TrailblazeLlmProvider = TrailblazeLlmProvider.OPEN_ROUTER
}
