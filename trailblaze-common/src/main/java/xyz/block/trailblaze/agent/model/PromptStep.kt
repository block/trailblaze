package xyz.block.trailblaze.agent.model

import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.chat.FunctionCall
import com.aallam.openai.api.chat.chatMessage
import kotlinx.coroutines.flow.MutableStateFlow
import xyz.block.trailblaze.api.AgentMessages.toContentString
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

// provide defaults
class PromptStep(
  val description: String,
  val taskId: String = "",
  val taskIndex: Int = 0,
  val fullPrompt: String = "",
  val llmStatusChecks: Boolean = true,
) {
  val llmResponseHistory = mutableListOf<ChatMessage>()

  private val taskCreatedTimestamp = System.currentTimeMillis()
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

  fun addToolCall(
    llmResponseContent: String?,
    commandResult: TrailblazeToolResult,
    function: FunctionCall,
  ) {
    llmResponseContent?.let { llmContent ->
      llmResponseHistory.add(
        chatMessage {
          role = ChatRole.Assistant
          content = llmContent
        },
      )
    }

    llmResponseHistory.add(
      chatMessage {
        role = ChatRole.User
        content = commandResult.toContentString(function)
      },
    )
  }

  fun addEmptyToolCall(
    llmResponseContent: String?,
    result: TrailblazeToolResult,
  ) {
    addToolCall(
      llmResponseContent = llmResponseContent,
      commandResult = result,
      // fake function call since it is not needed in the tool history
      function = FunctionCall(),
    )
  }

  fun markAsComplete() {
    currentStatus.value = AgentTaskStatus.Success.ObjectiveComplete(
      statusData = AgentTaskStatusData(
        prompt = description,
        callCount = llmResponseHistory.size,
        taskStartTime = taskCreatedTimestamp,
        totalDurationMs = System.currentTimeMillis() - taskCreatedTimestamp,
        taskId = taskId,
      ),
      llmExplanation = "All objectives completed successfully",
    )
  }

  fun markAsFailed() {
    currentStatus.value = AgentTaskStatus.Failure.ObjectiveFailed(
      statusData = AgentTaskStatusData(
        prompt = description,
        callCount = llmResponseHistory.size,
        taskStartTime = taskCreatedTimestamp,
        totalDurationMs = System.currentTimeMillis() - taskCreatedTimestamp,
        taskId = taskId,
      ),
      llmExplanation = "The objective failed to complete",
    )
  }
}
