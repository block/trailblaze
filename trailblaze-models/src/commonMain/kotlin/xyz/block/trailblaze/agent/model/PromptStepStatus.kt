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
) {
  val taskId = TaskId.generate()

  fun getLimitedHistory(): List<Message> = koogLlmResponseHistory.takeLast(5)

  private fun getHistorySize() = koogLlmResponseHistory.size

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
  ) {
    llmResponseContent?.let { llmContent ->
      addAssistantMessageToChatHistory(
        llmContent = llmContent,
      )
    }
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
  ) {
    llmResponseContent?.let { llmContent ->
      addAssistantMessageToChatHistory(
        llmContent = llmContent,
      )
    }
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
    toolsWithArgs: List<Pair<String, JsonObject>>,
  ) {
    llmResponseContent?.let { llmContent ->
      addAssistantMessageToChatHistory(
        llmContent = llmContent,
      )
    }
    if (toolsWithArgs.isNotEmpty()) {
      addToolExecutionResultUserMessageToChatHistory(
        commandResult = commandResult,
        toolsWithArgs = toolsWithArgs,
      )
    }
  }

  private fun addToolExecutionResultUserMessageToChatHistory(
    commandResult: TrailblazeToolResult,
    toolName: String,
    toolArgs: JsonObject,
  ) {
    val contentString = commandResult.toContentString(
      toolName = toolName,
      toolArgs = toolArgs,
    )
    koogLlmResponseHistory.add(
      Message.User(
        content = contentString,
        metaInfo = RequestMetaInfo.create(Clock.System),
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
    koogLlmResponseHistory.add(
      Message.User(
        content = contentString,
        metaInfo = RequestMetaInfo.create(Clock.System),
      ),
    )
  }

  /**
   * Overload for handling multiple tools with their individual arguments
   */
  private fun addToolExecutionResultUserMessageToChatHistory(
    commandResult: TrailblazeToolResult,
    toolsWithArgs: List<Pair<String, JsonObject>>,
  ) {
    val contentString = commandResult.toContentString(
      toolsWithArgs = toolsWithArgs,
    )
    koogLlmResponseHistory.add(
      Message.User(
        content = contentString,
        metaInfo = RequestMetaInfo.create(Clock.System),
      ),
    )
  }

  private fun addAssistantMessageToChatHistory(llmContent: String) {
    koogLlmResponseHistory.add(
      Message.Assistant(
        content = llmContent,
        metaInfo = ResponseMetaInfo.create(Clock.System),
      ),
    )
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
