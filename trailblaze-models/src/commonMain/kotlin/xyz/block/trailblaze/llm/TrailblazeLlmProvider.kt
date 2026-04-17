package xyz.block.trailblaze.llm

import ai.koog.prompt.llm.LLMProvider
import kotlinx.serialization.Serializable

@Serializable
data class TrailblazeLlmProvider(
  val id: String,
  val display: String,
  val description: String? = null,
) {
  fun toKoogLlmProvider(): LLMProvider = when (id) {
    LLMProvider.OpenAI.id -> LLMProvider.OpenAI
    LLMProvider.OpenRouter.id -> LLMProvider.OpenRouter
    LLMProvider.Ollama.id -> LLMProvider.Ollama
    LLMProvider.Anthropic.id -> LLMProvider.Anthropic
    LLMProvider.Google.id -> LLMProvider.Google
    DATABRICKS_KOOG_LLM_PROVIDER.id -> DATABRICKS_KOOG_LLM_PROVIDER
    MCP_SAMPLING_KOOG_LLM_PROVIDER.id -> MCP_SAMPLING_KOOG_LLM_PROVIDER
    else -> customKoogProviderCache.getOrPut(id) {
      object : LLMProvider(id = id, display = display) {}
    }
  }

  companion object {
    /**
     * Cache of Koog [LLMProvider] instances for custom providers. Since [LLMProvider] uses
     * reference equality (no equals/hashCode override), we must return the same instance for a
     * given provider ID so that map lookups in [MultiLLMPromptExecutor] work correctly.
     */
    private val customKoogProviderCache = mutableMapOf<String, LLMProvider>()

    val DATABRICKS_KOOG_LLM_PROVIDER = object : LLMProvider(
      id = "databricks",
      display = "Databricks",
    ) {}

    /**
     * Special provider for MCP Sampling where the client performs the LLM call.
     * We don't know the actual provider/model used by the client.
     */
    val MCP_SAMPLING_KOOG_LLM_PROVIDER = object : LLMProvider(
      id = "mcp_sampling",
      display = "MCP Sampling (Client)",
    ) {}

    val ANTHROPIC: TrailblazeLlmProvider = fromKoogLlmProvider(LLMProvider.Anthropic)
    val DATABRICKS: TrailblazeLlmProvider = fromKoogLlmProvider(DATABRICKS_KOOG_LLM_PROVIDER)
    val GOOGLE: TrailblazeLlmProvider = fromKoogLlmProvider(LLMProvider.Google)
    val OLLAMA: TrailblazeLlmProvider = fromKoogLlmProvider(LLMProvider.Ollama)
    val OPENAI: TrailblazeLlmProvider = fromKoogLlmProvider(LLMProvider.OpenAI)
    val OPEN_ROUTER: TrailblazeLlmProvider = fromKoogLlmProvider(LLMProvider.OpenRouter)

    /**
     * Special provider for MCP Sampling where the MCP client performs the LLM call.
     * Used when Trailblaze delegates LLM calls back to clients like Goose or Claude Desktop.
     */
    val MCP_SAMPLING: TrailblazeLlmProvider = fromKoogLlmProvider(MCP_SAMPLING_KOOG_LLM_PROVIDER)

    val ALL_PROVIDERS: List<TrailblazeLlmProvider> = listOf(
      ANTHROPIC,
      DATABRICKS,
      GOOGLE,
      MCP_SAMPLING,
      OLLAMA,
      OPENAI,
      OPEN_ROUTER,
    )

    fun fromKoogLlmProvider(koogLlmProvider: LLMProvider) = TrailblazeLlmProvider(
      id = koogLlmProvider.id,
      display = koogLlmProvider.display,
    )
  }
}
