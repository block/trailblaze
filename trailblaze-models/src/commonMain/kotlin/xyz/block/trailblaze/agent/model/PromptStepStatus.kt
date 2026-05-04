@file:OptIn(kotlin.time.ExperimentalTime::class)

package xyz.block.trailblaze.agent.model

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.api.AgentMessages.toContentString
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.logs.model.TaskId
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.yaml.PromptStep

data class PromptStepStatus(
  val promptStep: PromptStep,
  val llmStatusChecks: Boolean = true,
  private val koogLlmResponseHistory: MutableList<Message> = mutableListOf(),
  private val screenStateProvider: () -> ScreenState,
  private val maxHistorySize: Int = DEFAULT_MAX_HISTORY_SIZE,
  // Constructor param so data class copy() preserves the count across the session.
  // Defaults to the initial history size; callers should not set this directly.
  private var totalMessageCount: Int = koogLlmResponseHistory.size,
) {
  val taskId = TaskId.generate()

  private var latestObjectiveStatus: String? = null

  init {
    require(maxHistorySize > 0) { "maxHistorySize must be positive, but was $maxHistorySize" }
    // If constructed with a pre-populated history (e.g. recovery path), trim it
    if (koogLlmResponseHistory.size > maxHistorySize) {
      val trimmed = koogLlmResponseHistory.takeLast(maxHistorySize)
      koogLlmResponseHistory.clear()
      koogLlmResponseHistory.addAll(trimmed)
    }
  }

  fun addObjectiveStatusUpdate(status: String) {
    latestObjectiveStatus = status
  }

  fun getLatestObjectiveStatus(): String? = latestObjectiveStatus

  fun getLimitedHistory(): List<Message> {
    val window = koogLlmResponseHistory.takeLast(maxHistorySize)
    // Anthropic rejects requests where a tool_result block appears without the
    // matching tool_use immediately before it ("Each tool_result block must have
    // a corresponding tool_use block in the previous message"). FIFO eviction can
    // drop a Tool.Call while keeping its paired Tool.Result, which the API then
    // refuses. Trim any leading orphaned Tool.Result entries from the window so
    // the conversation it sees always starts on something Anthropic accepts.
    val firstValidIndex = window.indexOfFirst { it !is Message.Tool.Result }
    return when {
      // Window is entirely Tool.Result entries — every one of them is orphaned, so
      // there's nothing safe to send. Return empty rather than the unmodified window
      // (which would still start with a tool_result and trip the same Anthropic error).
      firstValidIndex == -1 -> emptyList()
      firstValidIndex == 0 -> window
      else -> window.drop(firstValidIndex)
    }
  }

  private fun getHistorySize() = totalMessageCount

  var currentStep: Int = 0
    private set
  lateinit var currentScreenState: ScreenState
    private set

  val taskCreatedTimestamp = Clock.System.now()
  val currentStatus = MutableStateFlow<AgentTaskStatus>(
    AgentTaskStatus.InProgress(
      statusData = AgentTaskStatusData(
        prompt = promptStep.prompt,
        callCount = 0,
        taskStartTime = taskCreatedTimestamp,
        totalDurationMs = 0,
        taskId = taskId,
      ),
    ),
  )

  // Function that the runner can call to determine if it should keep processing the task.
  // When the task is finished it means that no more calls to the LLM will be made. The task can
  // either complete successfully or one of several failure modes such as hitting the max call limit
  // without completing the task or via some other error
  fun isFinished(): Boolean = currentStatus.value !is AgentTaskStatus.InProgress

  fun addCompletedToolCallToChatHistory(
    commandResult: TrailblazeToolResult,
    llmResponseContent: String?,
    toolName: String?,
    toolArgs: JsonObject?,
    toolCall: Message.Tool.Call? = null,
  ) {
    if (toolCall != null && toolName != null && toolArgs != null) {
      addStructuredToolTurn(
        toolCall = toolCall,
        contentString = commandResult.toContentString(
          toolName = toolName,
          toolArgs = toolArgs,
        ),
        isError = commandResult.isError(),
      )
      return
    }
    // Fallback path — no original tool_use to anchor a tool_result against. Used by
    // handleEmptyToolCall and any caller that doesn't have the LLM's Message.Tool.Call.
    llmResponseContent?.let { addAssistantMessageToChatHistory(llmContent = it) }
    if (toolName != null && toolArgs != null) {
      addToolExecutionResultUserMessageToChatHistory(
        commandResult = commandResult,
        toolName = toolName,
        toolArgs = toolArgs,
      )
    }
  }

  /**
   * Overload for handling multiple tool names (used for delegating tools that execute multiple actual tools)
   */
  fun addCompletedToolCallToChatHistory(
    commandResult: TrailblazeToolResult,
    llmResponseContent: String?,
    toolNames: List<String>,
    toolArgs: JsonObject?,
    toolCall: Message.Tool.Call? = null,
  ) {
    if (toolCall != null && toolNames.isNotEmpty() && toolArgs != null) {
      addStructuredToolTurn(
        toolCall = toolCall,
        contentString = commandResult.toContentString(
          toolNames = toolNames,
          toolArgs = toolArgs,
        ),
        isError = commandResult.isError(),
      )
      return
    }
    llmResponseContent?.let { addAssistantMessageToChatHistory(llmContent = it) }
    if (toolNames.isNotEmpty() && toolArgs != null) {
      addToolExecutionResultUserMessageToChatHistory(
        commandResult = commandResult,
        toolNames = toolNames,
        toolArgs = toolArgs,
      )
    }
  }

  /**
   * Overload for handling multiple tools with their individual arguments (used for delegating tools)
   */
  fun addCompletedToolCallToChatHistory(
    commandResult: TrailblazeToolResult,
    llmResponseContent: String?,
    toolsWithArgs: Map<String, JsonObject>,
    toolCall: Message.Tool.Call? = null,
  ) {
    if (toolCall != null && toolsWithArgs.isNotEmpty()) {
      addStructuredToolTurn(
        toolCall = toolCall,
        contentString = commandResult.toContentString(
          toolsWithArgs = toolsWithArgs,
        ),
        isError = commandResult.isError(),
      )
      return
    }
    llmResponseContent?.let { addAssistantMessageToChatHistory(llmContent = it) }
    if (toolsWithArgs.isNotEmpty()) {
      addToolExecutionResultUserMessageToChatHistory(
        commandResult = commandResult,
        toolsWithArgs = toolsWithArgs,
      )
    }
  }

  /**
   * Adds the LLM's original [Message.Tool.Call] (assistant turn) followed by a paired
   * [Message.Tool.Result] (user turn) carrying the same `id`. This is the format the
   * koog Anthropic adapter requires to emit `tool_use` / `tool_result` content blocks —
   * without a matching pair, Claude treats the prior tool call as unanswered and re-issues
   * it (the 50× verify-loop we saw on every Claude run in a reproduction).
   *
   * The LLM's pre-tool reasoning text (passed via the older `llmResponseContent` path) is
   * intentionally dropped here: that text is already echoed inside the tool's `reasoning`
   * argument, and adding a separate [Message.Assistant] would land back-to-back with the
   * [Message.Tool.Call] which the Anthropic API rejects as consecutive assistant messages.
   */
  private fun addStructuredToolTurn(
    toolCall: Message.Tool.Call,
    contentString: String,
    isError: Boolean,
  ) {
    addToHistory(toolCall)
    addToHistory(
      Message.Tool.Result(
        id = toolCall.id,
        tool = toolCall.tool,
        content = contentString,
        metaInfo = RequestMetaInfo.create(kotlin.time.Clock.System),
        isError = isError,
      ),
    )
  }

  private fun TrailblazeToolResult.isError(): Boolean = this is TrailblazeToolResult.Error

  private fun addToolExecutionResultUserMessageToChatHistory(
    commandResult: TrailblazeToolResult,
    toolName: String,
    toolArgs: JsonObject,
  ) {
    val contentString = commandResult.toContentString(
      toolName = toolName,
      toolArgs = toolArgs,
    )
    addToHistory(
      Message.User(
        content = contentString,
        metaInfo = RequestMetaInfo.create(kotlin.time.Clock.System),
      ),
    )
  }

  /**
   * Overload for handling multiple tool names (used for delegating tools)
   */
  private fun addToolExecutionResultUserMessageToChatHistory(
    commandResult: TrailblazeToolResult,
    toolNames: List<String>,
    toolArgs: JsonObject,
  ) {
    val contentString = commandResult.toContentString(
      toolNames = toolNames,
      toolArgs = toolArgs,
    )
    addToHistory(
      Message.User(
        content = contentString,
        metaInfo = RequestMetaInfo.create(kotlin.time.Clock.System),
      ),
    )
  }

  /**
   * Overload for handling multiple tools with their individual arguments
   */
  private fun addToolExecutionResultUserMessageToChatHistory(
    commandResult: TrailblazeToolResult,
    toolsWithArgs: Map<String, JsonObject>,
  ) {
    val contentString = commandResult.toContentString(
      toolsWithArgs = toolsWithArgs,
    )
    addToHistory(
      Message.User(
        content = contentString,
        metaInfo = RequestMetaInfo.create(kotlin.time.Clock.System),
      ),
    )
  }

  private fun addAssistantMessageToChatHistory(llmContent: String) {
    addToHistory(
      Message.Assistant(
        content = llmContent,
        metaInfo = ResponseMetaInfo.create(kotlin.time.Clock.System),
      ),
    )
  }

  private fun addToHistory(message: Message) {
    koogLlmResponseHistory.add(message)
    totalMessageCount++
    // Keep only what getLimitedHistory() needs — avoid unbounded memory growth on-device
    while (koogLlmResponseHistory.size > maxHistorySize) {
      koogLlmResponseHistory.removeAt(0)
    }
  }

  companion object {
    /**
     * Default number of recent LLM messages to retain. Only the last N messages are sent
     * to the LLM for context, and older messages are evicted to bound memory usage —
     * critical for on-device Android execution where the ART heap is ~192 MB.
     */
    const val DEFAULT_MAX_HISTORY_SIZE = 5
  }

  fun handleEmptyToolCall(llmResponseContent: String?) {
    addCompletedToolCallToChatHistory(
      llmResponseContent = llmResponseContent,
      commandResult = TrailblazeToolResult.Error.EmptyToolCall,
      toolName = null,
      toolArgs = null,
    )
  }

  fun markAsComplete(llmExplanation: String = "All objectives completed successfully") {
    currentStatus.value = AgentTaskStatus.Success.ObjectiveComplete(
      statusData = AgentTaskStatusData(
        prompt = promptStep.prompt,
        callCount = getHistorySize(),
        taskStartTime = taskCreatedTimestamp,
        totalDurationMs = Clock.System.now().epochSeconds - taskCreatedTimestamp.epochSeconds,
        taskId = taskId,
      ),
      llmExplanation = llmExplanation,
    )
  }

  fun markAsFailed(llmExplanation: String = "The objective failed to complete") {
    currentStatus.value = AgentTaskStatus.Failure.ObjectiveFailed(
      statusData = AgentTaskStatusData(
        prompt = promptStep.prompt,
        callCount = getHistorySize(),
        taskStartTime = taskCreatedTimestamp,
        totalDurationMs = Clock.System.now().toEpochMilliseconds() - taskCreatedTimestamp.toEpochMilliseconds(),
        taskId = taskId,
      ),
      llmExplanation = llmExplanation,
    )
  }

  fun prepareNextStep() {
    currentStep++
    currentScreenState = screenStateProvider()
  }
}
