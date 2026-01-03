package xyz.block.trailblaze.llm

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.message.ContentPart
import ai.koog.prompt.message.Message

/**
 * Estimates token usage breakdown by category.
 * 
 * Since LLM APIs typically only return total input token counts, we estimate the breakdown
 * based on the relative character counts of different message types. This uses a rough
 * approximation of 4 characters per token for English text.
 */
object LlmTokenBreakdownEstimator {
  private const val CHARS_PER_TOKEN = 4.0
  private const val IMAGE_TOKENS_ESTIMATE = 765 // Average tokens for a high-res image

  /**
   * Estimates token breakdown from the request data and total input tokens.
   * 
   * @param messages The messages sent to the LLM
   * @param toolDescriptors The tool descriptors sent to the LLM
   * @param totalInputTokens The actual total input tokens from the LLM response
   * @return Estimated breakdown of input tokens by category
   */
  fun estimateBreakdown(
    messages: List<Message>,
    toolDescriptors: List<ToolDescriptor>,
    totalInputTokens: Long,
  ): LlmInputTokenBreakdown {
    // Collect character counts and image counts by category
    var systemPromptChars = 0L
    var userPromptChars = 0L
    var totalImageCount = 0
    
    // Message counts by category
    var systemMessageCount = 0
    var userMessageCount = 0
    var assistantMessageCount = 0
    var toolMessageCount = 0

    // Track whether we've seen the initial system/user messages
    var initialPhase = true
    var seenFirstUser = false

    for (message in messages) {
      when (message) {
        is Message.System -> {
          systemMessageCount++
          systemPromptChars += estimateMessageCharCount(message)
        }
        is Message.User -> {
          userMessageCount++
          val (chars, images) = estimateUserMessageCharCountAndImages(message)
          totalImageCount += images
          if (initialPhase && !seenFirstUser) {
            // First few user messages are considered part of the initial prompt
            userPromptChars += chars
            seenFirstUser = true
          } else if (initialPhase && seenFirstUser) {
            // Still in initial phase, but after first user message
            userPromptChars += chars
            // Transition to history after second user message with view hierarchy
            if (hasViewHierarchy(message)) {
              initialPhase = false
            }
          }
        }
        is Message.Assistant -> {
          assistantMessageCount++
          initialPhase = false
        }
        is Message.Tool -> {
          toolMessageCount++
          initialPhase = false
        }
        is Message.Reasoning -> {
          initialPhase = false
        }
      }
    }

    // Estimate tool descriptor size based on name and description
    // We use a rough estimate since ToolDescriptor is not serializable
    val toolDescriptorChars = toolDescriptors.sumOf { tool ->
      val nameLength = tool.name.length
      val descLength = tool.description?.length ?: 0
      // Account for name, description, and JSON structure overhead (~200 chars per tool)
      (nameLength + descLength + 200).toLong()
    }

    // Calculate total character count
    val totalChars = systemPromptChars + userPromptChars + toolDescriptorChars
    val estimatedTextTokens = (totalChars / CHARS_PER_TOKEN).toLong()
    val estimatedImageTokens = totalImageCount * IMAGE_TOKENS_ESTIMATE

    // If we have actual token count from LLM, distribute proportionally
    val totalEstimated = estimatedTextTokens + estimatedImageTokens
    val scaleFactor = if (totalEstimated > 0) {
      totalInputTokens.toDouble() / totalEstimated
    } else {
      1.0
    }

    return LlmInputTokenBreakdown(
      systemPrompt = CategoryBreakdown(
        tokens = ((systemPromptChars / CHARS_PER_TOKEN) * scaleFactor).toLong(),
        count = systemMessageCount,
      ),
      userPrompt = CategoryBreakdown(
        tokens = ((userPromptChars / CHARS_PER_TOKEN) * scaleFactor).toLong(),
        count = userMessageCount,
      ),
      toolDescriptors = CategoryBreakdown(
        tokens = ((toolDescriptorChars / CHARS_PER_TOKEN) * scaleFactor).toLong(),
        count = toolDescriptors.size,
      ),
      images = CategoryBreakdown(
        tokens = (estimatedImageTokens * scaleFactor).toLong(),
        count = totalImageCount,
      ),
      assistantMessageCount = assistantMessageCount,
      toolMessageCount = toolMessageCount,
    )
  }

  private fun estimateMessageCharCount(message: Message): Long {
    return when (message) {
      is Message.System -> message.content.length.toLong()
      is Message.User -> {
        message.parts.filterIsInstance<ContentPart.Text>()
          .sumOf { it.text.length.toLong() }
      }
      is Message.Assistant -> message.content?.length?.toLong() ?: 0L
      is Message.Tool -> message.content.length.toLong()
      is Message.Reasoning -> message.content?.length?.toLong() ?: 0L
    }
  }

  private fun estimateUserMessageCharCountAndImages(message: Message.User): Pair<Long, Int> {
    val chars = message.parts.filterIsInstance<ContentPart.Text>()
      .sumOf { it.text.length.toLong() }
    val images = message.parts.filterIsInstance<ContentPart.Image>().size
    return Pair(chars, images)
  }

  private fun hasViewHierarchy(message: Message.User): Boolean {
    return message.parts.filterIsInstance<ContentPart.Text>()
      .any { it.text.contains("view_hierarchy") || it.text.contains("ViewHierarchy") }
  }
}
