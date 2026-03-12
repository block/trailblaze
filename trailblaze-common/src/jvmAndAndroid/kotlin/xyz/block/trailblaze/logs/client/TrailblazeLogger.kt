package xyz.block.trailblaze.logs.client

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.ContentPart
import ai.koog.prompt.message.Message
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.block.trailblaze.agent.ProgressEventListener
import xyz.block.trailblaze.agent.TokenUsage
import xyz.block.trailblaze.agent.TrailblazeProgressEvent
import xyz.block.trailblaze.agent.model.PromptRecordingResult
import xyz.block.trailblaze.agent.model.PromptStepStatus
import xyz.block.trailblaze.api.ImageFormatDetector
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.http.CachedTokenCaptureInterceptor
import xyz.block.trailblaze.llm.CachedTokenExtractor
import xyz.block.trailblaze.llm.LlmRequestUsageAndCost
import xyz.block.trailblaze.llm.LlmTokenBreakdownEstimator
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.logs.client.TrailblazeLog.TrailblazeLlmRequestLog.Action
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.logs.model.TrailblazeLlmMessage
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool.Companion.toTrailblazeToolDescriptor
import xyz.block.trailblaze.util.Console
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
   * Saves a screenshot file and logs the screen state.
   *
   * @param session The session this screenshot belongs to
   * @param screenState The screen state containing screenshot bytes
   * @return The filename where the screenshot was stored, or null if no screenshot
   */
  fun logScreenState(session: TrailblazeSession, screenState: ScreenState): String? {
    val screenshotBytes = screenState.screenshotBytes ?: return null

    val imageFormat = ImageFormatDetector.detectFormat(screenshotBytes)
    val timestamp = Clock.System.now()
    val screenshotFileName = "${session.sessionId.value}_${timestamp.toEpochMilliseconds()}.${imageFormat.fileExtension}"

    val screenStateLog = TrailblazeScreenStateLog(
      fileName = screenshotFileName,
      sessionId = session.sessionId,
      screenState = screenState,
    )
    screenStateLogger.logScreenState(screenStateLog)

    return screenshotFileName
  }

  /**
   * Logs the annotated screenshot from a screen state.
   * This saves the screenshot with set-of-mark annotations applied.
   *
   * @param session The session this screenshot belongs to
   * @param screenState The screen state containing annotated screenshot
   * @return The filename where the screenshot was stored, or null if no screenshot available
   */
  fun logScreenStateAnnotated(session: TrailblazeSession, screenState: ScreenState): String? {
    val screenshotBytes = screenState.annotatedScreenshotBytes ?: return null

    val imageFormat = ImageFormatDetector.detectFormat(screenshotBytes)
    val timestamp = Clock.System.now()
    val screenshotFileName = "${session.sessionId.value}_${timestamp.toEpochMilliseconds()}.${imageFormat.fileExtension}"

    // Create a wrapper that exposes the annotated screenshot as the primary screenshot
    val annotatedScreenStateWrapper = object : ScreenState by screenState {
      override val screenshotBytes: ByteArray?
        get() = screenState.annotatedScreenshotBytes
      override val annotatedScreenshotBytes: ByteArray?
        get() = screenState.annotatedScreenshotBytes
    }

    val screenStateLog = TrailblazeScreenStateLog(
      fileName = screenshotFileName,
      sessionId = session.sessionId,
      screenState = annotatedScreenStateWrapper,
    )
    screenStateLogger.logScreenState(screenStateLog)

    return screenshotFileName
  }

  /**
   * Logs a snapshot with clean screenshot and view hierarchy.
   *
   * @param session The session this snapshot belongs to
   * @param screenState The screen state containing clean screenshot and view hierarchy
   * @param displayName Optional human-readable name for this snapshot (e.g., "login_screen").
   *                    Shown in the snapshot viewer UI. Does not affect the actual filename.
   * @return The filename where the snapshot was stored, or null if no screenshot available
   */
  fun logSnapshot(
    session: TrailblazeSession,
    screenState: ScreenState,
    displayName: String? = null,
    traceId: TraceId? = null,
  ): String? {
    val screenshotFileName = logScreenState(session, screenState) ?: return null

    log(
      session,
      TrailblazeLog.TrailblazeSnapshotLog(
        displayName = displayName,
        screenshotFile = screenshotFileName,
        viewHierarchy = screenState.viewHierarchy,
        trailblazeNodeTree = screenState.trailblazeNodeTree,
        deviceWidth = screenState.deviceWidth,
        deviceHeight = screenState.deviceHeight,
        session = session.sessionId,
        timestamp = Clock.System.now(),
        traceId = traceId,
      )
    )

    return screenshotFileName
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
    requestContext: TrailblazeLog.LlmRequestContext,
    tokenUsage: TokenUsage? = null, // Optional: pass directly from SamplingResult
    llmRequestLabel: String? = null,
  ) {
    val toolMessages = response.filterIsInstance<Message.Tool>()
    val screenshotFilename = logScreenStateAnnotated(session, stepStatus.currentScreenState)

    val toolOptions = toolDescriptors
      .map { it.toTrailblazeToolDescriptor() }
      .sortedBy { it.name }
    val endTime = Clock.System.now()

    // Use provided token usage, or extract from response metaInfo
    val usage = response.lastOrNull()?.metaInfo
    val inputTokens = tokenUsage?.inputTokens ?: usage?.inputTokensCount?.toLong() ?: 0L
    val outputTokens = tokenUsage?.outputTokens ?: usage?.outputTokensCount?.toLong() ?: 0L
    // Try Koog's native metadata first (future KG-656 support), then fall back to
    // our OkHttp interceptor which captures cached token data from raw API responses.
    // The interceptor correlates by (inputTokens, outputTokens) so concurrent sessions
    // don't mix up each other's data.
    val cachedTokenMetadata = usage?.metadata
      ?: CachedTokenCaptureInterceptor.getByTokenCounts(inputTokens, outputTokens)
    val cacheReadTokens = CachedTokenExtractor.extractCacheReadTokens(cachedTokenMetadata)
    val cacheCreationTokens = CachedTokenExtractor.extractCacheCreationTokens(cachedTokenMetadata)

    val promptCost = LlmRequestUsageAndCost.calculatePromptCost(
      inputTokens = inputTokens,
      cacheReadInputTokens = cacheReadTokens,
      model = trailblazeLlmModel,
    )
    val completionCost = outputTokens * trailblazeLlmModel.outputCostPerOneMillionTokens / 1_000_000.0
    Console.log(
      "[LLM Cost] input=$inputTokens cached=$cacheReadTokens " +
        "model=${trailblazeLlmModel.modelId} " +
        "inputRate=${trailblazeLlmModel.inputCostPerOneMillionTokens} " +
        "cachedRate=${trailblazeLlmModel.cachedInputCostPerOneMillionTokens} " +
        "promptCost=$promptCost completionCost=$completionCost " +
        "totalCost=${promptCost + completionCost}"
    )

    val tokenBreakdown = if (inputTokens > 0) {
      LlmTokenBreakdownEstimator.estimateBreakdown(
        messages = koogLlmRequestMessages,
        toolDescriptors = toolDescriptors,
        totalInputTokens = inputTokens,
      )
    } else {
      null
    }

    // Always create usage and cost - even with 0 tokens (e.g., MCP sampling with unknown usage).
    // MCP_SAMPLING provider has 0 cost, so this correctly shows $0.00 for those requests.
    val usageAndCost = LlmRequestUsageAndCost(
      trailblazeLlmModel = trailblazeLlmModel,
      inputTokens = inputTokens,
      outputTokens = outputTokens,
      cacheReadInputTokens = cacheReadTokens,
      cacheCreationInputTokens = cacheCreationTokens,
      promptCost = promptCost,
      completionCost = completionCost,
      inputTokenBreakdown = tokenBreakdown,
    )

    log(
      session,
      TrailblazeLog.TrailblazeLlmRequestLog(
        agentTaskStatus = stepStatus.currentStatus.value,
        viewHierarchy = stepStatus.currentScreenState.viewHierarchyOriginal,
        viewHierarchyFiltered = stepStatus.currentScreenState.viewHierarchy,
        trailblazeNodeTree = stepStatus.currentScreenState.trailblazeNodeTree,
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
        requestContext = requestContext,
        llmRequestLabel = llmRequestLabel,
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

  // region Progress Event Logging (Phase 6)

  /**
   * Logs a progress event for the given session.
   *
   * This converts [TrailblazeProgressEvent] to [TrailblazeLog.TrailblazeProgressLog]
   * and emits it through the log pipeline.
   *
   * ## Usage
   *
   * ```kotlin
   * val event = TrailblazeProgressEvent.StepStarted(
   *   timestamp = System.currentTimeMillis(),
   *   sessionId = session.sessionId.value,
   *   stepIndex = 0,
   *   stepPrompt = "Open the Settings app",
   *   totalSteps = 5,
   * )
   * logger.logProgressEvent(session, event)
   * ```
   *
   * @param session The session this event belongs to
   * @param event The progress event to log
   */
  fun logProgressEvent(session: TrailblazeSession, event: TrailblazeProgressEvent) {
    val (eventType, description, success, stepIndex, totalSteps, progressPercent, durationMs, eventData) =
      extractProgressEventData(event)

    log(
      session,
      TrailblazeLog.TrailblazeProgressLog(
        eventType = eventType,
        description = description,
        success = success,
        stepIndex = stepIndex,
        totalSteps = totalSteps,
        progressPercent = progressPercent,
        durationMs = durationMs,
        eventData = eventData,
        session = session.sessionId,
        timestamp = Instant.fromEpochMilliseconds(event.timestamp),
      ),
    )
  }

  /**
   * Creates a [ProgressEventListener] that logs events for a specific session.
   *
   * This is useful for integrating with [xyz.block.trailblaze.agent.DefaultProgressReporter]
   * or any other component that emits progress events.
   *
   * ## Usage
   *
   * ```kotlin
   * val listener = logger.createProgressListener(session)
   * val reporter = DefaultProgressReporter(listener)
   * // reporter will now log all events to the session
   * ```
   *
   * @param session The session to log events for
   * @return A listener that logs events to this logger
   */
  fun createProgressListener(session: TrailblazeSession): ProgressEventListener {
    return object : ProgressEventListener {
      override fun onProgressEvent(event: TrailblazeProgressEvent) {
        logProgressEvent(session, event)
      }
    }
  }

  /**
   * Extracts structured data from a progress event for logging.
   *
   * @return A tuple of (eventType, description, success, stepIndex, totalSteps, progressPercent, durationMs, eventData)
   */
  private fun extractProgressEventData(
    event: TrailblazeProgressEvent
  ): ProgressEventLogData = when (event) {
    is TrailblazeProgressEvent.ExecutionStarted -> ProgressEventLogData(
      eventType = "ExecutionStarted",
      description = "Started execution: ${event.objective}",
      success = null,
      stepIndex = null,
      totalSteps = null,
      progressPercent = 0,
      durationMs = 0L,
      eventData = buildJsonObject {
        put("objective", event.objective)
        put("agentImplementation", event.agentImplementation.name)
        put("hasTaskPlan", event.hasTaskPlan)
      },
    )

    is TrailblazeProgressEvent.ExecutionCompleted -> ProgressEventLogData(
      eventType = "ExecutionCompleted",
      description = if (event.success) "Execution completed successfully" else "Execution failed: ${event.errorMessage}",
      success = event.success,
      stepIndex = null,
      totalSteps = null,
      progressPercent = if (event.success) 100 else null,
      durationMs = event.totalDurationMs,
      eventData = buildJsonObject {
        put("totalActions", event.totalActions)
        event.errorMessage?.let { put("errorMessage", it) }
      },
    )

    is TrailblazeProgressEvent.StepStarted -> ProgressEventLogData(
      eventType = "StepStarted",
      description = "Step ${event.stepIndex + 1}/${event.totalSteps}: ${event.stepPrompt}",
      success = null,
      stepIndex = event.stepIndex,
      totalSteps = event.totalSteps,
      progressPercent = ((event.stepIndex.toFloat() / event.totalSteps) * 100).toInt(),
      durationMs = 0L,
      eventData = buildJsonObject {
        put("stepPrompt", event.stepPrompt)
        put("estimatedDurationMs", event.estimatedDurationMs)
      },
    )

    is TrailblazeProgressEvent.StepCompleted -> ProgressEventLogData(
      eventType = "StepCompleted",
      description = "Completed step ${event.stepIndex + 1}${if (event.usedRecording) " (recording)" else " (AI)"}",
      success = event.success,
      stepIndex = event.stepIndex,
      totalSteps = null,
      progressPercent = null,
      durationMs = event.durationMs,
      eventData = buildJsonObject {
        put("usedRecording", event.usedRecording)
        event.errorMessage?.let { put("errorMessage", it) }
      },
    )

    is TrailblazeProgressEvent.SubtaskProgress -> ProgressEventLogData(
      eventType = "SubtaskProgress",
      description = "Subtask ${event.subtaskIndex + 1}/${event.totalSubtasks}: ${event.subtaskName} (${event.percentComplete}%)",
      success = null,
      stepIndex = event.subtaskIndex,
      totalSteps = event.totalSubtasks,
      progressPercent = event.percentComplete,
      durationMs = 0L,
      eventData = buildJsonObject {
        put("subtaskName", event.subtaskName)
        put("actionsInSubtask", event.actionsInSubtask)
      },
    )

    is TrailblazeProgressEvent.SubtaskCompleted -> ProgressEventLogData(
      eventType = "SubtaskCompleted",
      description = "Completed subtask: ${event.subtaskName}",
      success = true,
      stepIndex = event.subtaskIndex,
      totalSteps = null,
      progressPercent = null,
      durationMs = event.durationMs,
      eventData = buildJsonObject {
        put("subtaskName", event.subtaskName)
        put("actionsTaken", event.actionsTaken)
      },
    )

    is TrailblazeProgressEvent.TaskReplanned -> ProgressEventLogData(
      eventType = "TaskReplanned",
      description = "Replanned due to: ${event.blockReason}",
      success = null,
      stepIndex = null,
      totalSteps = event.newSubtasksCount,
      progressPercent = null,
      durationMs = 0L,
      eventData = buildJsonObject {
        put("originalSubtask", event.originalSubtask)
        put("blockReason", event.blockReason)
        put("replanNumber", event.replanNumber)
      },
    )

    is TrailblazeProgressEvent.ReflectionTriggered -> ProgressEventLogData(
      eventType = "ReflectionTriggered",
      description = "Reflection: ${event.reason}",
      success = event.isOnTrack,
      stepIndex = null,
      totalSteps = null,
      progressPercent = null,
      durationMs = 0L,
      eventData = buildJsonObject {
        put("reason", event.reason)
        put("assessment", event.assessment)
        event.suggestedAction?.let { put("suggestedAction", it) }
        put("isOnTrack", event.isOnTrack)
      },
    )

    is TrailblazeProgressEvent.BacktrackPerformed -> ProgressEventLogData(
      eventType = "BacktrackPerformed",
      description = "Backtracked ${event.stepsBacktracked} steps: ${event.reason}",
      success = null,
      stepIndex = null,
      totalSteps = null,
      progressPercent = null,
      durationMs = 0L,
      eventData = buildJsonObject {
        put("stepsBacktracked", event.stepsBacktracked)
        put("reason", event.reason)
      },
    )

    is TrailblazeProgressEvent.ExceptionHandled -> ProgressEventLogData(
      eventType = "ExceptionHandled",
      description = "Handled ${event.exceptionType}: ${event.recoveryAction}",
      success = event.success,
      stepIndex = null,
      totalSteps = null,
      progressPercent = null,
      durationMs = 0L,
      eventData = buildJsonObject {
        put("exceptionType", event.exceptionType)
        put("recoveryAction", event.recoveryAction)
      },
    )

    is TrailblazeProgressEvent.FactStored -> ProgressEventLogData(
      eventType = "FactStored",
      description = "Stored fact: ${event.key}",
      success = true,
      stepIndex = null,
      totalSteps = null,
      progressPercent = null,
      durationMs = 0L,
      eventData = buildJsonObject {
        put("key", event.key)
        put("valuePreview", event.valuePreview)
      },
    )

    is TrailblazeProgressEvent.FactRecalled -> ProgressEventLogData(
      eventType = "FactRecalled",
      description = "Recalled fact: ${event.key}${if (event.found) "" else " (not found)"}",
      success = event.found,
      stepIndex = null,
      totalSteps = null,
      progressPercent = null,
      durationMs = 0L,
      eventData = buildJsonObject {
        put("key", event.key)
        put("found", event.found)
      },
    )
  }

  /**
   * Data class for extracted progress event log data.
   */
  private data class ProgressEventLogData(
    val eventType: String,
    val description: String,
    val success: Boolean?,
    val stepIndex: Int?,
    val totalSteps: Int?,
    val progressPercent: Int?,
    val durationMs: Long,
    val eventData: JsonObject?,
  )

  // endregion

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
