package xyz.block.trailblaze.llm.providers

import xyz.block.trailblaze.llm.TrailblazeLlmProvider

/**
 * Provides API tokens dynamically for different LLM providers (e.g., from environment variables).
 */
interface TrailblazeDynamicLlmTokenProvider {
  fun getApiTokenForProvider(llmProvider: TrailblazeLlmProvider): String?
}
