package xyz.block.trailblaze.desktop

import xyz.block.trailblaze.llm.TrailblazeLlmProvider

/**
 * Status of an LLM token for a specific provider.
 */
sealed class LlmTokenStatus {
  /** Token is available and valid. */
  data class Available(val provider: TrailblazeLlmProvider) : LlmTokenStatus()

  /** Token is expired but may be refreshable. */
  data class Expired(val provider: TrailblazeLlmProvider) : LlmTokenStatus()

  /** No token is available for this provider. */
  data class NotAvailable(val provider: TrailblazeLlmProvider) : LlmTokenStatus()
}
