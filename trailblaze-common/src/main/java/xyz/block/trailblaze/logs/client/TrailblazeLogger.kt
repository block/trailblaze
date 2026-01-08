package xyz.block.trailblaze.logs.client

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.ContentPart
import ai.koog.prompt.message.Message
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.agent.model.PromptRecordingResult
import xyz.block.trailblaze.agent.model.PromptStepStatus
import xyz.block.trailblaze.api.ImageFormatDetector
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.llm.LlmRequestUsageAndCost
import xyz.block.trailblaze.llm.LlmTokenBreakdownEstimator
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.logs.client.TrailblazeLog.TrailblazeLlmRequestLog.Action
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.logs.model.TrailblazeLlmMessage
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool.Companion.toTrailblazeToolDescriptor
import xyz.block.trailblaze.yaml.PromptStep

/**
 * Stateless logger that emits log events for a specific session.
 * All methods require an explicit [TrailblazeSession] parameter.
 *
 * This implementation separates session management from logging.
 * Use [TrailblazeSessionManager] to manage session lifecycle.
 *
 * ## Key Features
 * - **No session state**: Session is passed as parameter to each method
 * - **Immutability**: Returns updated session when state changes (e.g., fallback)
 * - **Thread-safe**: Stateless design eliminates synchronization needs
 * - **Testable**: Easy to test with explicit session parameter
 *
 * ## Usage
 * ```kotlin
 * val logEmitter = LogEmitterBuilder()
 *     .add { log -> sendToServer(log) }
 *     .build()
 * val logger = TrailblazeLogger(logEmitter, screenStateLogger)
 *
 * val session = sessionManager.startSession("MyTest")
 * logger.log(session, someLog)
 * ```
 *
 * @param logEmitter The emitter for delivering logs to backends
 * @param screenStateLogger The logger for storing screenshot data
 */
class TrailblazeLogger(
  private val logEmitter: LogEmitter,
  private val screenStateLogger: ScreenStateLogger,
) {

  /**
   * Emits a log event for the given session.
   *
   * @param session The session this log belongs to
   * @param log The log event to emit
   */
  fun log(session: TrailblazeSession, log: TrailblazeLog) {
    logEmitter.emit(log)
  }

  /**
   * Logs a screenshot and returns the filename where it was stored.
   *
   * @param session The session this screenshot belongs to
   * @param screenState The screen state containing screenshot bytes
   * @return The filename where the screenshot was stored, or empty string if no screenshot
   */
  fun logScreenState(session: TrailblazeSession, screenState: ScreenState): String {
    val screenshotBytes = screenState.screenshotBytes ?: return ""
    val imageFormat = ImageFormatDetector.detectFormat(screenshotBytes)
    val screenshotFileName = "${session.sessionId.value}_${
      Clock.System.now().toEpochMilliseconds()
    }.${imageFormat.fileExtension}"
    val screenStateLog = TrailblazeScreenStateLog(
      fileName = screenshotFileName,
      sessionId = session.sessionId,
      screenState = screenState,
    )
    return screenStateLogger.logScreenState(screenStateLog)
  }

  /**
   * Logs an attempt to use AI fallback.
   * Returns a new session with fallback marked as used.
   *
   * ## Important
   * This method returns an **updated session** with the fallback flag set.
   * You must use the returned session for subsequent operations:
   *
   * ```kotlin
   * var currentSession = session
   * currentSession = logger.logAttemptAiFallback(currentSession, promptStep, recordingResult)
   * // Use currentSession from now on, not the original session
   * ```
   *
   * @param session The current session
   * @param promptStep The prompt step that failed
   * @param recordingResult The recording failure result
   * @return A new session with fallback marked as used
   */
  fun logAttemptAiFallback(
    session: TrailblazeSession,
    promptStep: PromptStep,
    recordingResult: PromptRecordingResult.Failure,
  ): TrailblazeSession {
    log(
      session,
      TrailblazeLog.AttemptAiFallbackLog(
        promptStep = promptStep,
        session = session.sessionId,
        timestamp = Clock.System.now(),
        recordingResult = recordingResult,
      ),
    )
    return session.withFallbackUsed()
  }

  /**
   * Logs an LLM request with all associated metadata.
   *
   * This includes:
   * - LLM messages (request and response)
   * - Tool descriptors and actions
   * - Token usage and cost
   * - Screenshot
   * - View hierarchy
   * - Timing information
   *
   * @param session The current session
   * @param koogLlmRequestMessages The request messages sent to the LLM
   * @param stepStatus The current prompt step status
   * @param trailblazeLlmModel The LLM model used
   * @param response The LLM response messages
   * @param startTime When the request started
   * @param traceId The trace ID for this request
   * @param toolDescriptors The tools available to the LLM
   */
  fun logLlmRequest(
    session: TrailblazeSession,
    koogLlmRequestMessages: List<Message>,
    stepStatus: PromptStepStatus,
    trailblazeLlmModel: TrailblazeLlmModel,
    response: List<Message.Response>,
    startTime: Instant,
    traceId: TraceId,
    toolDescriptors: List<ToolDescriptor>,
  ) {
    val toolMessages = response.filterIsInstance<Message.Tool>()
    val screenshotFilename = logScreenState(session, stepStatus.currentScreenState)

    val toolOptions = toolDescriptors
      .map { it.toTrailblazeToolDescriptor() }
      .sortedBy { it.name }
    val endTime = Clock.System.now()

    val usage = response.last().metaInfo
    val inputTokens = usage.inputTokensCount?.toLong() ?: 0L
    val outputTokens = usage.outputTokensCount?.toLong() ?: 0L

    val tokenBreakdown = if (inputTokens > 0) {
      LlmTokenBreakdownEstimator.estimateBreakdown(
        messages = koogLlmRequestMessages,
        toolDescriptors = toolDescriptors,
        totalInputTokens = inputTokens,
      )
    } else {
      null
    }

    val usageAndCost = LlmRequestUsageAndCost(
      trailblazeLlmModel = trailblazeLlmModel,
      inputTokens = inputTokens,
      outputTokens = outputTokens,
      promptCost = inputTokens * trailblazeLlmModel.inputCostPerOneMillionTokens / 1_000_000.0,
      completionCost = outputTokens * trailblazeLlmModel.outputCostPerOneMillionTokens / 1_000_000.0,
      inputTokenBreakdown = tokenBreakdown,
    )

    log(
      session,
      TrailblazeLog.TrailblazeLlmRequestLog(
        agentTaskStatus = stepStatus.currentStatus.value,
        viewHierarchy = stepStatus.currentScreenState.viewHierarchyOriginal,
        viewHierarchyFiltered = stepStatus.currentScreenState.viewHierarchy,
        instructions = stepStatus.promptStep.prompt,
        trailblazeLlmModel = trailblazeLlmModel,
        llmMessages = (koogLlmRequestMessages + response).toTrailblazeLlmMessages(),
        screenshotFile = screenshotFilename,
        llmResponse = response,
        llmRequestUsageAndCost = usageAndCost,
        actions = toolMessages.map {
          Action(
            it.tool,
            if (it.content.trim() == "null") {
              JsonObject(emptyMap())
            } else {
              try {
                TrailblazeJsonInstance.decodeFromString(JsonObject.serializer(), it.content)
              } catch (e: Exception) {
                JsonObject(emptyMap())
              }
            },
          )
        },
        timestamp = startTime,
        durationMs = endTime.toEpochMilliseconds() - startTime.toEpochMilliseconds(),
        traceId = traceId,
        deviceWidth = stepStatus.currentScreenState.deviceWidth,
        deviceHeight = stepStatus.currentScreenState.deviceHeight,
        session = session.sessionId,
        toolOptions = toolOptions,
      ),
    )
  }

  /**
   * Helper method to convert Koog messages to Trailblaze format.
   * Handles Tool messages, attachments, and regular content.
   */
  private fun List<Message>.toTrailblazeLlmMessages() = map { messageFromHistory ->
    when (messageFromHistory) {
      is Message.Tool -> {
        TrailblazeLlmMessage(
          role = messageFromHistory.role.name.lowercase(),
          message = buildString {
            appendLine("**${messageFromHistory.tool}**")
            appendLine("")
            appendLine("```json")
            appendLine(messageFromHistory.content)
            appendLine("```")
          },
        )
      }

      else -> {
        TrailblazeLlmMessage(
          role = messageFromHistory.role.name.lowercase(),
          message = buildString {
            appendLine(messageFromHistory.content)
            if (messageFromHistory.hasAttachments()) {
              appendLine("")
              val attachments = messageFromHistory.parts.filterIsInstance<ContentPart.Attachment>()
              if (attachments.isNotEmpty()) {
                appendLine("Attachments:")
                attachments.forEach { attachment ->
                  val attachmentType = when (attachment) {
                    is ContentPart.Audio -> "Audio"
                    is ContentPart.File -> "File"
                    is ContentPart.Image -> "Image"
                    is ContentPart.Video -> "Video"
                  }

                  val attachmentContentKind = when (val attachmentContent = attachment.content) {
                    is AttachmentContent.Binary.Base64 -> "Binary, ${attachmentContent.base64.length} Base64 Encoded Characters"
                    is AttachmentContent.Binary.Bytes -> "Binary, ${attachmentContent.data.size} Bytes"
                    is AttachmentContent.PlainText -> "Plain Text, ${attachmentContent.text}"
                    is AttachmentContent.URL -> "Url, ${attachmentContent.url}"
                  }

                  appendLine("- $attachmentType (${attachment.format}), $attachmentContentKind")
                }
              }
            }
          },
        )
      }
    }
  }

  companion object {
    /**
     * Creates a logger with no-op emitter (for testing).
     *
     * ## Usage
     * ```kotlin
     * val logger = TrailblazeLogger.createNoOp()
     * // Logs will be discarded
     * ```
     */
    fun createNoOp(): TrailblazeLogger {
      return TrailblazeLogger(
        logEmitter = NoOpLogEmitter,
        screenStateLogger = ScreenStateLogger { "" },
      )
    }
  }
}
