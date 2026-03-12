package xyz.block.trailblaze.mcp.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.singleRunStrategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.mcp.McpToolRegistryProvider
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.llms.SingleLLMPromptExecutor
import kotlinx.coroutines.runBlocking
import xyz.block.trailblaze.devices.TrailblazeDevicePort
import xyz.block.trailblaze.llm.TrailblazeLlmModel

/**
 * Koog AI Agent that uses MCP tools via self-connection.
 *
 * This implementation uses Koog's native [AIAgent] class with:
 * - [McpToolRegistryProvider] to discover and execute tools via MCP protocol
 * - [singleRunStrategy] for tool-calling behavior
 * - Self-connection to Trailblaze's own MCP server (localhost:52525)
 *
 * ## Architecture
 *
 * ```
 * KoogMcpAgent
 *   └── Koog AIAgent
 *         └── McpToolRegistryProvider (MCP client)
 *               └── HTTP POST to localhost:52525/mcp
 *                     └── Trailblaze MCP Server
 *                           └── Device interaction
 * ```
 *
 * The agent genuinely IS an MCP client - it discovers tools via MCP protocol
 * and executes them via MCP calls. This creates perfect architectural symmetry
 * between external MCP clients (like Firebender) and the internal agent.
 *
 * ## Usage
 *
 * ```kotlin
 * val agent = KoogMcpAgent.create(
 *   llmClient = myLlmClient,
 *   llmModel = myLlmModel,
 * )
 * val result = agent.run("Tap the login button")
 * agent.close()
 * ```
 */
class KoogMcpAgent private constructor(
  private val agent: AIAgent<String, String>,
  private val toolRegistry: ToolRegistry,
) : AutoCloseable {

  /**
   * Runs the agent with the given objective.
   *
   * @param objective The task to accomplish (e.g., "Tap the login button")
   * @return The agent's response or result
   */
  suspend fun run(objective: String): String {
    return agent.run(objective)
  }

  /**
   * Closes the agent and releases resources.
   */
  override fun close() {
    runBlocking {
      agent.close()
    }
  }

  companion object {
    /** Default MCP server URL for self-connection. Overridable via TRAILBLAZE_MCP_URL env var. */
    val DEFAULT_MCP_URL: String =
      System.getenv("TRAILBLAZE_MCP_URL") ?: TrailblazeDevicePort.DEFAULT_MCP_URL

    /** Default client name for MCP connection */
    const val DEFAULT_CLIENT_NAME = "koog-mcp-agent"

    /** Default system prompt for the agent */
    private const val DEFAULT_SYSTEM_PROMPT = """You are a mobile UI automation assistant.

When given an objective, analyze the available tools and call the appropriate ones to accomplish the task.

Available tools include:
- viewHierarchy: Get the current UI structure
- getScreenshot: Capture the current screen
- tapOnPoint: Tap at specific coordinates
- inputText: Type text
- swipe: Swipe gesture

Look at the view hierarchy to understand the screen and find element coordinates.
Call tools to interact with the UI until the objective is complete."""

    /**
     * Creates a native Koog agent with MCP tools from self-connection.
     *
     * This connects to Trailblaze's own MCP server to discover and execute tools.
     * The agent uses Koog's native tool-calling strategy.
     *
     * @param llmClient The Koog LLM client for completions
     * @param llmModel The LLM model to use
     * @param mcpServerUrl The MCP server URL (default: localhost:52525)
     * @param systemPrompt Custom system prompt (optional)
     * @param maxAgentIterations Maximum tool-calling iterations
     * @return A ready-to-use Koog agent
     */
    suspend fun create(
      llmClient: LLMClient,
      llmModel: TrailblazeLlmModel,
      mcpServerUrl: String = DEFAULT_MCP_URL,
      systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
      maxAgentIterations: Int = 50,
    ): KoogMcpAgent {
      // Create MCP tool registry via self-connection
      val transport = McpToolRegistryProvider.defaultSseTransport(mcpServerUrl)
      val toolRegistry = McpToolRegistryProvider.fromTransport(
        transport = transport,
        name = DEFAULT_CLIENT_NAME,
        version = "1.0.0",
      )

      // Create Koog AI Agent with single-run strategy (tool calling)
      val koogModel = llmModel.toKoogLlmModel()
      val promptExecutor = SingleLLMPromptExecutor(llmClient)
      val agent = AIAgent(
        promptExecutor = promptExecutor,
        llmModel = koogModel,
        strategy = singleRunStrategy(),
        toolRegistry = toolRegistry,
        systemPrompt = systemPrompt,
        maxIterations = maxAgentIterations,
      )

      return KoogMcpAgent(agent, toolRegistry)
    }

    /**
     * Creates a tool registry from the MCP server without creating the full agent.
     *
     * Useful when you want to inspect available tools or use them with a custom agent.
     *
     * @param mcpServerUrl The MCP server URL
     * @return ToolRegistry with MCP tools
     */
    suspend fun createToolRegistry(
      mcpServerUrl: String = DEFAULT_MCP_URL,
    ): ToolRegistry {
      val transport = McpToolRegistryProvider.defaultSseTransport(mcpServerUrl)
      return McpToolRegistryProvider.fromTransport(
        transport = transport,
        name = DEFAULT_CLIENT_NAME,
        version = "1.0.0",
      )
    }
  }
}
