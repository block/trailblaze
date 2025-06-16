package xyz.block.trailblaze.agent.model

import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.api.AgentMessages.toContentString
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * A single step in an overall [TestObjective.TrailblazeObjective.TrailblazePrompt] objective.
 */
class TrailblazePromptStep(
  val description: String,
  val taskId: String = "",
  val taskIndex: Int = 0,
  val fullPrompt: String = "",
  val llmStatusChecks: Boolean = true,
) {
  private val koogLlmResponseHistory = mutableListOf<ai.koog.prompt.message.Message>()

  fun getHistorySize() = koogLlmResponseHistory.size

  fun getKoogLlmResponseHistory(): List<ai.koog.prompt.message.Message> = koogLlmResponseHistory

  private val taskCreatedTimestamp = Clock.System.now()
  val currentStatus = MutableStateFlow<AgentTaskStatus>(
    AgentTaskStatus.InProgress(
      statusData = AgentTaskStatusData(
        prompt = fullPrompt,
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
      ai.koog.prompt.message.Message.User(
        content = contentString,
        metaInfo = RequestMetaInfo.create(Clock.System),
      ),
    )
  }

  private fun addAssistantMessageToChatHistory(llmContent: String) {
    koogLlmResponseHistory.add(
      ai.koog.prompt.message.Message.Assistant(
        content = llmContent,
        metaInfo = ResponseMetaInfo.create(Clock.System),
      ),
    )
  }

  fun addEmptyToolCallToChatHistory(
    llmResponseContent: String?,
    result: TrailblazeToolResult.Error.EmptyToolCall,
  ) {
    addCompletedToolCallToChatHistory(
      llmResponseContent = llmResponseContent,
      commandResult = result,
      toolName = null,
      toolArgs = null,
    )
  }

  fun markAsComplete() {
    currentStatus.value = AgentTaskStatus.Success.ObjectiveComplete(
      statusData = AgentTaskStatusData(
        prompt = description,
        callCount = getHistorySize(),
        taskStartTime = taskCreatedTimestamp,
        totalDurationMs = Clock.System.now().epochSeconds - taskCreatedTimestamp.epochSeconds,
        taskId = taskId,
      ),
      llmExplanation = "All objectives completed successfully",
    )
  }

  fun markAsFailed() {
    currentStatus.value = AgentTaskStatus.Failure.ObjectiveFailed(
      statusData = AgentTaskStatusData(
        prompt = description,
        callCount = getHistorySize(),
        taskStartTime = taskCreatedTimestamp,
        totalDurationMs = Clock.System.now().toEpochMilliseconds() - taskCreatedTimestamp.toEpochMilliseconds(),
        taskId = taskId,
      ),
      llmExplanation = "The objective failed to complete",
    )
  }
}
