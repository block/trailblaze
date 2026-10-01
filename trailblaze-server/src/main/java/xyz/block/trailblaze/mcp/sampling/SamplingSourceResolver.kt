package xyz.block.trailblaze.mcp.sampling

import ai.koog.prompt.executor.clients.LLMClient
import xyz.block.trailblaze.agent.SamplingSource
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.mcp.TrailblazeMcpSessionContext
import xyz.block.trailblaze.report.utils.LogsRepo

/**
 * Resolves which sampling source to use based on availability.
 *
 * Priority order:
 * 1. MCP Client Sampling (if client supports it) - "borrow" external LLM
 * 2. Local LLM (if configured) - use Trailblaze's own LLM
 * 3. null - neither available
 *
 * This enables seamless switching between modes:
 * - When Goose calls runPrompt → use Goose's LLM via MCP sampling
 * - When running standalone → use configured local LLM
 *
 * @param sessionContext The MCP session context (for creating MCP sampling client)
 * @param llmClient Optional Koog LLM client (null if not configured)
 * @param llmModel Optional LLM model configuration (null if not configured)
 */
class SamplingSourceResolver(
  private val sessionContext: TrailblazeMcpSessionContext,
  private val llmClient: LLMClient?,
  private val llmModel: TrailblazeLlmModel?,
  private val logsRepo: LogsRepo? = null,
  private val sessionIdProvider: (() -> SessionId?)? = null,
) {

  private val mcpSource: McpClientSamplingSource by lazy {
    McpClientSamplingSource(McpSamplingClient(sessionContext))
  }

  private val localSource: LocalLlmSamplingSource by lazy {
    LocalLlmSamplingSource(
      llmClient = llmClient,
      llmModel = llmModel,
      logsRepo = logsRepo,
      sessionIdProvider = sessionIdProvider,
    )
  }

  /**
   * Returns the best available sampling source.
   *
   * Prefers MCP client sampling when available (no config needed on Trailblaze side).
   * Falls back to local LLM if MCP sampling not supported.
   *
   * @return The best available source, or null if neither is available
   */
  fun resolve(): SamplingSource? {
    return when {
      mcpSource.isAvailable() -> mcpSource
      localSource.isAvailable() -> localSource
      else -> null
    }
  }

  /**
   * Returns the resolved source or throws with a helpful error message.
   *
   * @throws IllegalStateException if no sampling source is available
   */
  fun resolveOrThrow(): SamplingSource {
    return resolve() ?: error(
      "No sampling source available. Either:\n" +
        "1. Connect an MCP client that supports sampling (Goose, Claude Desktop), or\n" +
        "2. Configure an LLM in Trailblaze settings (API key + model)",
    )
  }

  /**
   * Forces use of the MCP client sampling source.
   *
   * @return The MCP sampling source, or null if not available
   */
  fun resolveMcpSource(): SamplingSource? {
    return if (mcpSource.isAvailable()) mcpSource else null
  }

  /**
   * Forces use of the local LLM sampling source.
   *
   * @return The local LLM source, or null if not available
   */
  fun resolveLocalSource(): SamplingSource? {
    return if (localSource.isAvailable()) localSource else null
  }

  /**
   * Returns a description of available sources for debugging.
   */
  fun describeAvailability(): String = buildString {
    appendLine("Sampling source availability:")
    appendLine("  - MCP Client: ${if (mcpSource.isAvailable()) "available" else "not available"}")
    appendLine(
      "  - Local LLM: ${
        if (localSource.isAvailable()) {
          "available (${localSource.description()})"
        } else {
          "not configured"
        }
      }",
    )
    resolve()?.let { appendLine("  → Using: ${it.description()}") }
      ?: appendLine("  → No source available")
  }
}
