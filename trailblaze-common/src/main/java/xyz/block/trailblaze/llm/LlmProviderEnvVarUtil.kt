package xyz.block.trailblaze.llm

object LlmProviderEnvVarUtil {

  fun getEnvironmentVariableKeyForProvider(provider: TrailblazeLlmProvider): String? {
    return when (provider) {
      TrailblazeLlmProvider.OPENAI -> "OPENAI_API_KEY"
      TrailblazeLlmProvider.DATABRICKS -> "DATABRICKS_TOKEN"
      TrailblazeLlmProvider.GOOGLE -> "GOOGLE_API_KEY"
      TrailblazeLlmProvider.ANTHROPIC -> "ANTHROPIC_API_KEY"
      TrailblazeLlmProvider.OPEN_ROUTER -> "OPENROUTER_API_KEY"
      TrailblazeLlmProvider.OLLAMA -> null
      else -> null
    }
  }

  fun getEnvironmentVariableValueForProvider(provider: TrailblazeLlmProvider): String? {
    val key = getEnvironmentVariableKeyForProvider(provider)
    return key?.let { System.getenv(it) }
  }
}
