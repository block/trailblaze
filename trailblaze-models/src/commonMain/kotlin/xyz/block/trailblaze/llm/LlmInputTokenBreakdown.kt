package xyz.block.trailblaze.llm

import kotlinx.serialization.Serializable

/**
 * Represents a category with both token count and item count.
 */
@Serializable
data class CategoryBreakdown(
  val tokens: Long,
  val count: Int,
)

/**
 * Breakdown of input token usage by category.
 * Since LLM APIs typically only return total token counts, we estimate the breakdown
 * based on the relative character counts of different message types.
 */
@Serializable
data class LlmInputTokenBreakdown(
  val systemPrompt: CategoryBreakdown,
  val userPrompt: CategoryBreakdown,
  val toolDescriptors: CategoryBreakdown,
  val images: CategoryBreakdown,
  val assistantMessageCount: Int,
  val toolMessageCount: Int,
) {
  val totalEstimatedTokens: Long
    get() = systemPrompt.tokens + userPrompt.tokens + toolDescriptors.tokens + images.tokens
  
  val totalMessageCount: Int
    get() = systemPrompt.count + userPrompt.count + assistantMessageCount + toolMessageCount

  fun debugString(): String = buildString {
    appendLine("Input Token Breakdown:")
    appendLine("- System Prompt: ${systemPrompt.tokens} tokens (${percentageOf(systemPrompt.tokens)}%) - ${systemPrompt.count} messages")
    appendLine("- User Prompt: ${userPrompt.tokens} tokens (${percentageOf(userPrompt.tokens)}%) - ${userPrompt.count} messages")
    appendLine("- Tool Descriptors: ${toolDescriptors.tokens} tokens (${percentageOf(toolDescriptors.tokens)}%) - ${toolDescriptors.count} tools")
    appendLine("  - Assistant: $assistantMessageCount messages")
    appendLine("  - Tool: $toolMessageCount messages")
    appendLine("- Images: ${images.tokens} tokens (${percentageOf(images.tokens)}%) - ${images.count} images")
    appendLine("Total Estimated: $totalEstimatedTokens tokens, $totalMessageCount messages")
  }

  private fun percentageOf(tokens: Long): String {
    if (totalEstimatedTokens == 0L) return "0.0"
    val percentage = (tokens.toDouble() / totalEstimatedTokens) * 100
    return (kotlin.math.round(percentage * 10) / 10).toString()
  }
}
