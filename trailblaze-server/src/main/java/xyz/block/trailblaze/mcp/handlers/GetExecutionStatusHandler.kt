package xyz.block.trailblaze.mcp.handlers

import xyz.block.trailblaze.agent.ExecutionStatus
import xyz.block.trailblaze.agent.TrailblazeProgressEvent
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.mcp.progress.ProgressSessionManager

/**
 * Handler for getting execution status via MCP.
 *
 * Returns the current execution status for a given session, including:
 * - Current phase/state (RUNNING, COMPLETED, FAILED, etc.)
 * - Progress percentage (0-100)
 * - Current step or subtask being executed
 * - Elapsed time in milliseconds
 * - Total actions taken so far
 * - Recent progress events
 * - Error message (if failed)
 *
 * This handler provides MCP clients with a synchronous way to query
 * execution status without streaming.
 *
 * ## Usage
 *
 * ```kotlin
 * val handler = GetExecutionStatusHandler(progressManager)
 * val status = handler.getStatus("my_session_id")
 * ```
 *
 * @param progressManager The progress session manager for retrieving status
 */
class GetExecutionStatusHandler(
  private val progressManager: ProgressSessionManager,
) {

  /**
   * Gets the current execution status for a session.
   *
   * Returns a snapshot of the execution state including progress metrics
   * and recent events.
   *
   * @param sessionId The session ID to query
   * @return ExecutionStatusResponse containing current status, or null if not found
   */
  fun getStatus(sessionId: String): ExecutionStatusResponse? {
    val sid = SessionId(sessionId)

    // Get the status from the progress manager
    val status = progressManager.getExecutionStatus(sid) ?: return null

    // Get recent events for this session
    val allEvents = progressManager.getEventsForSession(sid)
    val recentEventStrings = allEvents.takeLast(5).map { event ->
      // Format event as a readable string
      formatProgressEventForResponse(event)
    }

    return ExecutionStatusResponse(
      sessionId = sessionId,
      phase = status.state,
      progress = status.progressPercent / 100.0, // Convert percentage to 0-1 range
      currentStep = status.currentStep,
      elapsedMs = status.elapsedMs,
      totalActions = status.actionsExecuted,
      recentEvents = recentEventStrings,
      errorMessage = status.errorMessage,
    )
  }

  /**
   * Formats a progress event into a human-readable string for client consumption.
   *
   * @param event The progress event to format
   * @return A human-readable string representation
   */
  private fun formatProgressEventForResponse(event: TrailblazeProgressEvent): String {
    return when (event) {
      is TrailblazeProgressEvent.ExecutionStarted ->
        "Started: ${event.objective}"
      is TrailblazeProgressEvent.ExecutionCompleted ->
        "Completed: ${if (event.success) "Success" else "Failed"}"
      is TrailblazeProgressEvent.StepStarted ->
        "Step ${event.stepIndex}: ${event.stepPrompt}"
      is TrailblazeProgressEvent.StepCompleted ->
        "Step ${event.stepIndex} ${if (event.success) "completed" else "failed"}"
      is TrailblazeProgressEvent.SubtaskProgress ->
        "Subtask: ${event.subtaskName} (${event.subtaskIndex}/${event.totalSubtasks})"
      is TrailblazeProgressEvent.SubtaskCompleted ->
        "Subtask completed: ${event.subtaskName}"
      is TrailblazeProgressEvent.ReflectionTriggered ->
        "Reflection: ${event.reason}"
      is TrailblazeProgressEvent.BacktrackPerformed ->
        "Backtracked ${event.stepsBacktracked} steps: ${event.reason}"
      is TrailblazeProgressEvent.ExceptionHandled ->
        "Exception handled: ${event.exceptionType}"
      is TrailblazeProgressEvent.FactStored ->
        "Stored fact: ${event.key}"
      is TrailblazeProgressEvent.FactRecalled ->
        "Recalled fact: ${event.key} ${if (event.found) "found" else "not found"}"
      is TrailblazeProgressEvent.TaskReplanned ->
        "Task replanned: ${event.originalSubtask}"
    }
  }
}
