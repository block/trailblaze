package xyz.block.trailblaze.http

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Interceptor
import okhttp3.Response
import xyz.block.trailblaze.util.Console

/**
 * OkHttp interceptor that captures cached token counts from raw LLM API responses.
 *
 * LLM providers (Anthropic, OpenAI, Google) return cached token data in their API responses,
 * but the Koog library (as of 0.6.3) doesn't pass this through to
 * [ai.koog.prompt.message.ResponseMetaInfo.metadata]. This interceptor peeks at the raw JSON
 * response and extracts cached token fields so we can use them for accurate cost calculations.
 *
 * This is a temporary workaround until Koog implements KG-656.
 *
 * ## Correlation Strategy
 *
 * Each request gets a unique `X-Trailblaze-Correlation-Id` header for tracing. Cached token
 * data is stored keyed by a composite of (inputTokens, outputTokens) extracted from the
 * response — the same values that Koog puts into [ai.koog.prompt.message.ResponseMetaInfo].
 * This lets [xyz.block.trailblaze.logs.client.TrailblazeLogger] look up the correct cached
 * token data by matching on the token counts it already has, avoiding cross-session mix-ups.
 *
 * Response JSON formats handled:
 * - Anthropic: `usage.cache_read_input_tokens`, `usage.cache_creation_input_tokens`
 * - OpenAI Chat Completions: `usage.prompt_tokens_details.cached_tokens`
 * - OpenAI Responses API: `usage.input_tokens_details.cached_tokens`
 * - Google: `usageMetadata.cachedContentTokenCount`
 */
object CachedTokenCaptureInterceptor : Interceptor {

  private const val MAX_PEEK_BYTES = 512L * 1024 // 512KB
  private const val MAX_STORE_SIZE = 200
  private const val CORRELATION_HEADER = "X-Trailblaze-Correlation-Id"

  private val json = Json { ignoreUnknownKeys = true }

  /**
   * Cached token data keyed by "(inputTokens)_(outputTokens)" for correlation.
   * The same token counts are available in [ai.koog.prompt.message.ResponseMetaInfo],
   * so the caller can construct the same key for lookup.
   */
  private val tokenDataByUsageKey = ConcurrentHashMap<String, JsonObject>()

  /**
   * Looks up cached token metadata by the token counts from the LLM response.
   * This is the primary lookup method - it correlates the raw HTTP response data
   * with the Koog [ai.koog.prompt.message.ResponseMetaInfo] token counts.
   *
   * @return The cached token metadata, or null if no data was captured for these counts.
   */
  fun getByTokenCounts(inputTokens: Long, outputTokens: Long): JsonObject? {
    val key = usageKey(inputTokens, outputTokens)
    val result = tokenDataByUsageKey.remove(key)
    if (result != null) {
      Console.log("[CachedTokenInterceptor] HIT key=$key metadata=$result")
    } else {
      Console.log("[CachedTokenInterceptor] MISS key=$key (store has ${tokenDataByUsageKey.size} entries: ${tokenDataByUsageKey.keys})")
    }
    return result
  }

  override fun intercept(chain: Interceptor.Chain): Response {
    // Add correlation ID for tracing/debugging
    val correlationId = UUID.randomUUID().toString()
    val taggedRequest =
      chain.request().newBuilder().header(CORRELATION_HEADER, correlationId).build()

    val response = chain.proceed(taggedRequest)
    try {
      val peekedBody = response.peekBody(MAX_PEEK_BYTES)
      val bodyString = peekedBody.string()
      // Only attempt to parse JSON responses (skip SSE streams, HTML errors, etc.)
      if (bodyString.startsWith("{")) {
        val jsonObj = json.parseToJsonElement(bodyString).jsonObject
        val metadata = extractCachedTokenMetadata(jsonObj)
        if (metadata != null) {
          val key = extractUsageKey(jsonObj)
          if (key != null) {
            Console.log("[CachedTokenInterceptor] STORED key=$key metadata=$metadata")
            tokenDataByUsageKey[key] = metadata
            // Evict oldest entries if the store grows too large
            if (tokenDataByUsageKey.size > MAX_STORE_SIZE) {
              tokenDataByUsageKey.keys.take(tokenDataByUsageKey.size - MAX_STORE_SIZE).forEach {
                tokenDataByUsageKey.remove(it)
              }
            }
          }
        }
      }
    } catch (_: Exception) {
      // Parsing failures are expected for non-LLM responses - silently skip
    }
    return response
  }

  /** Builds a lookup key from input/output token counts. */
  private fun usageKey(inputTokens: Long, outputTokens: Long): String =
    "${inputTokens}_${outputTokens}"

  /**
   * Extracts the usage key (input_output token counts) from the raw response JSON. Handles
   * different field names across providers.
   */
  private fun extractUsageKey(responseJson: JsonObject): String? {
    // Anthropic & OpenAI: usage.input_tokens / usage.prompt_tokens
    val usage = responseJson["usage"]?.jsonObject
    if (usage != null) {
      val input =
        (usage["input_tokens"] ?: usage["prompt_tokens"] ?: usage["total_tokens"])
          ?.jsonPrimitive
          ?.longOrNull
      val output =
        (usage["output_tokens"] ?: usage["completion_tokens"])?.jsonPrimitive?.longOrNull ?: 0L
      if (input != null) return usageKey(input, output)
    }
    // Google: usageMetadata.promptTokenCount
    val googleUsage = responseJson["usageMetadata"]?.jsonObject
    if (googleUsage != null) {
      val input = googleUsage["promptTokenCount"]?.jsonPrimitive?.intOrNull?.toLong()
      val output = googleUsage["candidatesTokenCount"]?.jsonPrimitive?.intOrNull?.toLong() ?: 0L
      if (input != null) return usageKey(input, output)
    }
    return null
  }

  /**
   * Extracts cached token fields from the raw API response JSON. Returns a [JsonObject] with
   * normalized keys that [xyz.block.trailblaze.llm.CachedTokenExtractor] understands, or null if
   * no cached token data was found.
   */
  private fun extractCachedTokenMetadata(responseJson: JsonObject): JsonObject? {
    val result = mutableMapOf<String, JsonPrimitive>()

    // Anthropic: usage.cache_read_input_tokens, usage.cache_creation_input_tokens
    val usage = responseJson["usage"]?.jsonObject
    if (usage != null) {
      usage["cache_read_input_tokens"]?.jsonPrimitive?.longOrNull?.let {
        result["cache_read_input_tokens"] = JsonPrimitive(it)
      }
      usage["cache_creation_input_tokens"]?.jsonPrimitive?.longOrNull?.let {
        result["cache_creation_input_tokens"] = JsonPrimitive(it)
      }

      // OpenAI Chat Completions: usage.prompt_tokens_details.cached_tokens
      usage["prompt_tokens_details"]
        ?.jsonObject
        ?.get("cached_tokens")
        ?.jsonPrimitive
        ?.longOrNull
        ?.let { result["cached_tokens"] = JsonPrimitive(it) }

      // OpenAI Responses API: usage.input_tokens_details.cached_tokens
      usage["input_tokens_details"]
        ?.jsonObject
        ?.get("cached_tokens")
        ?.jsonPrimitive
        ?.longOrNull
        ?.let { result["cached_tokens"] = JsonPrimitive(it) }
    }

    // Google: usageMetadata.cachedContentTokenCount
    responseJson["usageMetadata"]
      ?.jsonObject
      ?.get("cachedContentTokenCount")
      ?.jsonPrimitive
      ?.longOrNull
      ?.let { result["cached_content_token_count"] = JsonPrimitive(it) }

    return if (result.isNotEmpty()) JsonObject(result) else null
  }
}
