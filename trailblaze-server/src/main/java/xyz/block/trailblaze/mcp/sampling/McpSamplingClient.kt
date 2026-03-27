package xyz.block.trailblaze.mcp.sampling

import xyz.block.trailblaze.api.ImageFormatDetector
import io.modelcontextprotocol.kotlin.sdk.server.ServerSession
import io.modelcontextprotocol.kotlin.sdk.types.CreateMessageRequest
import io.modelcontextprotocol.kotlin.sdk.types.CreateMessageRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.Role
import io.modelcontextprotocol.kotlin.sdk.types.SamplingMessage
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.block.trailblaze.agent.SamplingResult
import xyz.block.trailblaze.mcp.TrailblazeMcpSessionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor

/**
 * Client for MCP Sampling - allows the server to request LLM completions from the client.
 *
 * MCP Sampling enables the subagent pattern:
 * - Server captures current screen state
 * - Server sends sampling request to client with screen state
 * - Client's LLM processes the request
 * - Client returns the completion (text or tool call in JSON format)
 * - Server executes any requested tools
 *
 * This prevents context window bloat by using fresh context per step.
 *
 * Two explicit request methods are provided:
 * - [requestTextCompletion]: For text responses (reasoning, summarization)
 * - [requestToolCall]: For tool call responses (forces JSON tool call format)
 *
 * Note: MCP sampling doesn't have native tool calling support like direct LLM APIs.
 * Tool calling is implemented via text-based JSON responses where the LLM is
 * instructed to respond with a tool call in a specific format.
 *
 * @see <a href="https://modelcontextprotocol.io/specification/2025-11-25/client/sampling">MCP Sampling Spec</a>
 */
class McpSamplingClient(
  private val sessionContext: TrailblazeMcpSessionContext,
) {

  private val serverSession: ServerSession?
    get() = sessionContext.mcpServerSession

  /**
   * Checks if the connected client supports MCP Sampling.
   *
   * Sampling support is determined during the MCP handshake via clientCapabilities.
   * If the client doesn't support sampling, requests will return an error.
   */
  fun isSamplingSupported(): Boolean {
    val session = serverSession ?: return false
    return session.clientCapabilities?.sampling != null
  }

  /**
   * Requests a text completion from the client.
   *
   * Use this for reasoning, summarization, or any response that doesn't
   * require tool execution.
   *
   * @param systemPrompt The system prompt to use for the completion
   * @param userMessage The user message / objective to process
   * @param maxTokens Maximum tokens in the response (default: 1024)
   * @param screenshotBase64 Optional base64-encoded PNG screenshot to include
   * @return The text completion result or an error
   */
  suspend fun requestTextCompletion(
    systemPrompt: String,
    userMessage: String,
    maxTokens: Int = 1024,
    screenshotBase64: String? = null,
  ): SamplingResult {
    val session = serverSession
      ?: return SamplingResult.Error("No active MCP session")

    if (session.clientCapabilities?.sampling == null) {
      return SamplingResult.Error(
        "Client does not support MCP Sampling. " +
          "The client must advertise sampling capability during initialization.",
      )
    }

    val messages = buildMessages(userMessage, screenshotBase64)
    val request = CreateMessageRequest(
      params = CreateMessageRequestParams(
        messages = messages,
        systemPrompt = systemPrompt,
        maxTokens = maxTokens,
      ),
    )

    return try {
      val result = session.createMessage(request)
      val responseText = when (val content = result.content) {
        is TextContent -> content.text
        else -> result.content.toString()
      }

      SamplingResult.Text(
        completion = responseText,
        stopReason = result.stopReason?.value,
        model = result.model,
      )
    } catch (e: IllegalStateException) {
      SamplingResult.Error("Sampling not supported: ${e.message}")
    } catch (e: Exception) {
      SamplingResult.Error("Sampling request failed: ${e.message}")
    }
  }

  /**
   * Requests a tool call from the client.
   *
   * Use this for agent loops where the LLM decides which tool to call.
   * The LLM will be instructed to respond with a tool call in JSON format.
   *
   * Note: MCP sampling doesn't have native tool_choice support. Tool calling
   * is implemented by including tool definitions in the system prompt and
   * instructing the LLM to respond with JSON.
   *
   * @param systemPrompt The system prompt (tool definitions will be appended)
   * @param userMessage The user message / objective to process
   * @param tools Available tools the LLM can call
   * @param maxTokens Maximum tokens in the response (default: 1024)
   * @param screenshotBase64 Optional base64-encoded PNG screenshot to include
   * @return The tool call result or an error
   */
  suspend fun requestToolCall(
    systemPrompt: String,
    userMessage: String,
    tools: List<TrailblazeToolDescriptor>,
    maxTokens: Int = 1024,
    screenshotBase64: String? = null,
  ): SamplingResult {
    val session = serverSession
      ?: return SamplingResult.Error("No active MCP session")

    if (session.clientCapabilities?.sampling == null) {
      return SamplingResult.Error(
        "Client does not support MCP Sampling. " +
          "The client must advertise sampling capability during initialization.",
      )
    }

    if (tools.isEmpty()) {
      return SamplingResult.Error("No tools provided for tool call sampling")
    }

    // Build enhanced system prompt with tool definitions
    val enhancedSystemPrompt = buildToolCallSystemPrompt(systemPrompt, tools)
    val messages = buildMessages(userMessage, screenshotBase64)
    val request = CreateMessageRequest(
      params = CreateMessageRequestParams(
        messages = messages,
        systemPrompt = enhancedSystemPrompt,
        maxTokens = maxTokens,
      ),
    )

    return try {
      val result = session.createMessage(request)
      val responseText = when (val content = result.content) {
        is TextContent -> content.text
        else -> result.content.toString()
      }

      // Parse the JSON tool call from the response
      parseToolCallResponse(responseText, result.model)
    } catch (e: IllegalStateException) {
      SamplingResult.Error("Sampling not supported: ${e.message}")
    } catch (e: Exception) {
      SamplingResult.Error("Sampling request failed: ${e.message}")
    }
  }

  /**
   * Legacy method for backward compatibility.
   * New code should use [requestTextCompletion] or [requestToolCall].
   */
  @Deprecated(
    message = "Use requestTextCompletion() or requestToolCall() instead",
    replaceWith = ReplaceWith("requestTextCompletion(systemPrompt, userMessage, maxTokens, screenshotBase64)"),
  )
  suspend fun requestCompletion(
    systemPrompt: String,
    userMessage: String,
    maxTokens: Int = 1024,
    screenshotBase64: String? = null,
  ): SamplingResult = requestTextCompletion(systemPrompt, userMessage, maxTokens, screenshotBase64)

  /**
   * Builds the messages list for a sampling request.
   */
  private fun buildMessages(
    userMessage: String,
    screenshotBase64: String?,
  ): List<SamplingMessage> = buildList {
    if (screenshotBase64 != null) {
      add(
        SamplingMessage(
          role = Role.User,
          content = ImageContent(
            data = screenshotBase64,
            mimeType = ImageFormatDetector.detectMimeTypeFromBase64(screenshotBase64),
          ),
        ),
      )
    }
    add(
      SamplingMessage(
        role = Role.User,
        content = TextContent(text = userMessage),
      ),
    )
  }

  /**
   * Builds a system prompt that includes tool definitions and instructs
   * the LLM to respond with a tool call in JSON format.
   */
  private fun buildToolCallSystemPrompt(
    basePrompt: String,
    tools: List<TrailblazeToolDescriptor>,
  ): String = buildString {
    appendLine(basePrompt)
    appendLine()
    appendLine("AVAILABLE TOOLS:")
    tools.forEach { tool ->
      appendLine("- ${tool.name}: ${tool.description ?: "No description"}")
      if (tool.requiredParameters.isNotEmpty()) {
        appendLine("  Required parameters:")
        tool.requiredParameters.forEach { param ->
          appendLine("    - ${param.name} (${param.type}): ${param.description ?: ""}")
        }
      }
      if (tool.optionalParameters.isNotEmpty()) {
        appendLine("  Optional parameters:")
        tool.optionalParameters.forEach { param ->
          appendLine("    - ${param.name} (${param.type}): ${param.description ?: ""}")
        }
      }
    }
    appendLine()
    appendLine("RESPONSE FORMAT:")
    appendLine("You MUST respond with a JSON object in this exact format:")
    appendLine("""{"tool": "toolName", "args": {"param1": "value1", ...}}""")
    appendLine()
    appendLine("Respond with only the JSON object, no other text.")
  }

  /**
   * Parses a tool call from the LLM's JSON response.
   */
  private fun parseToolCallResponse(response: String, model: String?): SamplingResult {
    val jsonStr = extractJsonFromResponse(response)
      ?: return SamplingResult.Error("No JSON found in response: ${response.take(200)}...")

    return try {
      val json = Json.parseToJsonElement(jsonStr).jsonObject
      val toolName = json["tool"]?.jsonPrimitive?.content
        ?: return SamplingResult.Error("Missing 'tool' field in response: $jsonStr")
      val arguments = json["args"]?.jsonObject ?: JsonObject(emptyMap())

      SamplingResult.ToolCall(
        toolName = toolName,
        arguments = arguments,
        stopReason = SamplingResult.StopReason.TOOL_USE,
        model = model,
      )
    } catch (e: Exception) {
      SamplingResult.Error("Failed to parse tool call JSON: ${e.message}\nResponse: $jsonStr")
    }
  }

  /**
   * Extracts JSON from an LLM response that may include markdown or extra text.
   */
  private fun extractJsonFromResponse(response: String): String? {
    val trimmed = response.trim()

    // Try direct JSON parse — if it starts with {, find the matching closing brace
    if (trimmed.startsWith("{")) {
      return extractBalancedJson(trimmed, 0) ?: trimmed
    }

    // Try extracting from markdown code block
    val codeBlockMatch = Regex("""```(?:json)?\s*(\{[\s\S]*?\})\s*```""").find(trimmed)
    if (codeBlockMatch != null) {
      return codeBlockMatch.groupValues[1]
    }

    // Try finding a balanced JSON object anywhere in the text
    val braceIndex = trimmed.indexOf('{')
    if (braceIndex >= 0) {
      return extractBalancedJson(trimmed, braceIndex)
    }

    return null
  }

  /**
   * Extracts a balanced JSON object starting at [startIndex] by counting braces.
   * Handles nested objects like {"tool":"tap","args":{"x":100}}.
   */
  private fun extractBalancedJson(text: String, startIndex: Int): String? {
    if (startIndex >= text.length || text[startIndex] != '{') return null
    var depth = 0
    var inString = false
    var escape = false
    for (i in startIndex until text.length) {
      val c = text[i]
      if (escape) { escape = false; continue }
      if (c == '\\' && inString) { escape = true; continue }
      if (c == '"') { inString = !inString; continue }
      if (inString) continue
      if (c == '{') depth++
      if (c == '}') { depth--; if (depth == 0) return text.substring(startIndex, i + 1) }
    }
    return null
  }

  /**
   * Requests a completion with the current screen state included.
   * Legacy method for backward compatibility.
   */
  @Deprecated(
    message = "Use requestTextCompletion() or requestToolCall() with explicit tool definitions",
    replaceWith = ReplaceWith("requestTextCompletion(systemPrompt, userMessage, maxTokens, screenshotBase64)"),
  )
  suspend fun requestCompletionWithScreenState(
    objective: String,
    screenState: ScreenStateForSampling? = null,
  ): SamplingResult {
    val systemPrompt = """
      You are a mobile UI automation assistant. You can see the current screen state
      and should determine the best action to accomplish the given objective.
      
      Available actions:
      - tap(x, y) - Tap at specific coordinates
      - swipe(startX, startY, endX, endY) - Swipe gesture
      - type(text) - Enter text
      - pressBack() - Press the back button
      - complete() - Mark the objective as complete
      - fail(reason) - Mark as failed with reason
      
      Respond with the single best action to take next.
    """.trimIndent()

    val userMessage = buildString {
      appendLine("Objective: $objective")
      appendLine()
      if (screenState != null) {
        appendLine("Current Screen State:")
        appendLine(screenState.viewHierarchy)
      } else {
        appendLine("[No screen state available]")
      }
    }

    return requestTextCompletion(
      systemPrompt = systemPrompt,
      userMessage = userMessage,
      screenshotBase64 = screenState?.screenshotBase64,
    )
  }
}

/**
 * Screen state data formatted for sampling requests.
 */
data class ScreenStateForSampling(
  val viewHierarchy: String,
  val screenshotBase64: String? = null,
  val deviceWidth: Int = 0,
  val deviceHeight: Int = 0,
)
