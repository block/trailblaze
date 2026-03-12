package xyz.block.trailblaze.agent

import kotlinx.serialization.Serializable

/**
 * Result of executing a UI action on the device.
 *
 * This sealed class represents the outcome of a tool execution,
 * providing enough information for the outer agent to decide next steps.
 *
 * @see Success for successful execution with screen state update
 * @see Failure for execution errors with recovery information
 */
@Serializable
sealed class ExecutionResult {

  /**
   * UI action executed successfully.
   *
   * @property screenSummaryAfter Description of the screen state after the action completed
   * @property durationMs How long the action took to execute in milliseconds
   */
  @Serializable
  data class Success(
    /** Description of the screen state after the action completed */
    val screenSummaryAfter: String,
    /** How long the action took to execute in milliseconds */
    val durationMs: Long,
  ) : ExecutionResult()

  /**
   * UI action failed to execute.
   *
   * @property error Human-readable description of what went wrong
   * @property recoverable True if the error might be resolved by retrying or trying an alternative action
   */
  @Serializable
  data class Failure(
    /** Human-readable description of what went wrong */
    val error: String,
    /** True if the agent should attempt recovery (retry or alternative action) */
    val recoverable: Boolean,
  ) : ExecutionResult()
}
