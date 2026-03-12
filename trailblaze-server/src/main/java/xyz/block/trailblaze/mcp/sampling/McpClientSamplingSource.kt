package xyz.block.trailblaze.mcp.sampling

import io.ktor.util.encodeBase64
import xyz.block.trailblaze.agent.SamplingResult
import xyz.block.trailblaze.agent.SamplingSource
import xyz.block.trailblaze.agent.ScreenContext
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor

/**
 * Sampling source that delegates to an external MCP client's LLM.
 *
 * This enables "borrowing" intelligence from clients like Goose or Claude Desktop
 * without needing to configure LLM credentials in Trailblaze. When an MCP client
 * connects that supports sampling, we can delegate LLM calls back to it.
 *
 * Supports two explicit sampling methods:
 * - [sampleText]: For text completions (delegated to client's LLM)
 * - [sampleToolCall]: For tool call responses (text-based JSON via MCP sampling)
 *
 * Note: MCP sampling doesn't have native tool calling support. Tool calling
 * is implemented by including tool definitions in the prompt and parsing
 * the JSON response from the client's LLM.
 *
 * @param mcpSamplingClient The existing MCP sampling client
 */
class McpClientSamplingSource(
  private val mcpSamplingClient: McpSamplingClient,
) : SamplingSource {

  override fun isAvailable(): Boolean = mcpSamplingClient.isSamplingSupported()

  override fun description(): String = "MCP Client Sampling"

  override suspend fun sampleText(
    systemPrompt: String,
    userMessage: String,
    screenshotBytes: ByteArray?,
    maxTokens: Int,
    traceId: TraceId?,
    screenContext: ScreenContext?,
  ): SamplingResult {
    // Note: traceId available for logging/correlation purposes
    if (!isAvailable()) {
      return SamplingResult.Error("MCP client does not support sampling")
    }

    val screenshotBase64 = screenshotBytes?.encodeBase64()

    return mcpSamplingClient.requestTextCompletion(
      systemPrompt = systemPrompt,
      userMessage = userMessage,
      maxTokens = maxTokens,
      screenshotBase64 = screenshotBase64,
    )
  }

  override suspend fun sampleToolCall(
    systemPrompt: String,
    userMessage: String,
    tools: List<TrailblazeToolDescriptor>,
    screenshotBytes: ByteArray?,
    maxTokens: Int,
    traceId: TraceId?,
    screenContext: ScreenContext?,
  ): SamplingResult {
    // Note: traceId available for logging/correlation purposes
    if (!isAvailable()) {
      return SamplingResult.Error("MCP client does not support sampling")
    }

    val screenshotBase64 = screenshotBytes?.encodeBase64()

    return mcpSamplingClient.requestToolCall(
      systemPrompt = systemPrompt,
      userMessage = userMessage,
      tools = tools,
      maxTokens = maxTokens,
      screenshotBase64 = screenshotBase64,
    )
  }
}
