package xyz.block.trailblaze.openai

import xyz.block.trailblaze.agent.AgentTask
import xyz.block.trailblaze.agent.model.Objective
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.commands.ObjectiveCompleteTrailblazeTool

/**
 * Manages objectives for TrailblazeOpenAiRunner.
 * Handles objective creation, progress tracking, and status updates.
 */
class ObjectiveManager {
  // Tracks the last task index for which we printed a progress update
  private var lastPrintedTaskIndex = -1

  // Flag that indicates if a task status update should be forced
  private var forceTaskStatusUpdate = false

  /**
   * Generate a list of objectives by splitting the instructions by line.
   * Each non-empty line becomes an objective with the line text as the description.
   */
  fun generateObjectivesFromLines(instructions: String): List<Objective> {
    // Split instructions by newlines and filter out empty lines
    val objectives = instructions.lines()
      .map { it.trim() }
      .filter { it.isNotEmpty() }
      .mapIndexed { index, line ->
        Objective(
          description = line,
          id = index.toString(),
        )
      }

    // If no objectives were generated, create a default objective with the entire instructions
    return if (objectives.isEmpty()) {
      listOf(
        Objective(
          description = instructions.trim(),
          id = "0",
        ),
      )
    } else {
      objectives
    }
  }

  /**
   * Sets the forceTaskStatusUpdate flag
   */
  fun setForceTaskStatusUpdate(force: Boolean) {
    forceTaskStatusUpdate = force
  }

  /**
   * Gets the current value of forceTaskStatusUpdate flag
   */
  fun getForceTaskStatusUpdate(): Boolean = forceTaskStatusUpdate

  /**
   * Handle an ObjectiveCompleteCommand by updating the task's current objective index and returning success.
   * Verifies that objectives are completed in order and all objectives are completed for a successful run.
   */
  fun handleObjectiveCompleteCommand(
    task: AgentTask,
    command: ObjectiveCompleteTrailblazeTool,
    llmResponseId: String? = null,
  ): TrailblazeToolResult {
    val objectives = task.objectives
    val currentObjectiveIndex = task.currentObjectiveIndex

    // Consistent debug output for all status types
    val currentObjective = if (objectives.isNotEmpty() && currentObjectiveIndex >= 0 && currentObjectiveIndex < objectives.size) {
      objectives[currentObjectiveIndex]
    } else {
      null
    }

    val objectiveInfo = if (currentObjective != null) {
      "Objective ${currentObjectiveIndex + 1}/${objectives.size}: ${currentObjective.description}"
    } else {
      "Objective: Unknown"
    }

    println("\n[OBJECTIVE_STATUS] Status: ${command.status} | Current: $objectiveInfo")
    println("[OBJECTIVE_DESCRIPTION] ${command.description}")
    println("[OBJECTIVE_MESSAGE] ${command.explanation}")

    // Reset the force task status update flag
    forceTaskStatusUpdate = false

    // Ensure there are objectives and we're working on a valid objective
    if (objectives.isEmpty() || currentObjectiveIndex < 0 || currentObjectiveIndex >= objectives.size) {
      // Return failure if there's no objectives or invalid objective index
      val errorMessage = "Cannot complete task: No objectives or invalid objective index"
      println("\u001B[1;31m❌ $errorMessage\u001B[0m")
      return TrailblazeToolResult.Error.UnknownTrailblazeTool(command)
    }

    // Validate the taskDescription matches the current objective
    val expectedDescription = currentObjective?.description
    val providedDescription = command.description.trim()
    val taskDescriptionMatches = expectedDescription?.let {
      it.equals(providedDescription, ignoreCase = true) ||
        providedDescription.contains(it, ignoreCase = true) ||
        it.contains(providedDescription, ignoreCase = true)
    } ?: false

    if (!taskDescriptionMatches && command.status != "in_progress") {
      val errorMessage = "Objective item description mismatch. Expected: '$expectedDescription', but got: '${command.description}'"
      println("\u001B[1;31m❌ $errorMessage\u001B[0m")
      // For in_progress status, we'll be more lenient and just warn
      if (command.status == "completed") {
        return TrailblazeToolResult.Error.UnknownTrailblazeTool(command)
      }
    } else if (!taskDescriptionMatches) {
      // Just warn for in_progress status
      println("\u001B[1;33m⚠️ Objective item description doesn't match current objective item, but continuing for in_progress status\u001B[0m")
    }

    // Handle based on status
    when (command.status) {
      "in_progress" -> {
        // Log the in-progress status
        val startTime = System.currentTimeMillis()
        TrailblazeLogger.log(
          TrailblazeLog.TrailblazeToolLog(
            agentTaskStatus = task.currentStatus.value,
            command = command,
            exceptionMessage = null,
            successful = true,
            duration = 0,
            timestamp = startTime,
            instructions = task.instructions,
            llmResponseId = llmResponseId,
            session = TrailblazeLogger.getCurrentSessionId(),
          ),
        )

        // Print in-progress message with consistent formatting
        println("\u001B[1;33m⏳ IN PROGRESS: $objectiveInfo\u001B[0m")
        println("\u001B[1;33m⏳ Reason: ${command.explanation}\u001B[0m")

        return TrailblazeToolResult.Success
      }

      "failed" -> {
        task.llmReportedCompletion(
          success = false,
          explanation = command.explanation,
        )

        // Log the task failure for better visibility in logs
        val startTime = System.currentTimeMillis()
        TrailblazeLogger.log(
          TrailblazeLog.TrailblazeToolLog(
            agentTaskStatus = task.currentStatus.value,
            command = command,
            exceptionMessage = command.explanation,
            successful = false,
            duration = 0,
            timestamp = startTime,
            instructions = task.instructions,
            llmResponseId = llmResponseId,
            session = TrailblazeLogger.getCurrentSessionId(),
          ),
        )

        // Consistent failure message format
        println("\u001B[1;31m❌ FAILED: $objectiveInfo\u001B[0m")
        println("\u001B[1;31m❌ Reason: ${command.explanation}\u001B[0m")

        return TrailblazeToolResult.Error.UnknownTrailblazeTool(command)
      }

      "completed" -> {
        // Print a more visible task completion message with separator
        println("\u001B[1;32m✅ COMPLETED: $objectiveInfo\u001B[0m")
        println("\u001B[1;32m✅ Reason: ${command.explanation}\u001B[0m")

        // Log the individual task completion for better visibility in logs
        val startTime = System.currentTimeMillis()
        TrailblazeLogger.log(
          TrailblazeLog.TrailblazeToolLog(
            agentTaskStatus = task.currentStatus.value,
            command = command,
            exceptionMessage = null,
            successful = true,
            duration = 0,
            timestamp = startTime,
            instructions = task.instructions,
            llmResponseId = llmResponseId,
            session = TrailblazeLogger.getCurrentSessionId(),
          ),
        )

        // Safely complete current objective and move to next
        val hasMoreObjectives = task.completeCurrentObjectiveAndMoveToNext()

        // Simplified task progression message
        println("[OBJECTIVE_PROGRESS] Objective item completed | More objective items: $hasMoreObjectives | Status: ${task.currentStatus.value.javaClass.simpleName}")

        // Reset the last printed task index to force printing the progress bar for the next objective
        lastPrintedTaskIndex = -1

        // If there are no more objectives, mark the entire task as complete
        if (!hasMoreObjectives) {
          task.markAsComplete()
          // Since we've completed all objectives in order successfully, we can mark the entire objective as complete
          task.llmReportedCompletion(
            success = true,
            explanation = "All objective items completed successfully: ${command.explanation}",
          )
          println("\u001B[1;32m🎉 ALL OBJECTIVE ITEMS COMPLETED - Overall objective achieved successfully!\u001B[0m")

          // Simplified completion message
          println("\n[ALL_COMPLETE] Objective items: ${objectives.size} | Final status: ${task.currentStatus.value.javaClass.simpleName}")
        } else {
          // Print a reminder about the next objective
          val nextObjectiveIndex = task.currentObjectiveIndex
          val nextObjective = objectives[nextObjectiveIndex]

          println("\n\u001B[1;33m⚠️ OBJECTIVE PROGRESSION REMINDER\u001B[0m")
          println("\u001B[1;33m• NEXT FOCUS: Objective ${nextObjectiveIndex + 1}/${objectives.size} - ${nextObjective.description}\u001B[0m\n")
        }
      }

      else -> {
        val errorMessage = "Unknown objective item status: ${command.status}. Expected: 'completed', 'failed', or 'in_progress'."
        println("\u001B[1;31m❌ $errorMessage\u001B[0m")
        return TrailblazeToolResult.Error.UnknownTrailblazeTool(command)
      }
    }

    // Always return success unless explicitly returning error above
    return TrailblazeToolResult.Success
  }

  /**
   * Prints a visual progress bar showing completed and pending objectives
   */
  fun printObjectiveProgressBar(task: AgentTask) {
    val objectives = task.objectives
    if (objectives.isEmpty()) return

    val currentIndex = task.currentObjectiveIndex
    val totalObjectives = objectives.size

    // Only print the progress bar if we haven't printed it for this objective index yet
    if (lastPrintedTaskIndex == currentIndex) {
      return
    }
    lastPrintedTaskIndex = currentIndex

    // Use minimal formatting to reduce memory usage
    println("[OBJECTIVE_PROGRESS] Working on: ${currentIndex + 1}/$totalObjectives - ${objectives[currentIndex].description}")

    // Add a simple completed/remaining count
    val completedObjectives = currentIndex
    val remainingObjectives = totalObjectives - currentIndex
    println("[OBJECTIVE_COUNT] $completedObjectives objective items completed, $remainingObjectives remaining")
  }
}
