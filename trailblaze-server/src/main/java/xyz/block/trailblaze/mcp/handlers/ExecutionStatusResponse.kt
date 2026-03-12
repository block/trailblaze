package xyz.block.trailblaze.mcp.handlers

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.agent.ExecutionState

/**
 * Response containing the current execution status for a session.
 *
 * This data class is used by MCP clients to query the current state of
 * an executing trail or automation task.
 *
 * @param sessionId Unique session identifier
 * @param phase Current execution phase (RUNNING, COMPLETED, FAILED, etc.)
 * @param progress Progress as a decimal between 0.0 and 1.0
 * @param currentStep Description of the current step or subtask being executed
 * @param elapsedMs Time elapsed since execution started in milliseconds
 * @param totalActions Total number of actions taken so far
 * @param recentEvents List of recent progress events (last 5) as human-readable strings
 * @param errorMessage Error message if the execution failed
 */
@Serializable
data class ExecutionStatusResponse(
  val sessionId: String,
  val phase: ExecutionState,
  val progress: Double,
  val currentStep: String?,
  val elapsedMs: Long,
  val totalActions: Int,
  val recentEvents: List<String>,
  val errorMessage: String?,
)
