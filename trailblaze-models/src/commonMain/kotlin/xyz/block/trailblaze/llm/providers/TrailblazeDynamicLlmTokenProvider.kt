package xyz.block.trailblaze.llm.providers

import ai.koog.prompt.executor.clients.LLMClient
import io.ktor.client.HttpClient
import xyz.block.trailblaze.llm.TrailblazeLlmProvider

/**
 * Provides API tokens dynamically for different LLM providers (e.g., from environment variables).
 */
interface TrailblazeDynamicLlmTokenProvider {
  fun getApiTokenForProvider(llmProvider: TrailblazeLlmProvider): String?

  fun getLLMClientForProviderIfAvailable(
    trailblazeLlmProvider: TrailblazeLlmProvider,
    baseClient: HttpClient,
  ): LLMClient?
}
