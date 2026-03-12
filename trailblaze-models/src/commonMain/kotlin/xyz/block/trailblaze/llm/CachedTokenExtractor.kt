package xyz.block.trailblaze.llm

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Extracts cached token counts from LLM response metadata.
 *
 * Different providers report cached tokens under different keys. This utility
 * checks all known key patterns so that when the Koog library or providers
 * start passing this data through [ai.koog.prompt.message.ResponseMetaInfo.metadata],
 * we automatically pick it up.
 *
 * Known provider conventions:
 * - Anthropic: `cache_read_input_tokens`, `cache_creation_input_tokens`
 * - OpenAI: `cached_tokens` (from prompt_tokens_details)
 * - Google: `cached_content_token_count`
 */
object CachedTokenExtractor {

  private val CACHE_READ_KEYS = listOf(
    "cache_read_input_tokens", // Anthropic
    "cached_tokens", // OpenAI
    "cached_content_token_count", // Google
    "cache_read_tokens", // Generic
  )

  private val CACHE_CREATION_KEYS = listOf(
    "cache_creation_input_tokens", // Anthropic
    "cache_creation_tokens", // Generic
  )

  /**
   * Extracts the number of cache-read input tokens from response metadata.
   * Returns 0 if metadata is null or doesn't contain cached token info.
   */
  fun extractCacheReadTokens(metadata: JsonObject?): Long {
    return extractFirstMatchingKey(metadata, CACHE_READ_KEYS)
  }

  /**
   * Extracts the number of cache-creation input tokens from response metadata.
   * Returns 0 if metadata is null or doesn't contain cache creation info.
   */
  fun extractCacheCreationTokens(metadata: JsonObject?): Long {
    return extractFirstMatchingKey(metadata, CACHE_CREATION_KEYS)
  }

  private fun extractFirstMatchingKey(metadata: JsonObject?, keys: List<String>): Long {
    if (metadata == null) return 0L
    for (key in keys) {
      val value = metadata[key]?.jsonPrimitive?.longOrNull
      if (value != null) return value
    }
    return 0L
  }
}
