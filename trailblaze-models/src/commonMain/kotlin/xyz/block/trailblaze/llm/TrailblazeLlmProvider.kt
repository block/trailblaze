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
    DATABRICKS_KOOG_LLM_PROVIDER.id -> DATABRICKS_KOOG_LLM_PROVIDER
    else -> error("Unknown LLM provider: $id")
  }

  companion object {
    val DATABRICKS_KOOG_LLM_PROVIDER = object : LLMProvider(
      id = "databricks",
      display = "Databricks",
    ) {}

    val OPENAI: TrailblazeLlmProvider = fromKoogLlmProvider(LLMProvider.OpenAI)
    val OLLAMA: TrailblazeLlmProvider = fromKoogLlmProvider(LLMProvider.Ollama)
    val DATABRICKS: TrailblazeLlmProvider = fromKoogLlmProvider(DATABRICKS_KOOG_LLM_PROVIDER)

    val ALL_PROVIDERS: List<TrailblazeLlmProvider> = listOf(
      DATABRICKS,
      OPENAI,
      OLLAMA,
    )

    fun fromKoogLlmProvider(koogLlmProvider: LLMProvider) = TrailblazeLlmProvider(
      id = koogLlmProvider.id,
      display = koogLlmProvider.display,
    )
  }
}
