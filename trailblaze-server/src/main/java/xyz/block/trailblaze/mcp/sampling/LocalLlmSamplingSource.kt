package xyz.block.trailblaze.mcp.sampling

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.reflect.asToolType
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.ContentPart
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.params.LLMParams
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.agent.AgentTier
import xyz.block.trailblaze.agent.SamplingResult
import xyz.block.trailblaze.agent.SamplingSource
import xyz.block.trailblaze.agent.ScreenContext
import xyz.block.trailblaze.agent.TokenUsage
import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.agent.model.AgentTaskStatusData
import xyz.block.trailblaze.llm.LlmRequestUsageAndCost
import xyz.block.trailblaze.llm.LlmTokenBreakdownEstimator
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TaskId
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.logs.model.TrailblazeLlmMessage
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.mcp.LlmCallStrategy
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool.Companion.toTrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor
import kotlin.reflect.full.starProjectedType
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Sampling source that uses Trailblaze's configured LLM via Koog.
 *
 * This is for standalone mode where users have configured their own LLM API keys.
 * When Trailblaze runs without an MCP client that supports sampling, it can use
 * this source to make LLM calls directly.
 *
 * Supports two explicit sampling methods:
 * - [sampleText]: For text completions (no tool calling)
 * - [sampleToolCall]: For tool call responses (forces LLM to call a tool)
 *
 * @param llmClient The Koog LLM client (e.g., OpenAI, Anthropic) - null if not configured
 * @param llmModel The model configuration - null if not configured
 */
class LocalLlmSamplingSource(
  private val llmClient: LLMClient?,
  private val llmModel: TrailblazeLlmModel?,
  private val logsRepo: LogsRepo? = null,
  private val sessionIdProvider: (() -> SessionId?)? = null,
  /** Timeout for each LLM call in milliseconds. Default: 120 seconds. */
  private val llmCallTimeoutMs: Long = DEFAULT_LLM_CALL_TIMEOUT_MS,
) : SamplingSource {

  override fun isAvailable(): Boolean = llmClient != null && llmModel != null

  override fun description(): String = "Local LLM (${llmModel?.modelId ?: "not configured"})"

  @OptIn(ExperimentalUuidApi::class)
  override suspend fun sampleText(
    systemPrompt: String,
    userMessage: String,
    screenshotBytes: ByteArray?,
    maxTokens: Int,
    traceId: TraceId?,
    screenContext: ScreenContext?,
  ): SamplingResult {
    val client = llmClient
      ?: return SamplingResult.Error("LLM client not configured")
    val model = llmModel
      ?: return SamplingResult.Error("LLM model not configured")
    val requestStartTime = Clock.System.now()

    // Log the LLM request for debugging
    Console.log("")
    Console.log("╔══════════════════════════════════════════════════════════════════════════════")
    Console.log("║ [LocalLlmSamplingSource] LLM REQUEST")
    Console.log("║ TraceId: ${traceId?.traceId ?: "none"}")
    Console.log("║ InnerSampling: ${LlmCallStrategy.DIRECT} (Trailblaze's configured LLM)")
    Console.log("║ Model: ${model.modelId}")
    Console.log("╠══════════════════════════════════════════════════════════════════════════════")
    Console.log("║ System prompt: ${systemPrompt.length} chars")
    Console.log("║ User message: ${userMessage.length} chars")
    Console.log("║ Has screenshot: ${screenshotBytes != null && screenshotBytes.isNotEmpty()}")
    Console.log("╠══════════════════════════════════════════════════════════════════════════════")
    Console.log("║ USER MESSAGE PREVIEW:")
    val truncatedPreview = truncateToFullLines(userMessage, maxChars = MAX_CONSOLE_PREVIEW_LENGTH)
    truncatedPreview.text.lines().forEach { Console.log("║   $it") }
    if (truncatedPreview.truncatedChars > 0) {
      Console.log("║   ...(${truncatedPreview.truncatedChars} more characters)...")
    }
    Console.log("╚══════════════════════════════════════════════════════════════════════════════")
    Console.log("")

    val messages = buildMessages(systemPrompt, userMessage, screenshotBytes)

    return try {
      val responses = withTimeout(llmCallTimeoutMs) {
        client.execute(
          prompt = Prompt(
            messages = messages,
            id = Uuid.random().toString(),
            params = LLMParams(temperature = null, speculation = null, schema = null),
          ),
          model = model.toKoogLlmModel(),
          tools = emptyList(), // No tools for text sampling
        )
      }

      // Extract token usage from the response
      val responseMetaInfo = responses.filterIsInstance<Message.Response>().lastOrNull()?.metaInfo
      val tokenUsage = if (responseMetaInfo != null) {
        TokenUsage(
          inputTokens = responseMetaInfo.inputTokensCount?.toLong() ?: 0L,
          outputTokens = responseMetaInfo.outputTokensCount?.toLong() ?: 0L,
        )
      } else {
        null
      }

      val responseText = responses.firstOrNull()?.let { response ->
        when (response) {
          is Message.Assistant -> response.content
          else -> response.toString()
        }
      } ?: ""

      // Log the response
      Console.log("")
      Console.log("╔══════════════════════════════════════════════════════════════════════════════")
      Console.log("║ [LocalLlmSamplingSource] LLM RESPONSE")
      Console.log("║ TraceId: ${traceId?.traceId ?: "none"}")
      Console.log("║ Tokens: input=${tokenUsage?.inputTokens ?: "?"}, output=${tokenUsage?.outputTokens ?: "?"}")
      Console.log("╠══════════════════════════════════════════════════════════════════════════════")
      Console.log("║ RESPONSE:")
      responseText.take(MAX_CONSOLE_RESPONSE_LENGTH).lines().forEach { Console.log("║   $it") }
      Console.log("╚══════════════════════════════════════════════════════════════════════════════")
      Console.log("")

      emitSamplingLog(
        traceId = traceId,
        model = model,
        systemPrompt = systemPrompt,
        userMessage = userMessage,
        completion = responseText,
        includedScreenshot = screenshotBytes != null && screenshotBytes.isNotEmpty(),
        screenshotBytes = screenshotBytes,
        tokenUsage = tokenUsage,
        successful = true,
        errorMessage = null,
        startTime = requestStartTime,
        screenContext = screenContext,
        koogMessages = messages, // For accurate token breakdown
      )

      SamplingResult.Text(
        completion = responseText,
        model = model.modelId,
        tokenUsage = tokenUsage,
      )
    } catch (e: Exception) {
      Console.log("[LocalLlmSamplingSource] sampleText ERROR (traceId=${traceId?.traceId}): ${e.message}")
      emitSamplingLog(
        traceId = traceId,
        model = model,
        systemPrompt = systemPrompt,
        userMessage = userMessage,
        completion = "",
        includedScreenshot = screenshotBytes != null && screenshotBytes.isNotEmpty(),
        screenshotBytes = screenshotBytes,
        tokenUsage = null,
        successful = false,
        errorMessage = e.message,
        startTime = requestStartTime,
        screenContext = screenContext,
        koogMessages = messages, // For token breakdown (even on error)
      )
      SamplingResult.Error("Local LLM text request failed: ${e.message}")
    }
  }

  @OptIn(ExperimentalUuidApi::class)
  override suspend fun sampleToolCall(
    systemPrompt: String,
    userMessage: String,
    tools: List<TrailblazeToolDescriptor>,
    screenshotBytes: ByteArray?,
    maxTokens: Int,
    traceId: TraceId?,
    screenContext: ScreenContext?,
  ): SamplingResult {
    // Note: traceId can be used for logging LLM requests for correlation with tool executions
    val client = llmClient
      ?: return SamplingResult.Error("LLM client not configured")
    val model = llmModel
      ?: return SamplingResult.Error("LLM model not configured")

    if (tools.isEmpty()) {
      return SamplingResult.Error("No tools provided for tool call sampling")
    }

    val messages = buildMessages(systemPrompt, userMessage, screenshotBytes)
    val requestStartTime = Clock.System.now()
    val koogTools = tools.map { it.toKoogToolDescriptor() }

    return try {
      val responses = withTimeout(llmCallTimeoutMs) {
        client.execute(
          prompt = Prompt(
            messages = messages,
            id = Uuid.random().toString(),
            params = LLMParams(
              temperature = null,
              speculation = null,
              schema = null,
              toolChoice = LLMParams.ToolChoice.Required, // Force tool use
            ),
          ),
          model = model.toKoogLlmModel(),
          tools = koogTools,
        )
      }

      // Extract token usage from the response
      val responseMetaInfo = responses.filterIsInstance<Message.Response>().lastOrNull()?.metaInfo
      val tokenUsage = if (responseMetaInfo != null) {
        TokenUsage(
          inputTokens = responseMetaInfo.inputTokensCount?.toLong() ?: 0L,
          outputTokens = responseMetaInfo.outputTokensCount?.toLong() ?: 0L,
        )
      } else {
        null
      }

      // Extract the tool call from response
      val toolCall = responses.filterIsInstance<Message.Tool>().firstOrNull()
        ?: return SamplingResult.Error(
          "LLM did not return a tool call. Response: ${responses.firstOrNull()}"
        )

      // Parse the tool arguments as JsonObject
      val arguments = try {
        Json.decodeFromString<JsonObject>(toolCall.content)
      } catch (e: Exception) {
        return SamplingResult.Error(
          "Failed to parse tool call arguments as JSON: ${e.message}. Content: ${toolCall.content}"
        )
      }

      emitSamplingLog(
        traceId = traceId,
        model = model,
        systemPrompt = systemPrompt,
        userMessage = userMessage,
        completion = toolCall.content,
        includedScreenshot = screenshotBytes != null && screenshotBytes.isNotEmpty(),
        screenshotBytes = screenshotBytes,
        tokenUsage = tokenUsage,
        successful = true,
        errorMessage = null,
        startTime = requestStartTime,
        screenContext = screenContext,
        toolCallName = toolCall.tool, // Log as tool call
        toolOptions = tools, // Log available tools
        koogMessages = messages, // For accurate token breakdown
        koogToolDescriptors = koogTools, // For accurate token breakdown
      )

      SamplingResult.ToolCall(
        toolName = toolCall.tool,
        arguments = arguments,
        stopReason = SamplingResult.StopReason.TOOL_USE,
        model = model.modelId,
        tokenUsage = tokenUsage,
      )
    } catch (e: Exception) {
      emitSamplingLog(
        traceId = traceId,
        model = model,
        systemPrompt = systemPrompt,
        userMessage = userMessage,
        completion = "",
        includedScreenshot = screenshotBytes != null && screenshotBytes.isNotEmpty(),
        screenshotBytes = screenshotBytes,
        tokenUsage = null,
        successful = false,
        errorMessage = e.message,
        startTime = requestStartTime,
        screenContext = screenContext,
        toolOptions = tools, // Log available tools even on error
        koogMessages = messages, // For token breakdown (even on error)
        koogToolDescriptors = koogTools,
      )
      SamplingResult.Error("Local LLM tool call request failed: ${e.message}")
    }
  }

  @OptIn(ExperimentalUuidApi::class)
  override suspend fun sampleToolCallWithKoogTools(
    systemPrompt: String,
    userMessage: String,
    koogTools: List<ToolDescriptor>,
    screenshotBytes: ByteArray?,
    maxTokens: Int,
    traceId: TraceId?,
    screenContext: ScreenContext?,
  ): SamplingResult {
    val client = llmClient
      ?: return SamplingResult.Error("LLM client not configured")
    val model = llmModel
      ?: return SamplingResult.Error("LLM model not configured")

    if (koogTools.isEmpty()) {
      return SamplingResult.Error("No tools provided for tool call sampling")
    }

    val messages = buildMessages(systemPrompt, userMessage, screenshotBytes)
    val requestStartTime = Clock.System.now()
    // Convert Koog tools to TrailblazeToolDescriptor for logging
    val toolOptionsForLog = koogTools.map { it.toTrailblazeToolDescriptor() }

    return try {
      val responses = withTimeout(llmCallTimeoutMs) {
        client.execute(
          prompt = Prompt(
            messages = messages,
            id = Uuid.random().toString(),
            params = LLMParams(
              temperature = null,
              speculation = null,
              schema = null,
              toolChoice = LLMParams.ToolChoice.Required, // Force tool use
            ),
          ),
          model = model.toKoogLlmModel(),
          tools = koogTools, // Use Koog tools directly - no lossy conversion!
        )
      }

      // Extract token usage from the response
      val responseMetaInfo = responses.filterIsInstance<Message.Response>().lastOrNull()?.metaInfo
      val tokenUsage = if (responseMetaInfo != null) {
        TokenUsage(
          inputTokens = responseMetaInfo.inputTokensCount?.toLong() ?: 0L,
          outputTokens = responseMetaInfo.outputTokensCount?.toLong() ?: 0L,
        )
      } else {
        null
      }

      // Extract the tool call from response
      val toolCall = responses.filterIsInstance<Message.Tool>().firstOrNull()
        ?: return SamplingResult.Error(
          "LLM did not return a tool call. Response: ${responses.firstOrNull()}"
        )

      // Parse the tool arguments as JsonObject
      val arguments = try {
        Json.decodeFromString<JsonObject>(toolCall.content)
      } catch (e: Exception) {
        return SamplingResult.Error(
          "Failed to parse tool call arguments as JSON: ${e.message}. Content: ${toolCall.content}"
        )
      }

      emitSamplingLog(
        traceId = traceId,
        model = model,
        systemPrompt = systemPrompt,
        userMessage = userMessage,
        completion = toolCall.content,
        includedScreenshot = screenshotBytes != null && screenshotBytes.isNotEmpty(),
        screenshotBytes = screenshotBytes,
        tokenUsage = tokenUsage,
        successful = true,
        errorMessage = null,
        startTime = requestStartTime,
        screenContext = screenContext,
        toolCallName = toolCall.tool, // Log as tool call
        toolOptions = toolOptionsForLog, // Log available tools
        koogMessages = messages, // For accurate token breakdown
        koogToolDescriptors = koogTools, // For accurate token breakdown
      )

      SamplingResult.ToolCall(
        toolName = toolCall.tool,
        arguments = arguments,
        stopReason = SamplingResult.StopReason.TOOL_USE,
        model = model.modelId,
        tokenUsage = tokenUsage,
      )
    } catch (e: Exception) {
      emitSamplingLog(
        traceId = traceId,
        model = model,
        systemPrompt = systemPrompt,
        userMessage = userMessage,
        completion = "",
        includedScreenshot = screenshotBytes != null && screenshotBytes.isNotEmpty(),
        screenshotBytes = screenshotBytes,
        tokenUsage = null,
        successful = false,
        errorMessage = e.message,
        startTime = requestStartTime,
        screenContext = screenContext,
        toolOptions = toolOptionsForLog, // Log available tools even on error
        koogMessages = messages, // For token breakdown (even on error)
        koogToolDescriptors = koogTools,
      )
      SamplingResult.Error("Local LLM tool call request failed: ${e.message}")
    }
  }

  /**
   * Builds the message list for an LLM request.
   */
  private fun buildMessages(
    systemPrompt: String,
    userMessage: String,
    screenshotBytes: ByteArray?,
  ): List<Message> = buildList {
    add(
      Message.System(
        content = systemPrompt,
        metaInfo = RequestMetaInfo(kotlin.time.Clock.System.now()),
      ),
    )
    add(
      Message.User(
        parts = buildList {
          add(ContentPart.Text(text = userMessage))
          if (screenshotBytes != null && screenshotBytes.isNotEmpty()) {
            add(
              ContentPart.Image(
                content = AttachmentContent.Binary.Bytes(screenshotBytes),
                format = "png",
              ),
            )
          }
        },
        metaInfo = RequestMetaInfo(kotlin.time.Clock.System.now()),
      ),
    )
  }

  private data class TruncatedText(val text: String, val truncatedChars: Int)

  private fun emitSamplingLog(
    traceId: TraceId?,
    model: TrailblazeLlmModel,
    systemPrompt: String,
    userMessage: String,
    completion: String,
    includedScreenshot: Boolean,
    screenshotBytes: ByteArray?,
    tokenUsage: TokenUsage?,
    successful: Boolean,
    errorMessage: String?,
    startTime: Instant,
    screenContext: ScreenContext?,
    /** Tool name if this was a tool call response (null for text responses) */
    toolCallName: String? = null,
    /** Tool options that were available to the LLM */
    toolOptions: List<TrailblazeToolDescriptor> = emptyList(),
    /** Koog messages for accurate token breakdown estimation */
    koogMessages: List<Message> = emptyList(),
    /** Koog tool descriptors for accurate token breakdown estimation */
    koogToolDescriptors: List<ToolDescriptor> = emptyList(),
  ) {
    val repo = logsRepo ?: return
    val sessionId = sessionIdProvider?.invoke() ?: return
    val durationMs = (Clock.System.now() - startTime).inWholeMilliseconds
    val resolvedTraceId = traceId ?: TraceId.generate(TraceId.Companion.TraceOrigin.LLM)

    // Calculate token breakdown using the standard estimator
    val inputTokens = tokenUsage?.inputTokens ?: 0L
    val outputTokens = tokenUsage?.outputTokens ?: 0L

    // Use the standard LlmTokenBreakdownEstimator if we have Koog messages
    val inputTokenBreakdown = if (inputTokens > 0 && koogMessages.isNotEmpty()) {
      LlmTokenBreakdownEstimator.estimateBreakdown(
        messages = koogMessages,
        toolDescriptors = koogToolDescriptors,
        totalInputTokens = inputTokens,
      )
    } else {
      null
    }

    val usageAndCost = LlmRequestUsageAndCost(
      trailblazeLlmModel = model,
      inputTokens = inputTokens,
      outputTokens = outputTokens,
      promptCost = inputTokens * model.inputCostPerOneMillionTokens / 1_000_000.0,
      completionCost = outputTokens * model.outputCostPerOneMillionTokens / 1_000_000.0,
      inputTokenBreakdown = inputTokenBreakdown,
    )

    // Save screenshot to disk if available
    val screenshotFile = if (screenshotBytes != null && screenshotBytes.isNotEmpty()) {
      try {
        repo.saveScreenshotBytes(sessionId, screenshotBytes, "png")
      } catch (e: Exception) {
        Console.log("[LocalLlmSamplingSource] Failed to save screenshot: ${e.message}")
        null
      }
    } else {
      null
    }

    // Emit TrailblazeLlmRequestLog for full insights (if we have screen context)
    val viewHierarchy = screenContext?.viewHierarchy
    if (viewHierarchy != null) {
      val agentTaskStatus = AgentTaskStatus.McpScreenAnalysis(
        statusData = AgentTaskStatusData(
          taskId = TaskId.generate(),
          prompt = userMessage.take(200),
          callCount = 1,
          taskStartTime = startTime,
          totalDurationMs = durationMs,
        ),
        recommendedAction = completion.take(100),
        confidence = if (successful) "HIGH" else "LOW",
      )

      val llmRequestLog = TrailblazeLog.TrailblazeLlmRequestLog(
        agentTaskStatus = agentTaskStatus,
        viewHierarchy = viewHierarchy,
        viewHierarchyFiltered = screenContext.viewHierarchyFiltered,
        instructions = userMessage,
        trailblazeLlmModel = model,
        llmMessages = listOf(
          TrailblazeLlmMessage(role = "system", message = systemPrompt.take(MAX_LOG_MESSAGE_LENGTH)),
          TrailblazeLlmMessage(role = "user", message = userMessage.take(MAX_LOG_MESSAGE_LENGTH)),
          // Use "tool_call" role when the LLM called a tool, "assistant" for text responses
          if (toolCallName != null) {
            TrailblazeLlmMessage(
              role = "tool_call",
              message = completion.take(MAX_LOG_MESSAGE_LENGTH),
              toolName = toolCallName,
            )
          } else {
            TrailblazeLlmMessage(role = "assistant", message = completion.take(MAX_LOG_MESSAGE_LENGTH))
          },
        ),
        llmResponse = emptyList(), // Raw responses not available at this level
        actions = emptyList(), // Actions parsed at higher level
        toolOptions = toolOptions,
        llmRequestUsageAndCost = usageAndCost,
        screenshotFile = screenshotFile,
        durationMs = durationMs,
        session = sessionId,
        timestamp = startTime,
        traceId = resolvedTraceId,
        deviceWidth = screenContext.deviceWidth,
        deviceHeight = screenContext.deviceHeight,
        requestContext = TrailblazeLog.LlmRequestContext(
          agentImplementation = AgentImplementation.TWO_TIER_AGENT,
          llmCallStrategy = LlmCallStrategy.DIRECT,
          agentTier = AgentTier.INNER,
        ),
      )
      repo.saveLogToDisk(llmRequestLog)
    }

    // Also emit McpSamplingLog for backward compatibility
    val mcpLog = TrailblazeLog.McpSamplingLog(
      llmStrategy = LlmCallStrategy.DIRECT,
      systemPrompt = systemPrompt.take(MAX_LOG_PROMPT_LENGTH),
      userMessage = userMessage.take(MAX_LOG_PROMPT_LENGTH),
      completion = completion.take(MAX_LOG_PROMPT_LENGTH),
      includedScreenshot = includedScreenshot,
      usageAndCost = usageAndCost,
      modelName = model.modelId,
      successful = successful,
      errorMessage = errorMessage,
      viewHierarchy = screenContext?.viewHierarchy,
      viewHierarchyFiltered = screenContext?.viewHierarchyFiltered,
      deviceWidth = screenContext?.deviceWidth ?: 0,
      deviceHeight = screenContext?.deviceHeight ?: 0,
      durationMs = durationMs,
      session = sessionId,
      timestamp = Clock.System.now(),
      traceId = resolvedTraceId,
    )

    repo.saveLogToDisk(mcpLog)
  }

  /**
   * Truncates text to approximately [maxChars] but ensures we end on a complete line.
   * Returns the truncated text and the number of characters that were cut off.
   */
  private fun truncateToFullLines(text: String, maxChars: Int): TruncatedText {
    if (text.length <= maxChars) {
      return TruncatedText(text, 0)
    }

    // Find the last newline before maxChars to ensure we have complete lines
    val lastNewlineIndex = text.lastIndexOf('\n', maxChars)
    val truncateAt = if (lastNewlineIndex > 0) lastNewlineIndex else maxChars

    return TruncatedText(
      text = text.substring(0, truncateAt),
      truncatedChars = text.length - truncateAt,
    )
  }

  companion object {
    /** Default timeout for LLM calls: 120 seconds */
    const val DEFAULT_LLM_CALL_TIMEOUT_MS = 120_000L

    // Truncation limits for log messages to prevent excessive log size
    private const val MAX_LOG_MESSAGE_LENGTH = 2000
    private const val MAX_LOG_PROMPT_LENGTH = 4000
    private const val MAX_CONSOLE_PREVIEW_LENGTH = 800
    private const val MAX_CONSOLE_RESPONSE_LENGTH = 1000

    /**
     * Converts a [TrailblazeToolDescriptor] to Koog's [ToolDescriptor].
     *
     * Note: Type information is not fully preserved since TrailblazeToolDescriptor
     * only stores type names as strings. We use String type as a safe default.
     */
    private fun TrailblazeToolDescriptor.toKoogToolDescriptor(): ToolDescriptor {
      val stringType = String::class.starProjectedType.asToolType()

      return ToolDescriptor(
        name = name,
        description = description ?: "",
        requiredParameters = requiredParameters.map { param ->
          ToolParameterDescriptor(
            name = param.name,
            type = stringType,
            description = param.description ?: "",
          )
        },
        optionalParameters = optionalParameters.map { param ->
          ToolParameterDescriptor(
            name = param.name,
            type = stringType,
            description = param.description ?: "",
          )
        },
      )
    }
  }
}
