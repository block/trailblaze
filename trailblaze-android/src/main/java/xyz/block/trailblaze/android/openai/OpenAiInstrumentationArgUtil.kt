package xyz.block.trailblaze.android.openai

import xyz.block.trailblaze.android.InstrumentationArgUtil
import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import xyz.block.trailblaze.llm.config.LlmAuthResolver

/**
 * OpenAI-specific instrumentation argument utilities.
 * Separated from the base InstrumentationArgUtil to keep the core utilities provider-agnostic.
 */
object OpenAiInstrumentationArgUtil {

  /**
   * Gets the OpenAI API key from instrumentation arguments.
   * Checks the dynamic convention first, then legacy arg names.
   */
  fun getApiKeyFromInstrumentationArg(): String = if (InstrumentationArgUtil.isAiEnabled()) {
    val openAiApiKey =
      InstrumentationArgUtil.getInstrumentationArg(LlmAuthResolver.resolve(TrailblazeLlmProvider.OPENAI))
    if (openAiApiKey.isNullOrBlank()) {
      throw IllegalStateException("OpenAI API key not set (expected trailblaze.llm.auth.token.openai)")
    }
    openAiApiKey
  } else {
    "AI_DISABLED"
  }

  /**
   * Gets the OpenAI base URL from instrumentation arguments.
   * Checks the dynamic convention first, then legacy arg names.
   * Defaults to the standard OpenAI API endpoint if not provided.
   */
  fun getBaseUrlFromInstrumentationArg(): String {
    val baseUrl =
      InstrumentationArgUtil.getInstrumentationArg(LlmAuthResolver.BASE_URL_ARG)

    return if (baseUrl.isNullOrBlank()) {
      "https://api.openai.com"
    } else {
      // Ensure base URL ends with a slash
      if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
    }
  }
}
