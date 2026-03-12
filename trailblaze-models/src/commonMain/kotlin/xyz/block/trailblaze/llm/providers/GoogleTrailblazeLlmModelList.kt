package xyz.block.trailblaze.llm.providers

import ai.koog.prompt.executor.clients.google.GoogleModels
import xyz.block.trailblaze.llm.TrailblazeLlmModel.Companion.toTrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmModelList
import xyz.block.trailblaze.llm.TrailblazeLlmProvider

/**
 * https://ai.google.dev/gemini-api/docs/pricing
 */
object GoogleTrailblazeLlmModelList : TrailblazeLlmModelList {

  /** 90% discount on cached inputs */
  const val GOOGLE_CACHED_INPUT_DISCOUNT_MULTIPLIER_90_PERCENT = 0.10

  val GEMINI_2_5_PRO = GoogleModels.Gemini2_5Pro.toTrailblazeLlmModel(
    inputCostPerOneMillionTokens = 1.25,
    outputCostPerOneMillionTokens = 10.00,
    cachedInputDiscountMultiplier = GOOGLE_CACHED_INPUT_DISCOUNT_MULTIPLIER_90_PERCENT
  )

  val GEMINI_3_0_FLASH_PREVIEW = GoogleModels.Gemini2_5Flash.toTrailblazeLlmModel(
    inputCostPerOneMillionTokens = 0.50,
    outputCostPerOneMillionTokens = 3.00,
    cachedInputDiscountMultiplier = GOOGLE_CACHED_INPUT_DISCOUNT_MULTIPLIER_90_PERCENT,
  ).copy(
    modelId = "gemini-3-flash-preview",
  )

  val GEMINI_3_1_PRO_PREVIEW = GoogleModels.Gemini3_Pro_Preview.toTrailblazeLlmModel(
    inputCostPerOneMillionTokens = 2.00,
    outputCostPerOneMillionTokens = 12.00,
    cachedInputDiscountMultiplier = GOOGLE_CACHED_INPUT_DISCOUNT_MULTIPLIER_90_PERCENT,
  ).copy(
    modelId = "gemini-3.1-pro-preview"
  )

  val GEMINI_3_1_PRO_PREVIEW_CUSTOMTOOLS = GEMINI_3_1_PRO_PREVIEW.copy(
    modelId = "gemini-3.1-pro-preview-customtools"
  )

  val GEMINI_3_1_FLASH_LITE_PREVIEW = GoogleModels.Gemini2_5FlashLite.toTrailblazeLlmModel(
    inputCostPerOneMillionTokens = 0.25,
    outputCostPerOneMillionTokens = 1.50,
    cachedInputDiscountMultiplier = GOOGLE_CACHED_INPUT_DISCOUNT_MULTIPLIER_90_PERCENT
  ).copy(
    modelId = "gemini-3.1-flash-lite-preview",
  )

  override val entries = listOf(
    GEMINI_2_5_PRO,
    GEMINI_3_1_FLASH_LITE_PREVIEW,
    GEMINI_3_1_PRO_PREVIEW,
    GEMINI_3_1_PRO_PREVIEW_CUSTOMTOOLS,
    GEMINI_3_0_FLASH_PREVIEW,
  )

  override val provider: TrailblazeLlmProvider = TrailblazeLlmProvider.GOOGLE
}
