package xyz.block.trailblaze.llm

import ai.koog.prompt.llm.LLMProvider
import kotlinx.serialization.Serializable

@Serializable
data class TrailblazeLlmProvider(
  val id: String,
  val display: String,
) {
  fun toKoogLlmProvider(): LLMProvider = when (id) {
    LLMProvider.OpenAI.id -> LLMProvider.OpenAI
    LLMProvider.Ollama.id -> LLMProvider.Ollama
    LLMProvider.Anthropic.id -> LLMProvider.Anthropic
    LLMProvider.Google.id -> LLMProvider.Google
    DATABRICKS_KOOG_LLM_PROVIDER.id -> DATABRICKS_KOOG_LLM_PROVIDER
    else -> error("Unknown LLM provider: $id")
  }

  companion object {
    val DATABRICKS_KOOG_LLM_PROVIDER = object : LLMProvider(
      id = "databricks",
      display = "Databricks",
    ) {}

    val ANTHROPIC: TrailblazeLlmProvider = fromKoogLlmProvider(LLMProvider.Anthropic)
    val DATABRICKS: TrailblazeLlmProvider = fromKoogLlmProvider(DATABRICKS_KOOG_LLM_PROVIDER)
    val GOOGLE: TrailblazeLlmProvider = fromKoogLlmProvider(LLMProvider.Google)
    val OLLAMA: TrailblazeLlmProvider = fromKoogLlmProvider(LLMProvider.Ollama)
    val OPENAI: TrailblazeLlmProvider = fromKoogLlmProvider(LLMProvider.OpenAI)

    val ALL_PROVIDERS: List<TrailblazeLlmProvider> = listOf(
      ANTHROPIC,
      DATABRICKS,
      GOOGLE,
      OLLAMA,
      OPENAI,
    )

    fun fromKoogLlmProvider(koogLlmProvider: LLMProvider) = TrailblazeLlmProvider(
      id = koogLlmProvider.id,
      display = koogLlmProvider.display,
    )
  }
}
