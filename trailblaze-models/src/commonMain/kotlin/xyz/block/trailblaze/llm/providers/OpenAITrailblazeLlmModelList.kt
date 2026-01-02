package xyz.block.trailblaze.llm.providers

import ai.koog.prompt.executor.clients.openai.OpenAIModels
import xyz.block.trailblaze.llm.TrailblazeLlmModel.Companion.toTrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmModelList
import xyz.block.trailblaze.llm.TrailblazeLlmProvider

object OpenAITrailblazeLlmModelList : TrailblazeLlmModelList {

  val OPENAI_GPT_5_2 = OpenAIModels.Chat.GPT5_2.toTrailblazeLlmModel(
    inputCostPerOneMillionTokens = 1.75,
    outputCostPerOneMillionTokens = 14.00,
  )

  val OPENAI_GPT_5 = OpenAIModels.Chat.GPT5.toTrailblazeLlmModel(
    inputCostPerOneMillionTokens = 1.25,
    outputCostPerOneMillionTokens = 10.00,
  )

  val OPENAI_GPT_5_MINI = OpenAIModels.Chat.GPT5Mini.toTrailblazeLlmModel(
    inputCostPerOneMillionTokens = 0.25,
    outputCostPerOneMillionTokens = 12.00,
  )

  val OPENAI_GPT_4_1 = OpenAIModels.Chat.GPT4_1.toTrailblazeLlmModel(
    inputCostPerOneMillionTokens = 2.00,
    outputCostPerOneMillionTokens = 8.00,
  )
  val OPENAI_GPT_4_1_MINI = OpenAIModels.Chat.GPT4_1Mini.toTrailblazeLlmModel(
    inputCostPerOneMillionTokens = 0.40,
    outputCostPerOneMillionTokens = 1.60,
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
