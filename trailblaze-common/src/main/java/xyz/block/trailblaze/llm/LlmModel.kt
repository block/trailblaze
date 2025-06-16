package xyz.block.trailblaze.llm

/**
 * Sources:
 *  https://platform.openai.com/docs/pricing
 *  https://github.com/BerriAI/litellm/blob/main/model_prices_and_context_window.json
 */
enum class LlmModel(
  val id: String,
  val alias: String,
  val inputCostPerOneMillionTokens: Double,
  val outputCostPerOneMillionTokens: Double,
  val maxInputTokens: Long,
  val maxOutputTokens: Long,
) {

  GPT_4_1(
    id = "gpt-4.1-2025-04-14",
    alias = "gpt-4.1",
    inputCostPerOneMillionTokens = 2.00,
    outputCostPerOneMillionTokens = 8.00,
    maxInputTokens = 1_047_576,
    maxOutputTokens = 32_768,
  ),
  GPT_4_1_MINI(
    id = "gpt-4.1-mini-2025-04-14",
    alias = "gpt-4.1-mini",
    inputCostPerOneMillionTokens = 0.40,
    outputCostPerOneMillionTokens = 1.60,
    maxInputTokens = 1_047_576,
    maxOutputTokens = 32_768,
  ),
  GPT_4O(
    id = "gpt-4o-2024-08-06",
    alias = "gpt-4o",
    inputCostPerOneMillionTokens = 2.50,
    outputCostPerOneMillionTokens = 10.00,
    maxInputTokens = 128_000,
    maxOutputTokens = 16_384,
  ),
  GPT_4O_MINI(
    id = "gpt-4o-mini-2024-07-18",
    alias = "gpt-4o-mini",
    inputCostPerOneMillionTokens = 0.15,
    outputCostPerOneMillionTokens = 0.60,
    maxInputTokens = 128_000,
    maxOutputTokens = 16_384,
  ),
  O4_MINI(
    id = "o4-mini-2025-04-16",
    alias = "o4-mini",
    inputCostPerOneMillionTokens = 1.10,
    outputCostPerOneMillionTokens = 4.40,
    maxInputTokens = 200_000,
    maxOutputTokens = 100_000,
  ),
  ;

  companion object {
    fun getModelByName(modelName: String): LlmModel = LlmModel.entries.firstOrNull {
      it.id == modelName || it.alias == modelName
    }
      ?: error("Unknown Model: $modelName. Please add an entry in LlmModel.kt")
  }
}
