package xyz.block.trailblaze.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Token usage information from an LLM response.
 *
 * @property inputTokens Number of tokens in the input/prompt
 * @property outputTokens Number of tokens in the output/completion
 */
@Serializable
data class TokenUsage(
  val inputTokens: Long,
  val outputTokens: Long,
)

/**
 * Result of an LLM sampling request.
 *
 * Used by agent implementations to get LLM completions for decision-making.
 * Supports two explicit response types:
 *
 * - [Text]: For text completions (reasoning, summarization)
 * - [ToolCall]: For structured tool call responses (primary for agent loops)
 *
 * @see SamplingSource.sampleText
 * @see SamplingSource.sampleToolCall
 */
@Serializable
sealed class SamplingResult {

  /** Token usage for cost tracking. Null if not available. */
  abstract val tokenUsage: TokenUsage?

  /**
   * Text completion from the LLM.
   *
   * Use this for reasoning, summarization, or any response that doesn't
   * require tool execution.
   *
   * @property completion The generated text response
   * @property stopReason Why generation stopped (e.g., "endTurn", "maxTokens")
   * @property model The model that generated the response (e.g., "claude-3-opus")
   * @property tokenUsage Token usage for cost tracking
   */
  @Serializable
  data class Text(
    val completion: String,
    val stopReason: String? = null,
    val model: String? = null,
    override val tokenUsage: TokenUsage? = null,
  ) : SamplingResult()

  /**
   * Tool call from the LLM.
   *
   * Use this for agent loops where the LLM decides which tool to call.
   * The reasoning is typically captured in the tool's `reasoning` parameter
   * rather than as a separate field.
   *
   * @property toolName The name of the tool to call
   * @property arguments The tool arguments as a JSON object
   * @property stopReason Why generation stopped (e.g., "tool_use", "endTurn")
   * @property model The model that generated the response
   * @property tokenUsage Token usage for cost tracking
   */
  @Serializable
  data class ToolCall(
    val toolName: String,
    val arguments: JsonObject,
    val stopReason: String? = null,
    val model: String? = null,
    override val tokenUsage: TokenUsage? = null,
  ) : SamplingResult()

  /**
   * Error during sampling.
   *
   * @property message Human-readable error description
   */
  @Serializable
  data class Error(
    val message: String,
    override val tokenUsage: TokenUsage? = null,
  ) : SamplingResult()

  companion object {
    /**
     * Alias for backward compatibility with code using `SamplingResult.Success`.
     * New code should use [Text] directly.
     */
    @Suppress("FunctionName")
    fun Success(
      completion: String,
      stopReason: String? = null,
      model: String? = null,
    ): Text = Text(completion, stopReason, model)
  }

  /**
   * Constants for stop reason values.
   *
   * These align with MCP SDK's StopReason values plus custom extensions
   * for Trailblaze tool calling.
   *
   * @see <a href="https://modelcontextprotocol.io/specification/2025-11-25/client/sampling">MCP Sampling Spec</a>
   */
  object StopReason {
    /** Model naturally finished its response. */
    const val END_TURN = "endTurn"

    /** Model stopped due to a stop sequence. */
    const val STOP_SEQUENCE = "stopSequence"

    /** Model stopped due to reaching max tokens. */
    const val MAX_TOKENS = "maxTokens"

    /**
     * Model stopped to call a tool.
     *
     * Note: This is a Trailblaze extension - MCP sampling doesn't natively
     * support tool calling, so this is used for parsed JSON tool calls.
     */
    const val TOOL_USE = "tool_use"
  }
}
