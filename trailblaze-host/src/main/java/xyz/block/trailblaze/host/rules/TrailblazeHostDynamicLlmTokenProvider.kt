package xyz.block.trailblaze.host.rules

import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import xyz.block.trailblaze.llm.providers.TrailblazeDynamicLlmTokenProvider

/**
 * Retrieves LLM API tokens from environment variables for host testing (OPENAI_API_KEY).
 */
object TrailblazeHostDynamicLlmTokenProvider : TrailblazeDynamicLlmTokenProvider {
  override fun getApiTokenForProvider(llmProvider: TrailblazeLlmProvider): String? = when (llmProvider) {
    TrailblazeLlmProvider.ANTHROPIC -> System.getenv("ANTHROPIC_API_KEY")
    TrailblazeLlmProvider.OPENAI -> System.getenv("OPENAI_API_KEY")
    TrailblazeLlmProvider.GOOGLE -> System.getenv("GOOGLE_API_KEY")
    else -> error("Currently unsupported provider: $llmProvider")
  }
}
