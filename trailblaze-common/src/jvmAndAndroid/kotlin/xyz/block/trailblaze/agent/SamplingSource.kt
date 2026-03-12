package xyz.block.trailblaze.agent

import ai.koog.agents.core.tools.ToolDescriptor
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeToolParameterDescriptor

/**
 * Context about the screen state for enhanced logging.
 *
 * When provided to sampling methods, this context is included in log output
 * to help with debugging and analysis.
 */
data class ScreenContext(
  /** The view hierarchy tree at the time of sampling */
  val viewHierarchy: ViewHierarchyTreeNode?,
  /** Filtered/simplified view hierarchy (interactable elements only) */
  val viewHierarchyFiltered: ViewHierarchyTreeNode? = null,
  /** Device screen width in pixels */
  val deviceWidth: Int,
  /** Device screen height in pixels */
  val deviceHeight: Int,
)

/**
 * Abstraction for requesting LLM completions.
 *
 * This enables agents to get completions without knowing whether they
 * come from a local LLM configuration or from an external source.
 *
 * Two explicit sampling methods are provided:
 * - [sampleText]: For text completions (reasoning, summarization)
 * - [sampleToolCall]: For structured tool call responses (primary for agent loops)
 *
 * Implementations:
 * - LocalLlmSamplingSource: Uses locally configured LLM via Koog
 * - McpClientSamplingSource: Delegates to external client via MCP sampling
 */
interface SamplingSource {

  /**
   * Requests a text completion from the LLM.
   *
   * Use this for reasoning, summarization, or any response that doesn't
   * require tool execution.
   *
   * @param systemPrompt The system prompt for the completion
   * @param userMessage The user message / objective
   * @param screenshotBytes Optional PNG screenshot to include (for vision models)
   * @param maxTokens Maximum tokens in response (default: 1024)
   * @param traceId Optional trace ID for correlating LLM calls with tool executions
   * @param screenContext Optional screen context for enhanced logging (view hierarchy, dimensions)
   * @return Text completion result or error
   */
  suspend fun sampleText(
    systemPrompt: String,
    userMessage: String,
    screenshotBytes: ByteArray? = null,
    maxTokens: Int = 1024,
    traceId: TraceId? = null,
    screenContext: ScreenContext? = null,
  ): SamplingResult

  /**
   * Requests a tool call from the LLM.
   *
   * Use this for agent loops where the LLM decides which tool to call.
   * The LLM will be forced to respond with a tool call from the provided tools.
   *
   * @param systemPrompt The system prompt for the completion
   * @param userMessage The user message / objective
   * @param tools Available tools the LLM can call (dynamic based on session state)
   * @param screenshotBytes Optional PNG screenshot to include (for vision models)
   * @param maxTokens Maximum tokens in response (default: 1024)
   * @param traceId Optional trace ID for correlating LLM calls with tool executions
   * @param screenContext Optional screen context for enhanced logging (view hierarchy, dimensions)
   * @return Tool call result or error
   */
  suspend fun sampleToolCall(
    systemPrompt: String,
    userMessage: String,
    tools: List<TrailblazeToolDescriptor>,
    screenshotBytes: ByteArray? = null,
    maxTokens: Int = 1024,
    traceId: TraceId? = null,
    screenContext: ScreenContext? = null,
  ): SamplingResult

  /**
   * Requests a tool call from the LLM using Koog's native [ToolDescriptor].
   *
   * Prefer this over [sampleToolCall] with [TrailblazeToolDescriptor] when you have
   * Koog tools directly, as it preserves full type information (JsonObject, List, Boolean, etc.)
   * that would otherwise be lost in the conversion to TrailblazeToolDescriptor.
   *
   * @param systemPrompt The system prompt for the completion
   * @param userMessage The user message / objective
   * @param koogTools Available tools with full Koog type information
   * @param screenshotBytes Optional PNG screenshot to include (for vision models)
   * @param maxTokens Maximum tokens in response (default: 1024)
   * @param traceId Optional trace ID for correlating LLM calls with tool executions
   * @param screenContext Optional screen context for enhanced logging (view hierarchy, dimensions)
   * @return Tool call result or error
   */
  suspend fun sampleToolCallWithKoogTools(
    systemPrompt: String,
    userMessage: String,
    koogTools: List<ToolDescriptor>,
    screenshotBytes: ByteArray? = null,
    maxTokens: Int = 1024,
    traceId: TraceId? = null,
    screenContext: ScreenContext? = null,
  ): SamplingResult {
    // Default implementation falls back to sampleToolCall with lossy conversion.
    // Override this method to preserve full type information.
    val trailblazeTools = koogTools.map { koogTool ->
      TrailblazeToolDescriptor(
        name = koogTool.name,
        description = koogTool.description,
        requiredParameters = koogTool.requiredParameters.map { param ->
          TrailblazeToolParameterDescriptor(
            name = param.name,
            type = param.type.name,
            description = param.description,
          )
        },
        optionalParameters = koogTool.optionalParameters.map { param ->
          TrailblazeToolParameterDescriptor(
            name = param.name,
            type = param.type.name,
            description = param.description,
          )
        },
      )
    }
    return sampleToolCall(systemPrompt, userMessage, trailblazeTools, screenshotBytes, maxTokens, traceId, screenContext)
  }

  /**
   * Checks if this sampling source is available and configured.
   *
   * For LocalLlmSamplingSource: true if LLM credentials are configured
   * For McpClientSamplingSource: true if MCP client supports sampling capability
   */
  fun isAvailable(): Boolean

  /**
   * Human-readable description of this source (for logging/debugging).
   */
  fun description(): String

  /**
   * Legacy method for backward compatibility.
   * New code should use [sampleText] or [sampleToolCall] directly.
   */
  @Deprecated(
    message = "Use sampleText() or sampleToolCall() instead",
    replaceWith = ReplaceWith("sampleText(systemPrompt, userMessage, screenshotBytes, maxTokens, traceId)"),
  )
  suspend fun sample(
    systemPrompt: String,
    userMessage: String,
    screenshotBytes: ByteArray? = null,
    maxTokens: Int = 1024,
    traceId: TraceId? = null,
  ): SamplingResult = sampleText(systemPrompt, userMessage, screenshotBytes, maxTokens, traceId)
}
