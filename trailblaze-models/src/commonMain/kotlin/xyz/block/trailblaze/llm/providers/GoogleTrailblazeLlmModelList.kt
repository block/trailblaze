package xyz.block.trailblaze.llm.providers

import ai.koog.prompt.executor.clients.google.GoogleModels
import xyz.block.trailblaze.llm.TrailblazeLlmModel.Companion.toTrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmModelList
import xyz.block.trailblaze.llm.TrailblazeLlmProvider

/**
 * https://ai.google.dev/gemini-api/docs/pricing
 */
object GoogleTrailblazeLlmModelList : TrailblazeLlmModelList {

  val GEMINI_3_0_PRO_PREVIEW = GoogleModels.Gemini3_Pro_Preview.toTrailblazeLlmModel(
    inputCostPerOneMillionTokens = 2.00,
    outputCostPerOneMillionTokens = 4.00,
  )

  val GEMINI_3_0_FLASH_PREVIEW = GEMINI_3_0_PRO_PREVIEW.copy(
    modelId = "gemini-3-flash-preview",
    inputCostPerOneMillionTokens = 0.50,
    outputCostPerOneMillionTokens = 3.00,
  )

  val GEMINI_2_5_FLASH = GoogleModels.Gemini2_5Flash.toTrailblazeLlmModel(
    inputCostPerOneMillionTokens = 0.30,
    outputCostPerOneMillionTokens = 2.50,
  )

  val GEMINI_2_5_FLASH_LITE = GEMINI_2_5_FLASH.copy(
    modelId = "gemini-2.5-flash-lite",
    inputCostPerOneMillionTokens = 0.10,
    outputCostPerOneMillionTokens = 0.40,
  )

  val GEMINI_2_5_PRO = GoogleModels.Gemini2_5Pro.toTrailblazeLlmModel(
    inputCostPerOneMillionTokens = 1.25,
    outputCostPerOneMillionTokens = 10.00,
  )

  override val entries = listOf(
    GEMINI_2_5_FLASH,
    GEMINI_2_5_FLASH_LITE,
    GEMINI_2_5_PRO,
    GEMINI_3_0_PRO_PREVIEW,
    GEMINI_3_0_FLASH_PREVIEW,
  )

  override val provider: TrailblazeLlmProvider = TrailblazeLlmProvider.GOOGLE
}
