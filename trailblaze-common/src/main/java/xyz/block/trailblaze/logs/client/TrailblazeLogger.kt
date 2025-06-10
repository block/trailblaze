package xyz.block.trailblaze.logs.client

import com.aallam.openai.api.chat.ChatCompletion
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.Content
import com.aallam.openai.api.chat.ImagePart
import com.aallam.openai.api.chat.ListContent
import com.aallam.openai.api.chat.TextContent
import com.aallam.openai.api.chat.TextPart
import com.aallam.openai.api.chat.ToolCall
import com.aallam.openai.api.core.Role
import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.logs.client.TrailblazeLog.TrailblazeLlmRequestLog.Action
import xyz.block.trailblaze.logs.model.LlmMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.io.encoding.ExperimentalEncodingApi

object TrailblazeLogger {

  private var logListener: (TrailblazeLog) -> Unit = {}
  private var logScreenshotListener: (ByteArray) -> String = { "Screenshot_Not_Logged" }

  fun setLogListener(logListener: (TrailblazeLog) -> Unit) {
    this.logListener = logListener
  }

  fun log(trailblazeLog: TrailblazeLog) {
    logListener(trailblazeLog)
  }

  fun setLogScreenshotListener(logScreenshotListener: (ByteArray) -> String) {
    this.logScreenshotListener = logScreenshotListener
  }

  fun logScreenshot(screenshotBytes: ByteArray): String = logScreenshotListener(screenshotBytes)

  /** Transforms an Open AI [Content] object into a [String] representation */
  private fun contentToString(openAiContent: Content?): String? = when (openAiContent) {
    is ListContent -> {
      val result = openAiContent.content.map { content ->
        when (content) {
          is ImagePart -> null
          is TextPart -> content.text
        }
      }
      result.filterNotNull().joinToString(",")
    }

    is TextContent -> openAiContent.content
    null -> null
  }

  fun logLlmRequest(
    llmRequestId: String,
    agentTaskStatus: AgentTaskStatus,
    screenState: ScreenState,
    instructions: String,
    request: ChatCompletionRequest,
    response: ChatCompletion,
    startTime: Long,
  ) {
    val firstOpenAiResponseMessage: ChatMessage = response.choices.first().message
    val actions =
      firstOpenAiResponseMessage.toolCalls?.filterIsInstance<ToolCall.Function>() ?: emptyList()

    @OptIn(ExperimentalEncodingApi::class)
    val bytes = screenState.screenshotBytes ?: byteArrayOf()
    val screenshotFilename = logScreenshot(bytes)

    log(
      TrailblazeLog.TrailblazeLlmRequestLog(
        agentTaskStatus = agentTaskStatus,
        viewHierarchy = screenState.viewHierarchy,
        instructions = instructions,
        llmMessages = request.messages.map {
          LlmMessage(
            role = it.role.role,
            message = contentToString(it.messageContent),
          )
        }.plus(
          LlmMessage(
            role = Role.Assistant.role,
            message = contentToString(response.choices.firstOrNull()?.message?.messageContent),
          ),
        ),
        screenshotFile = screenshotFilename,
        llmResponse = response,
        actions = actions.map { Action(it.function.name, it.function.argumentsAsJson()) },
        timestamp = startTime,
        duration = System.currentTimeMillis() - startTime,
        llmResponseId = llmRequestId,
        deviceWidth = screenState.deviceWidth,
        deviceHeight = screenState.deviceHeight,
        session = getCurrentSessionId(),
      ),
    )
  }

  @Suppress("SimpleDateFormat")
  private val DATE_TIME_FORMAT = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US)

  private fun generateSessionId(seed: String): String = "${DATE_TIME_FORMAT.format(Date())}_$seed"

  private var sessionId: String = generateSessionId("Trailblaze")

  fun startSession(sessionName: String): String = synchronized(sessionId) {
    val newSessionId = generateSessionId(sessionName)
    return newSessionId.also { this.sessionId = newSessionId }
  }

  fun getCurrentSessionId(): String = synchronized(sessionId) {
    return this.sessionId
  }

  /**
   * Prefer startSession() unless you need to explicitly override it
   */
  fun overrideSessionId(sessionId: String) = synchronized(sessionId) {
    this.sessionId = sessionId
  }
}
