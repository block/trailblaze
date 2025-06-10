package xyz.block.trailblaze.api

object JvmOpenAiApiKeyUtil {

  fun getApiKeyFromEnv(): String {
    val openAiApiKey = System.getenv("OPENAI_API_KEY")
    if (openAiApiKey == null || openAiApiKey.isBlank()) {
      throw IllegalStateException("OPENAI_API_KEY environment variable is not set")
    }
    return openAiApiKey
  }
}
