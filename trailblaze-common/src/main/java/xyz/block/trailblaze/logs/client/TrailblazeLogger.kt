package xyz.block.trailblaze.logs.client

import ai.koog.prompt.message.Message
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.logs.client.TrailblazeLog.TrailblazeLlmRequestLog.Action
import xyz.block.trailblaze.logs.model.LlmMessage
import xyz.block.trailblaze.logs.model.SessionStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TrailblazeLogger {

  private var logListener: (TrailblazeLog) -> Unit = { }
  private var logScreenshotListener: (ByteArray) -> String = { "No Logger Set for Screenshots" }

  fun setLogListener(logListener: (TrailblazeLog) -> Unit) {
    this.logListener = logListener
  }

  fun log(trailblazeLog: TrailblazeLog) = logListener(trailblazeLog)

  fun setLogScreenshotListener(logScreenshotListener: (ByteArray) -> String) {
    this.logScreenshotListener = logScreenshotListener
  }

  fun logScreenshot(screenshotBytes: ByteArray): String = logScreenshotListener(screenshotBytes)

  fun logLlmRequest(
    llmModelId: String,
    llmRequestId: String,
    agentTaskStatus: AgentTaskStatus,
    screenState: ScreenState,
    instructions: String,
    llmMessages: List<LlmMessage>,
    response: List<Message.Response>,
    startTime: Instant,
  ) {
    val toolMessages = response.filterIsInstance<Message.Tool>()

    val bytes = screenState.screenshotBytes ?: byteArrayOf()
    val screenshotFilename = logScreenshot(bytes)

    val endTime = Clock.System.now()
    log(
      TrailblazeLog.TrailblazeLlmRequestLog(
        agentTaskStatus = agentTaskStatus,
        viewHierarchy = screenState.viewHierarchy,
        instructions = instructions,
        llmModelId = llmModelId,
        llmMessages = llmMessages,
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
        llmResponseId = llmRequestId,
        deviceWidth = screenState.deviceWidth,
        deviceHeight = screenState.deviceHeight,
        session = getCurrentSessionId(),
      ),
    )
  }

  @Suppress("SimpleDateFormat")
  private val DATE_TIME_FORMAT = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.US)

  private fun generateSessionId(seed: String): String = "${DATE_TIME_FORMAT.format(Date())}_$seed"

  private var sessionId: String = generateSessionId("Trailblaze")

  private var sessionStartTime: Instant = Clock.System.now()

  fun startSession(sessionName: String): String = overrideSessionId(
    sessionIdOverride = generateSessionId(sessionName),
  )

  fun getCurrentSessionId(): String = synchronized(sessionId) {
    return this.sessionId
  }

  private fun truncateSessionId(sessionId: String): String = sessionId.substring(0, minOf(sessionId.length, 100))
    .replace(Regex("[^a-zA-Z0-9]"), "_")
    .lowercase()

  /**
   * Note: This will truncate the session ID to 100 characters and replace any non-alphanumeric characters with underscores.
   */
  @Deprecated("Prefer startSession() unless you need to explicitly override the session id")
  fun overrideSessionId(sessionIdOverride: String): String = synchronized(this.sessionId) {
    sessionStartTime = Clock.System.now()
    truncateSessionId(sessionIdOverride).also {
      this.sessionId = it
    }
  }

  fun sendStartLog(className: String, methodName: String) {
    TrailblazeLogger.log(
      TrailblazeLog.TrailblazeSessionStatusChangeLog(
        sessionStatus = SessionStatus.Started(
          testClassName = className,
          testMethodName = methodName,
        ),
        session = TrailblazeLogger.getCurrentSessionId(),
        timestamp = Clock.System.now(),
      ),
    )
  }

  fun sendEndLog(isSuccess: Boolean, exception: Throwable? = null) {
    val durationMs = Clock.System.now().toEpochMilliseconds() - sessionStartTime.toEpochMilliseconds()
    val testEndedLog = if (isSuccess) {
      TrailblazeLog.TrailblazeSessionStatusChangeLog(
        sessionStatus = SessionStatus.Ended.Succeeded(
          durationMs = durationMs,
        ),
        session = TrailblazeLogger.getCurrentSessionId(),
        timestamp = Clock.System.now(),
      )
    } else {
      TrailblazeLog.TrailblazeSessionStatusChangeLog(
        sessionStatus = SessionStatus.Ended.Failed(
          durationMs = durationMs,
          exceptionMessage = buildString {
            appendLine(exception?.message)
            appendLine(exception?.stackTraceToString())
          },
        ),
        session = TrailblazeLogger.getCurrentSessionId(),
        timestamp = Clock.System.now(),
      )
    }
    log(testEndedLog)
  }
}
