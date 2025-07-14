package xyz.block.trailblaze.android.openai

import xyz.block.trailblaze.android.InstrumentationArgUtil

/**
 * OpenAI-specific instrumentation argument utilities.
 * Separated from the base InstrumentationArgUtil to keep the core utilities provider-agnostic.
 */
object OpenAiInstrumentationArgUtil {

  /**
   * Gets the OpenAI API key from instrumentation arguments.
   * Supports both OPENAI_API_KEY and openAiApiKey argument variants.
   */
  fun getApiKeyFromInstrumentationArg(): String = if (InstrumentationArgUtil.isAiEnabled()) {
    val openAiApiKey = InstrumentationArgUtil.getInstrumentationArg("OPENAI_API_KEY")
      ?: InstrumentationArgUtil.getInstrumentationArg("openAiApiKey")
    if (openAiApiKey.isNullOrBlank()) {
      throw IllegalStateException("OPENAI_API_KEY environment variable is not set")
    }
    openAiApiKey
  } else {
    "AI_DISABLED"
  }

  /**
   * Gets the OpenAI base URL from instrumentation arguments.
   * Supports both OPENAI_BASE_URL and openAiBaseUrl argument variants.
   * Defaults to the standard OpenAI API endpoint if not provided.
   */
  fun getBaseUrlFromInstrumentationArg(): String {
    val baseUrl = InstrumentationArgUtil.getInstrumentationArg("OPENAI_BASE_URL")
      ?: InstrumentationArgUtil.getInstrumentationArg("openAiBaseUrl")

    return if (baseUrl.isNullOrBlank()) {
      "https://api.openai.com/v1/"
    } else {
      // Ensure base URL ends with a slash
      if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
    }
  }
}
