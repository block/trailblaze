package xyz.block.trailblaze.agent

import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.chat.FunctionCall
import com.aallam.openai.api.chat.chatMessage
import kotlinx.coroutines.flow.MutableStateFlow
import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.agent.model.AgentTaskStatusData
import xyz.block.trailblaze.agent.model.Objective
import xyz.block.trailblaze.api.AgentMessages.toContentString
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import java.util.UUID

data class AgentTask(
  val instructions: String,
  val maxRuns: Int,
) {
  var agentCallCount = 0

  val taskId = UUID.randomUUID().toString()

  val taskCreatedTimestamp = System.currentTimeMillis()
  val currentStatus = MutableStateFlow<AgentTaskStatus>(
    AgentTaskStatus.InProgress(
      statusData = AgentTaskStatusData(
        prompt = instructions,
        callCount = agentCallCount,
        taskStartTime = taskCreatedTimestamp,
        totalDurationMs = 0,
        taskId = taskId,
      ),
    ),
  )

  private var _objectives = mutableListOf<Objective>()
  val objectives: List<Objective> get() = _objectives.toList()

  private var _currentObjectiveIndex: Int = -1
  val currentObjectiveIndex: Int get() = _currentObjectiveIndex

  val llmResponseHistory = mutableListOf<ChatMessage>()

  fun setObjectives(objectives: List<Objective>) {
    _objectives = objectives.toMutableList()
    _currentObjectiveIndex = 0
    printCurrentObjectiveInfo()
  }

  /**
   * Mark the current objective as complete and move to the next one.
   * Returns true if there are more objectives, false if all objectives are complete.
   */
  fun completeCurrentObjectiveAndMoveToNext(): Boolean {
    if (_objectives.isEmpty() || _currentObjectiveIndex == -1) {
      return false
    }

    val completedObjectiveIndex = _currentObjectiveIndex
    val totalObjectives = _objectives.size
    val completedObjective = _objectives[completedObjectiveIndex]

    // Print minimally formatted completion message for the objective we just finished
    println("[OBJECTIVE_COMPLETE] (${completedObjectiveIndex + 1}/$totalObjectives) ${completedObjective.description}")

    // Update the current objective index to move to the next objective
    _currentObjectiveIndex++
    val hasMoreObjectives = _currentObjectiveIndex < totalObjectives

    // Update the current status to reflect progress through the objectives
    val currentStatusValue = currentStatus.value
    if (currentStatusValue is AgentTaskStatus.InProgress) {
      // Update the status data to include the current objective index
      // This is just for tracking, not changing the overall status type
      val updatedStatusData = currentStatusValue.statusData.copy(
        totalDurationMs = getCurrentDurationMs(),
      )
      currentStatus.value = currentStatusValue.copy(
        statusData = updatedStatusData,
      )
    }

    if (hasMoreObjectives) {
      // If there are more objectives, print information about the next objective
      printCurrentObjectiveInfo()
    } else {
      // No more objectives - print a simplified completion message
      println("[ALL_OBJECTIVES_COMPLETE] ($totalObjectives/$totalObjectives)")
    }

    return hasMoreObjectives
  }

  fun printCurrentObjectiveInfo() {
    if (_objectives.isEmpty() || _currentObjectiveIndex == -1 || _currentObjectiveIndex >= _objectives.size) {
      return
    }

    val currentObjective = _objectives[_currentObjectiveIndex]
    println("[CURRENT_OBJECTIVE] ${_currentObjectiveIndex + 1}/${_objectives.size}: ${currentObjective.description}")
  }

  fun getCurrentObjective(): Objective? {
    if (_objectives.isEmpty() || _currentObjectiveIndex == -1 || _currentObjectiveIndex >= _objectives.size) {
      return null
    }

    return _objectives[_currentObjectiveIndex]
  }

  fun getCurrentDurationMs(): Long {
    val duration = System.currentTimeMillis() - taskCreatedTimestamp
    println("Current duration: $duration ms")
    return duration
  }

  // Function that the runner can call to determine if it should keep processing the task.
  // When the task is finished it means that no more calls to the LLM will be made. The task can
  // either complete successfully or one of several failure modes such as hitting the max call limit
  // without completing the task or via some other error
  fun isFinished(): Boolean = currentStatus.value !is AgentTaskStatus.InProgress

  fun llmReportedCompletion(success: Boolean, explanation: String) {
    currentStatus.value = if (success) {
      AgentTaskStatus.Success.ObjectiveComplete(
        statusData = AgentTaskStatusData(
          prompt = instructions,
          callCount = agentCallCount,
          taskStartTime = taskCreatedTimestamp,
          totalDurationMs = getCurrentDurationMs(),
          taskId = taskId,
        ),
        llmExplanation = explanation,
      )
    } else {
      AgentTaskStatus.Failure.ObjectiveFailed(
        statusData = AgentTaskStatusData(
          prompt = instructions,
          callCount = agentCallCount,
          taskStartTime = taskCreatedTimestamp,
          totalDurationMs = getCurrentDurationMs(),
          taskId = taskId,
        ),
        llmExplanation = explanation,
      )
    }
  }

  fun markAsFailed() {
    currentStatus.value = AgentTaskStatus.Failure.ObjectiveFailed(
      statusData = AgentTaskStatusData(
        prompt = instructions,
        callCount = agentCallCount,
        taskStartTime = taskCreatedTimestamp,
        totalDurationMs = getCurrentDurationMs(),
        taskId = taskId,
      ),
      llmExplanation = "The objective failed to complete",
    )
  }

  fun markAsComplete() {
    currentStatus.value = AgentTaskStatus.Success.ObjectiveComplete(
      statusData = AgentTaskStatusData(
        prompt = instructions,
        callCount = agentCallCount,
        taskStartTime = taskCreatedTimestamp,
        totalDurationMs = getCurrentDurationMs(),
        taskId = taskId,
      ),
      llmExplanation = "All objectives completed successfully",
    )
  }

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

  fun increaseLlmCallCount() {
    val curr = currentStatus.value
    when (curr) {
      is AgentTaskStatus.InProgress -> {
        agentCallCount++
        val newStatusData = curr.statusData.copy(
          callCount = agentCallCount,
        )
        this.currentStatus.value = curr.copy(
          statusData = newStatusData,
        )
      }

      else -> {}
    }

    if (agentCallCount >= maxRuns) {
      currentStatus.value = AgentTaskStatus.Failure.MaxCallsLimitReached(
        statusData = AgentTaskStatusData(
          prompt = instructions,
          callCount = agentCallCount,
          taskStartTime = taskCreatedTimestamp,
          totalDurationMs = getCurrentDurationMs(),
          taskId = taskId,
        ),
      )
      println("⚠️ Reached maximum number of agent calls ($maxRuns) - stopping and marking as failure")
    }
  }

  fun printResultsToConsole() {
    // If the agent thinks the objective is completed, but we haven't explicitly marked success,
    // there's something wrong with the flow. Print a warning so the user knows something is up.
    val state = currentStatus.value
    when (state) {
      is AgentTaskStatus.InProgress -> {
        println("[WARNING] Task ended without success or failure status")
      }

      is AgentTaskStatus.Failure -> {
        println("[FAILURE] Task failed ($agentCallCount calls)")
        if (state is AgentTaskStatus.Failure.ObjectiveFailed) {
          state.llmExplanation.let {
            println("[REASON] $it")
          }
        }
      }

      is AgentTaskStatus.Success.ObjectiveComplete -> {
        println("[SUCCESS] Task completed ($agentCallCount calls)")
        state.llmExplanation.let {
          println("[DETAILS] $it")
        }
      }
    }

    // Print objective completion summary if we had objectives
    if (_objectives.isNotEmpty()) {
      println("\n[OBJECTIVE_LIST_SUMMARY]")

      // When the task is successfully completed, mark all objectives as complete
      val allObjectivesComplete = state is AgentTaskStatus.Success.ObjectiveComplete

      _objectives.forEachIndexed { index, objective ->
        val isComplete = allObjectivesComplete || index <= _currentObjectiveIndex
        val status = if (isComplete) "✓" else "○"
        println("$status ${index + 1}. ${objective.description}")
      }
    }
  }
}
