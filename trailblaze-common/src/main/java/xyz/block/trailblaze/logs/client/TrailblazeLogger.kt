package xyz.block.trailblaze.logs.client

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.message.Attachment
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.Message.WithAttachments
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.agent.model.PromptStepStatus
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.logs.client.TrailblazeLog.TrailblazeLlmRequestLog.Action
import xyz.block.trailblaze.logs.client.TrailblazeLog.TrailblazeLlmRequestLog.ToolOption
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.logs.model.TrailblazeLlmMessage
import xyz.block.trailblaze.yaml.TrailConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Central logging facility for Trailblaze operations.
 * Handles session management, screenshot logging, and LLM request/response tracking.
 */
object TrailblazeLogger {

  private const val MAX_SESSION_ID_LENGTH = 100
  private const val DEFAULT_SESSION_PREFIX = "Trailblaze"

  private var logListener: (TrailblazeLog) -> Unit = { }

  private var logScreenshotListener: (ByteArray) -> String = { "No Logger Set for Screenshots" }

  /**
   * Sets the global log listener for capturing TrailblazeLog events.
   */
  fun setLogListener(logListener: (TrailblazeLog) -> Unit) {
    this.logListener = logListener
  }

  /**
   * Logs a TrailblazeLog event to the registered listener.
   */
  fun log(trailblazeLog: TrailblazeLog) = logListener(trailblazeLog)

  /**
   * Sets the global screenshot logging listener for storing screenshot data.
   */
  fun setLogScreenshotListener(logScreenshotListener: (ByteArray) -> String) {
    this.logScreenshotListener = logScreenshotListener
  }

  /**
   * Logs a screenshot and returns the filename where it was stored.
   */
  fun logScreenshot(screenshotBytes: ByteArray): String = logScreenshotListener(screenshotBytes)

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

    val bytes = stepStatus.currentScreenState.screenshotBytes ?: byteArrayOf()
    val screenshotFilename = logScreenshot(bytes)

    val toolOptions = toolDescriptors.map { ToolOption(it.name) }
    val endTime = Clock.System.now()
    log(
      TrailblazeLog.TrailblazeLlmRequestLog(
        agentTaskStatus = stepStatus.currentStatus.value,
        viewHierarchy = stepStatus.currentScreenState.viewHierarchy,
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
  private val DATE_TIME_FORMAT = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.US)

  private fun generateSessionId(seed: String): String = "${DATE_TIME_FORMAT.format(Date())}_$seed"

  private var sessionId: String = generateSessionId(DEFAULT_SESSION_PREFIX)
  private var sessionStartTime: Instant = Clock.System.now()

  /**
   * Starts a new session with the given name.
   * @param sessionName The name to use for the session
   * @return The generated session ID
   */
  fun startSession(sessionName: String): String = overrideSessionId(
    sessionIdOverride = generateSessionId(sessionName),
  )

  /**
   * Returns the current session ID in a thread-safe manner.
   */
  fun getCurrentSessionId(): String = synchronized(sessionId) {
    return this.sessionId
  }

  /**
   * Truncates and sanitizes session ID to ensure it's filesystem-safe.
   */
  private fun truncateSessionId(sessionId: String): String = sessionId.take(minOf(sessionId.length, MAX_SESSION_ID_LENGTH))
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
    truncateSessionId(sessionIdOverride).also {
      this.sessionId = it
    }
  }

  /**
   * Sends a session start log with test metadata.
   */
  fun sendStartLog(
    trailConfig: TrailConfig?,
    className: String,
    methodName: String,
    trailblazeDeviceInfo: TrailblazeDeviceInfo,
    rawYaml: String? = null,
  ) {
    TrailblazeLogger.log(
      TrailblazeLog.TrailblazeSessionStatusChangeLog(
        sessionStatus = SessionStatus.Started(
          trailConfig = trailConfig,
          testClassName = className,
          testMethodName = methodName,
          trailblazeDeviceInfo = trailblazeDeviceInfo,
          rawYaml = rawYaml,
        ),
        session = TrailblazeLogger.getCurrentSessionId(),
        timestamp = Clock.System.now(),
      ),
    )
  }

  /**
   * Sends a session end log with the provided status.
   */
  fun sendEndLog(endedStatus: SessionStatus.Ended) {
    log(
      TrailblazeLog.TrailblazeSessionStatusChangeLog(
        sessionStatus = endedStatus,
        session = TrailblazeLogger.getCurrentSessionId(),
        timestamp = Clock.System.now(),
      ),
    )
  }

  /**
   * Sends a session end log with success/failure status and optional exception.
   */
  fun sendEndLog(isSuccess: Boolean, exception: Throwable? = null) {
    val durationMs = Clock.System.now().toEpochMilliseconds() - sessionStartTime.toEpochMilliseconds()
    val endedStatus = if (isSuccess) {
      SessionStatus.Ended.Succeeded(
        durationMs = durationMs,
      )
    } else {
      SessionStatus.Ended.Failed(
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
            if (messageFromHistory is WithAttachments) {
              appendLine("")
              if (messageFromHistory.attachments.isNotEmpty()) {
                appendLine("Attachments:")
                messageFromHistory.attachments.forEach { attachment ->
                  val attachmentType = when (attachment) {
                    is Attachment.Audio -> "Audio"
                    is Attachment.File -> "File"
                    is Attachment.Image -> "Image"
                    is Attachment.Video -> "Video"
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
}
