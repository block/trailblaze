package xyz.block.trailblaze.llm

import ai.koog.prompt.llm.LLMProvider

data class TrailblazeLlmProvider(
  /** Should match Koog's LLM Provider ID */
  val llmProvider: LLMProvider,
) {

  val id = llmProvider.id

  companion object {
    val OPENAI = TrailblazeLlmProvider(
      llmProvider = LLMProvider.OpenAI,
    )
    val OLLAMA = TrailblazeLlmProvider(
      llmProvider = LLMProvider.Ollama,
    )
    val DATABRICKS = TrailblazeLlmProvider(
      llmProvider = DatabricksLLMProvider,
    )

    val ALL_PROVIDERS = listOf(
      DATABRICKS,
      OPENAI,
      OLLAMA,
    )

    fun getTrailblazeLlmProviderById(id: String): TrailblazeLlmProvider? = ALL_PROVIDERS.firstOrNull {
      it.id == id
    }
  }

  data object DatabricksLLMProvider : LLMProvider("databricks", "Databricks")
}
