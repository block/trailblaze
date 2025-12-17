package xyz.block.trailblaze.logs.client

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.ContentPart
import ai.koog.prompt.message.Message
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.agent.model.PromptStepStatus
import xyz.block.trailblaze.api.ImageFormatDetector
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.logs.client.TrailblazeLog.TrailblazeLlmRequestLog.Action
import xyz.block.trailblaze.logs.client.TrailblazeLog.TrailblazeLlmRequestLog.ToolOption
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.logs.model.TrailblazeLlmMessage
import xyz.block.trailblaze.session.TrailblazeSessionManager
import xyz.block.trailblaze.yaml.TrailConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Central logging facility for Trailblaze operations.
 * Handles session management, screenshot logging, and LLM request/response tracking.
 * Each instance is scoped to a single test run/session.
 */
abstract class TrailblazeLogger {

  private val maxSessionIdLength = 100
  private val defaultSessionPrefix = "Trailblaze"

  private var logListener: (TrailblazeLog) -> Unit = { }

  private var screenStateLogger: ScreenStateLogger = DebugScreenStateLogger

  // Track fallback usage for the current session
  private var sessionFallbackUsed = false

  /**
   * Flag to track if an end log has already been sent for the current session.
   * Prevents duplicate session end logs from being sent.
   */
  @Volatile
  private var sessionEndLogSent: Boolean = false

  /**
   * Sets the global log listener for capturing TrailblazeLog events.
   */
  fun setLogListener(logListener: (TrailblazeLog) -> Unit) {
    this.logListener = logListener
  }

  /**
   * Gets the current log listener, allowing composition of multiple listeners.
   */
  fun getLogListener(): (TrailblazeLog) -> Unit = logListener

  /**
   * Logs a TrailblazeLog event to the registered listener.
   */
  fun log(trailblazeLog: TrailblazeLog) = logListener(trailblazeLog)

  /**
   * Sets the global screenshot logging listener for storing screenshot data.
   */
  fun setScreenStateLogger(screenStateLogger: ScreenStateLogger) {
    this.screenStateLogger = screenStateLogger
  }

  /**
   * Logs a screenshot and returns the filename where it was stored.
   */
  fun logScreenState(screenState: ScreenState): String {
    val screenshotBytes = screenState.screenshotBytes ?: return ""
    val sessionId = getCurrentSessionId()
    val imageFormat = ImageFormatDetector.detectFormat(screenshotBytes)
    val screenshotFileName = "${sessionId}_${Clock.System.now().toEpochMilliseconds()}.${imageFormat.fileExtension}"
    val screenState = TrailblazeScreenStateLog(
      fileName = screenshotFileName,
      sessionId = sessionId,
      screenState = screenState,
    )
    return screenStateLogger.logScreenState(screenState)
  }

  /**
   * Logs an attempt to use AI fallback and marks that fallback was used for the current session.
   * This method encapsulates both logging and tracking fallback usage.
   */
  fun logAttemptAiFallback(
    promptStep: xyz.block.trailblaze.yaml.PromptStep,
    recordingResult: xyz.block.trailblaze.agent.model.PromptRecordingResult.Failure,
  ) {
    log(
      TrailblazeLog.AttemptAiFallbackLog(
        promptStep = promptStep,
        session = getCurrentSessionId(),
        timestamp = Clock.System.now(),
        recordingResult = recordingResult,
      ),
    )
    markFallbackUsed()
  }

  /**
   * Marks that AI fallback was used for the current session.
   * This is called internally when fallback is used.
   */
  private fun markFallbackUsed() {
    sessionFallbackUsed = true
  }

  /**
   * Gets whether fallback was used in the current session.
   */
  fun wasFallbackUsed(): Boolean = sessionFallbackUsed

  /**
   * Resets the fallback flag. Called when starting a new session.
   */
  private fun resetFallbackFlag() {
    sessionFallbackUsed = false
  }

  /**
   * Logs an LLM request with associated metadata including screenshots, tool options, and timing.
   */
  fun logLlmRequest(
    koogLlmRequestMessages: List<Message>,
    stepStatus: PromptStepStatus,
    trailblazeLlmModel: TrailblazeLlmModel,
    response: List<Message.Response>,
    startTime: Instant,
    traceId: TraceId,
    toolDescriptors: List<ToolDescriptor>,
  ) {
    val toolMessages = response.filterIsInstance<Message.Tool>()

    val bytes = stepStatus.currentScreenState
    val screenshotFilename = logScreenState(stepStatus.currentScreenState)

    val toolOptions = toolDescriptors.map { ToolOption(it.name) }
    val endTime = Clock.System.now()
    log(
      TrailblazeLog.TrailblazeLlmRequestLog(
        agentTaskStatus = stepStatus.currentStatus.value,
        viewHierarchy = stepStatus.currentScreenState.viewHierarchyOriginal,
        viewHierarchyFiltered = stepStatus.currentScreenState.viewHierarchy,
        instructions = stepStatus.promptStep.prompt,
        trailblazeLlmModel = trailblazeLlmModel,
        llmMessages = (koogLlmRequestMessages + response).toTrailblazeLlmMessages(),
        screenshotFile = screenshotFilename,
        llmResponse = response,
        actions = toolMessages.map {
          Action(
            it.tool,
            TrailblazeJsonInstance.decodeFromString(JsonObject.serializer(), it.content),
          )
        },
        timestamp = startTime,
        durationMs = endTime.toEpochMilliseconds() - startTime.toEpochMilliseconds(),
        traceId = traceId,
        deviceWidth = stepStatus.currentScreenState.deviceWidth,
        deviceHeight = stepStatus.currentScreenState.deviceHeight,
        session = getCurrentSessionId(),
        toolOptions = toolOptions,
      ),
    )
  }

  @Suppress("SimpleDateFormat")
  private val dateTimeFormat = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.US)

  private fun generateSessionId(seed: String): String = "${dateTimeFormat.format(Date())}_$seed"

  private var sessionId: String = generateSessionId(defaultSessionPrefix)
  private var sessionStartTime: Instant = Clock.System.now()

  /**
   * Starts a new session with the given name.
   * @param sessionName The name to use for the session
   * @return The generated session ID
   */
  fun startSession(sessionName: String): String {
    resetFallbackFlag()
    return overrideSessionId(
      sessionIdOverride = generateSessionId(sessionName),
    )
  }

  /**
   * Returns the current session ID in a thread-safe manner.
   */
  fun getCurrentSessionId(): String = synchronized(sessionId) {
    return this.sessionId
  }

  /**
   * Truncates and sanitizes session ID to ensure it's filesystem-safe.
   */
  private fun truncateSessionId(sessionId: String): String = sessionId.take(minOf(sessionId.length, maxSessionIdLength))
    .replace(Regex("[^a-zA-Z0-9]"), "_")
    .lowercase()

  /**
   * Overrides the current session ID with a custom value.
   * Note: This will truncate the session ID to 100 characters and replace any non-alphanumeric characters with underscores.
   * @deprecated Prefer startSession() unless you need to explicitly override the session id
   */
  @Deprecated("Prefer startSession() unless you need to explicitly override the session id")
  fun overrideSessionId(sessionIdOverride: String): String = synchronized(this.sessionId) {
    sessionStartTime = Clock.System.now()
    clearEndLogSentFlag()
    truncateSessionId(sessionIdOverride).also {
      this.sessionId = it
    }
  }

  /**
   * Sends a session start log with test metadata.
   */
  fun sendStartLog(
    trailConfig: TrailConfig?,
    trailFilePath: String?,
    className: String,
    methodName: String,
    hasRecordedSteps: Boolean,
    trailblazeDeviceInfo: TrailblazeDeviceInfo,
    trailblazeDeviceId : TrailblazeDeviceId?,
    rawYaml: String,
  ) {
    log(
      TrailblazeLog.TrailblazeSessionStatusChangeLog(
        sessionStatus = SessionStatus.Started(
          trailConfig = trailConfig,
          trailFilePath = trailFilePath,
          testClassName = className,
          testMethodName = methodName,
          trailblazeDeviceInfo = trailblazeDeviceInfo,
          rawYaml = rawYaml,
          hasRecordedSteps = hasRecordedSteps,
          trailblazeDeviceId = trailblazeDeviceId,
        ),
        session = getCurrentSessionId(),
        timestamp = Clock.System.now(),
      ),
    )
  }

  /**
   * Sends a session end log with the provided status.
   */
  fun sendEndLog(endedStatus: SessionStatus.Ended) {
    // Check if end log already sent for this session
    if (sessionEndLogSent) {
      return
    }

    // Mark as sent before logging to prevent race conditions
    sessionEndLogSent = true

    log(
      TrailblazeLog.TrailblazeSessionStatusChangeLog(
        sessionStatus = endedStatus,
        session = getCurrentSessionId(),
        timestamp = Clock.System.now(),
      ),
    )
  }

  /**
   * Sends a session end log with success/failure status and optional exception.
   * This method automatically determines whether to use fallback-aware statuses
   * based on the current fallback usage.
   */
  fun sendEndLog(
    isSuccess: Boolean,
    exception: Throwable? = null,
  ) {
    val durationMs =
      Clock.System.now().toEpochMilliseconds() - sessionStartTime.toEpochMilliseconds()

    val endedStatus = when {
      isSuccess && sessionFallbackUsed -> SessionStatus.Ended.SucceededWithFallback(
        durationMs = durationMs,
      )

      !isSuccess && sessionFallbackUsed -> SessionStatus.Ended.FailedWithFallback(
        durationMs = durationMs,
        exceptionMessage = buildString {
          appendLine(exception?.message)
          appendLine(exception?.stackTraceToString())
        },
      )

      isSuccess -> SessionStatus.Ended.Succeeded(
        durationMs = durationMs,
      )

      else -> SessionStatus.Ended.Failed(
        durationMs = durationMs,
        exceptionMessage = buildString {
          appendLine(exception?.message)
          appendLine(exception?.stackTraceToString())
        },
      )
    }
    sendEndLog(endedStatus)
  }

  /**
   * Sends the appropriate session end log based on the session manager state.
   * This is the single consolidated method that checks if max calls limit was reached
   * and sends the appropriate log. Use this method to avoid duplicating if-else logic.
   *
   * @param sessionManager The session manager to check for max calls limit status
   * @param isSuccess Whether the session completed successfully
   * @param exception Optional exception if the session failed
   */
  fun sendSessionEndLog(
    sessionManager: TrailblazeSessionManager,
    isSuccess: Boolean,
    exception: Throwable? = null,
  ) {
    val maxCallsLimitInfo = sessionManager.getMaxCallsLimitInfo()
    if (maxCallsLimitInfo != null) {
      // Max calls limit was reached - send specific log
      val durationMs =
        Clock.System.now().toEpochMilliseconds() - sessionStartTime.toEpochMilliseconds()
      sendEndLog(
        SessionStatus.Ended.MaxCallsLimitReached(
          durationMs = durationMs,
          maxCalls = maxCallsLimitInfo.maxCalls,
          objectivePrompt = maxCallsLimitInfo.objectivePrompt,
        ),
      )
    } else {
      // Normal success/failure - use standard end log
      sendEndLog(isSuccess, exception)
    }
  }

  /**
   * Converts a list of Messages to TrailblazeLlmMessages for logging.
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

  /**
   * Clear the end log sent flag (called when starting a new session).
   */
  private fun clearEndLogSentFlag() {
    sessionEndLogSent = false
  }
}

/**
 * Central location for logging system.
 * using this instance will only allow one run simultaneously
 */
data object TrailblazeLoggerInstance : TrailblazeLogger()

/**
 * Implementation of the TrailblazeLogger class.
 * Using this class since it won't have knowledge of other runs will allow for running simultaneous tests.
 */
class TrailblazeLoggerImpl : TrailblazeLogger()
