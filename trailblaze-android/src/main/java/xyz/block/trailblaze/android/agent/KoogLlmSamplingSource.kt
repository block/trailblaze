package xyz.block.trailblaze.android.agent

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.agents.core.tools.ToolParameterDescriptor
import xyz.block.trailblaze.toolcalls.asToolType
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.ContentPart
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.params.LLMParams
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.agent.SamplingResult
import xyz.block.trailblaze.agent.SamplingSource
import xyz.block.trailblaze.agent.ScreenContext
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.api.ImageFormatDetector
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor
import kotlin.reflect.full.starProjectedType
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Implementation of [SamplingSource] using Koog's [LLMClient].
 *
 * This allows [xyz.block.trailblaze.agent.DirectMcpAgent] to make LLM calls directly
 * using any Koog-compatible LLM client (OpenAI, Ollama, etc.).
 * Works both on-host and on-device.
 *
 * Supports two explicit sampling methods:
 * - [sampleText]: For text completions (no tool calling)
 * - [sampleToolCall]: For tool call responses (uses native Koog tool calling)
 */
class KoogLlmSamplingSource(
  private val llmClient: LLMClient,
  private val llmModel: TrailblazeLlmModel,
) : SamplingSource {

  @OptIn(ExperimentalUuidApi::class)
  override suspend fun sampleText(
    systemPrompt: String,
    userMessage: String,
    screenshotBytes: ByteArray?,
    maxTokens: Int,
    traceId: TraceId?,
    screenContext: ScreenContext?,
  ): SamplingResult {
    val messages = buildMessages(systemPrompt, userMessage, screenshotBytes)

    return try {
      val responses = llmClient.execute(
        prompt = Prompt(
          messages = messages,
          id = Uuid.random().toString(),
          params = LLMParams(temperature = null, speculation = null, schema = null),
        ),
        model = llmModel.toKoogLlmModel(),
        tools = emptyList(), // No tools for text sampling
      )

      val responseText = responses.firstOrNull()?.let { response ->
        when (response) {
          is Message.Assistant -> response.content
          else -> response.toString()
        }
      } ?: ""

      SamplingResult.Text(
        completion = responseText,
        model = llmModel.modelId,
      )
    } catch (e: Exception) {
      SamplingResult.Error("LLM text request failed: ${e.message}")
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
    if (tools.isEmpty()) {
      return SamplingResult.Error("No tools provided for tool call sampling")
    }

    val messages = buildMessages(systemPrompt, userMessage, screenshotBytes)
    val koogTools = tools.map { it.toKoogToolDescriptor() }

    return try {
      val responses = llmClient.execute(
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
        model = llmModel.toKoogLlmModel(),
        tools = koogTools,
      )

      // Extract the tool call from response
      val toolCall = responses.filterIsInstance<Message.Tool>().firstOrNull()
        ?: return SamplingResult.Error(
          "LLM did not return a tool call. Response: ${responses.firstOrNull()}"
        )

      // Parse the tool arguments as JsonObject
      val arguments = try {
        Json.decodeFromString<JsonObject>(toolCall.content)
      } catch (e: Exception) {
        JsonObject(emptyMap())
      }

      SamplingResult.ToolCall(
        toolName = toolCall.tool,
        arguments = arguments,
        stopReason = SamplingResult.StopReason.TOOL_USE,
        model = llmModel.modelId,
      )
    } catch (e: Exception) {
      SamplingResult.Error("LLM tool call request failed: ${e.message}")
    }
  }

  override fun isAvailable(): Boolean {
    // Client is provided at construction, so always available
    return true
  }

  override fun description(): String {
    return "Koog LLM (${llmModel.modelId})"
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
          val supportsVision = llmModel.capabilities.contains(LLMCapability.Vision.Image)
          if (screenshotBytes != null && screenshotBytes.isNotEmpty() && supportsVision) {
            add(
              ContentPart.Image(
                content = AttachmentContent.Binary.Bytes(screenshotBytes),
                format = ImageFormatDetector.detectFormat(screenshotBytes).mimeSubtype,
              ),
            )
          }
        },
        metaInfo = RequestMetaInfo(kotlin.time.Clock.System.now()),
      ),
    )
  }

  companion object {
    /**
     * Converts a [TrailblazeToolDescriptor] to Koog's [ToolDescriptor].
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
