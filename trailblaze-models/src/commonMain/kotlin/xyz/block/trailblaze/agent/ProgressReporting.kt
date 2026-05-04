package xyz.block.trailblaze.agent

import kotlin.concurrent.Volatile
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.util.Console

/**
 * Progress events for real-time reporting during trail/blaze execution.
 *
 * MCP clients (Cursor, Claude, VS Code, etc.) can subscribe to these events
 * to provide users with detailed progress information during automation.
 *
 * ## Event Types
 *
 * - **Step Events**: Track individual step start/completion
 * - **Subtask Events**: Track task decomposition progress (Phase 3)
 * - **Reflection Events**: Report self-assessment and course corrections (Phase 2)
 * - **Exception Events**: Report recovery from popups, errors, etc. (Phase 1)
 * - **Memory Events**: Report cross-app memory operations (Phase 4)
 *
 * ## Usage
 *
 * ```kotlin
 * // Subscribe to progress events
 * executor.progressEvents.collect { event ->
 *   when (event) {
 *     is TrailblazeProgressEvent.StepStarted -> {
 *       Console.log("Starting step ${event.stepIndex}: ${event.stepPrompt}")
 *     }
 *     is TrailblazeProgressEvent.StepCompleted -> {
 *       Console.log("Completed step ${event.stepIndex} in ${event.durationMs}ms")
 *     }
 *     // ... handle other events
 *   }
 * }
 * ```
 *
 * @see ExecutionStatus for overall execution state
 */
@Serializable
sealed interface TrailblazeProgressEvent {

  /** Timestamp when the event occurred (epoch milliseconds) */
  val timestamp: Long

  /** Session ID for correlating events */
  val sessionId: SessionId

  /** Device ID for tracking progress per-device in parallel execution */
  val deviceId: TrailblazeDeviceId?

  // =========================================================================
  // Step Progress Events
  // =========================================================================

  /**
   * A step has started execution.
   *
   * @property stepIndex Zero-based index of the step
   * @property stepPrompt The prompt/description for this step
   * @property estimatedDurationMs Estimated execution time in milliseconds
   * @property totalSteps Total number of steps in the trail
   */
  @Serializable
  data class StepStarted(
    override val timestamp: Long,
    override val sessionId: SessionId,
    override val deviceId: TrailblazeDeviceId?,
    val stepIndex: Int,
    val stepPrompt: String,
    val estimatedDurationMs: Long = 5000L,
    val totalSteps: Int,
  ) : TrailblazeProgressEvent

  /**
   * A step has completed execution.
   *
   * @property stepIndex Zero-based index of the step
   * @property usedRecording True if recording was used, false if self-heal
   * @property durationMs Actual execution time in milliseconds
   * @property success True if the step completed successfully
   * @property errorMessage Error message if step failed
   */
  @Serializable
  data class StepCompleted(
    override val timestamp: Long,
    override val sessionId: SessionId,
    override val deviceId: TrailblazeDeviceId?,
    val stepIndex: Int,
    val usedRecording: Boolean,
    val durationMs: Long,
    val success: Boolean,
    val errorMessage: String? = null,
  ) : TrailblazeProgressEvent

  // =========================================================================
  // Subtask Progress Events (Phase 3: Task Decomposition)
  // =========================================================================

  /**
   * Progress update for task decomposition.
   *
   * Reports progress on individual subtasks when task decomposition is enabled.
   *
   * @property subtaskIndex Current subtask index (zero-based)
   * @property subtaskName Description of the current subtask
   * @property totalSubtasks Total number of subtasks
   * @property percentComplete Percentage complete for current subtask (0-100)
   * @property actionsInSubtask Number of actions taken in current subtask
   */
  @Serializable
  data class SubtaskProgress(
    override val timestamp: Long,
    override val sessionId: SessionId,
    override val deviceId: TrailblazeDeviceId?,
    val subtaskIndex: Int,
    val subtaskName: String,
    val totalSubtasks: Int,
    val percentComplete: Int,
    val actionsInSubtask: Int,
  ) : TrailblazeProgressEvent

  /**
   * A subtask has been completed.
   *
   * @property subtaskIndex Completed subtask index
   * @property subtaskName Description of the completed subtask
   * @property durationMs Time to complete the subtask
   * @property actionsTaken Number of actions taken
   */
  @Serializable
  data class SubtaskCompleted(
    override val timestamp: Long,
    override val sessionId: SessionId,
    override val deviceId: TrailblazeDeviceId?,
    val subtaskIndex: Int,
    val subtaskName: String,
    val durationMs: Long,
    val actionsTaken: Int,
  ) : TrailblazeProgressEvent

  /**
   * Task plan has been replanned due to blockers.
   *
   * @property originalSubtask The subtask that was blocked
   * @property blockReason Why the subtask was blocked
   * @property newSubtasksCount Number of new subtasks added
   * @property replanNumber Which replan attempt this is
   */
  @Serializable
  data class TaskReplanned(
    override val timestamp: Long,
    override val sessionId: SessionId,
    override val deviceId: TrailblazeDeviceId?,
    val originalSubtask: String,
    val blockReason: String,
    val newSubtasksCount: Int,
    val replanNumber: Int,
  ) : TrailblazeProgressEvent

  // =========================================================================
  // Reflection Events (Phase 2: Self-Correction)
  // =========================================================================

  /**
   * Reflection/self-assessment was triggered.
   *
   * @property reason Why reflection was triggered (loop, low confidence, etc.)
   * @property assessment Summary of the reflection assessment
   * @property suggestedAction Suggested course correction (if any)
   * @property isOnTrack Whether the agent appears to be making progress
   */
  @Serializable
  data class ReflectionTriggered(
    override val timestamp: Long,
    override val sessionId: SessionId,
    override val deviceId: TrailblazeDeviceId?,
    val reason: String,
    val assessment: String,
    val suggestedAction: String?,
    val isOnTrack: Boolean,
  ) : TrailblazeProgressEvent

  /**
   * Backtrack was performed based on reflection.
   *
   * @property stepsBacktracked Number of steps that were undone
   * @property reason Why backtracking was needed
   */
  @Serializable
  data class BacktrackPerformed(
    override val timestamp: Long,
    override val sessionId: SessionId,
    override val deviceId: TrailblazeDeviceId?,
    val stepsBacktracked: Int,
    val reason: String,
  ) : TrailblazeProgressEvent

  // =========================================================================
  // Exception Handling Events (Phase 1: Recovery)
  // =========================================================================

  /**
   * An exceptional screen state was handled.
   *
   * @property exceptionType Type of exception (POPUP_DIALOG, ADVERTISEMENT, etc.)
   * @property recoveryAction Action taken to recover
   * @property success Whether recovery was successful
   */
  @Serializable
  data class ExceptionHandled(
    override val timestamp: Long,
    override val sessionId: SessionId,
    override val deviceId: TrailblazeDeviceId?,
    val exceptionType: String,
    val recoveryAction: String,
    val success: Boolean,
  ) : TrailblazeProgressEvent

  // =========================================================================
  // Memory Events (Phase 4: Cross-App Memory)
  // =========================================================================

  /**
   * A fact was stored in working memory.
   *
   * @property key The key used to store the fact
   * @property valuePreview Preview of the stored value (truncated for privacy)
   */
  @Serializable
  data class FactStored(
    override val timestamp: Long,
    override val sessionId: SessionId,
    override val deviceId: TrailblazeDeviceId?,
    val key: String,
    val valuePreview: String,
  ) : TrailblazeProgressEvent

  /**
   * A fact was recalled from working memory.
   *
   * @property key The key that was recalled
   * @property found Whether the fact was found
   */
  @Serializable
  data class FactRecalled(
    override val timestamp: Long,
    override val sessionId: SessionId,
    override val deviceId: TrailblazeDeviceId?,
    val key: String,
    val found: Boolean,
  ) : TrailblazeProgressEvent

  // =========================================================================
  // Execution Lifecycle Events
  // =========================================================================

  /**
   * Execution has started.
   *
   * @property objective The objective being achieved
   * @property agentImplementation The agent implementation being used
   * @property hasTaskPlan Whether task decomposition is active
   */
  @Serializable
  data class ExecutionStarted(
    override val timestamp: Long,
    override val sessionId: SessionId,
    override val deviceId: TrailblazeDeviceId?,
    val objective: String,
    val agentImplementation: AgentImplementation,
    val hasTaskPlan: Boolean,
  ) : TrailblazeProgressEvent

  /**
   * Execution has completed.
   *
   * @property success Whether the objective was achieved
   * @property totalDurationMs Total execution time
   * @property totalActions Total actions taken
   * @property errorMessage Error message if execution failed
   */
  @Serializable
  data class ExecutionCompleted(
    override val timestamp: Long,
    override val sessionId: SessionId,
    override val deviceId: TrailblazeDeviceId?,
    val success: Boolean,
    val totalDurationMs: Long,
    val totalActions: Int,
    val errorMessage: String? = null,
  ) : TrailblazeProgressEvent
}

/**
 * Overall execution status for a session.
 *
 * Provides a snapshot of the current execution state, useful for
 * status queries and dashboard displays.
 */
@Serializable
data class ExecutionStatus(
  /** Unique session identifier */
  val sessionId: SessionId,

  /** Device ID for tracking progress per-device in parallel execution */
  val deviceId: TrailblazeDeviceId?,

  /** Current state of execution */
  val state: ExecutionState,

  /** The objective being achieved */
  val objective: String,

  /** The agent implementation being used */
  val agentImplementation: AgentImplementation,

  /** Overall progress percentage (0-100) */
  val progressPercent: Int,

  /** Current step or subtask being executed */
  val currentStep: String?,

  /** Number of steps/subtasks completed */
  val completedSteps: Int,

  /** Total number of steps/subtasks */
  val totalSteps: Int,

  /** Number of actions executed so far */
  val actionsExecuted: Int,

  /** Elapsed time in milliseconds */
  val elapsedMs: Long,

  /** Estimated remaining time in milliseconds */
  val estimatedRemainingMs: Long?,

  /** Number of exceptions handled during execution */
  val exceptionsHandled: Int,

  /** Number of facts stored in working memory */
  val memoryFactCount: Int,

  /** Error message if in ERROR state */
  val errorMessage: String? = null,

  /** Last update timestamp (epoch milliseconds) */
  val lastUpdated: Long,
)

/**
 * States for execution lifecycle.
 */
@Serializable
enum class ExecutionState {
  /** Execution has not started */
  NOT_STARTED,

  /** Currently executing steps */
  RUNNING,

  /** Paused (e.g., waiting for user input) */
  PAUSED,

  /** Completed successfully */
  COMPLETED,

  /** Failed with error */
  FAILED,

  /** Cancelled by user */
  CANCELLED,
}

/**
 * Callback interface for receiving progress events.
 *
 * Implementations can forward events to MCP clients, log them,
 * or update UI displays.
 */
interface ProgressEventListener {
  /**
   * Called when a progress event occurs.
   *
   * @param event The progress event
   */
  fun onProgressEvent(event: TrailblazeProgressEvent)
}

/**
 * Builder for creating execution status snapshots.
 */
class ExecutionStatusBuilder(
  private val sessionId: SessionId,
  private val objective: String,
  private val agentImplementation: AgentImplementation,
  private val deviceId: TrailblazeDeviceId? = null,
) {
  @Volatile private var state: ExecutionState = ExecutionState.NOT_STARTED
  @Volatile private var currentStep: String? = null
  @Volatile private var completedSteps: Int = 0
  @Volatile private var totalSteps: Int = 0
  @Volatile private var actionsExecuted: Int = 0
  @Volatile private var startTimeMs: Long = 0
  @Volatile private var exceptionsHandled: Int = 0
  @Volatile private var memoryFactCount: Int = 0
  @Volatile private var errorMessage: String? = null
  @Volatile private var estimatedDurationPerStep: Long = 5000L

  fun setState(state: ExecutionState) = apply { this.state = state }
  fun setCurrentStep(step: String?) = apply { this.currentStep = step }
  fun setCompletedSteps(count: Int) = apply { this.completedSteps = count }
  fun setTotalSteps(count: Int) = apply { this.totalSteps = count }
  fun incrementActions() = apply { this.actionsExecuted++ }
  fun setStartTime(timeMs: Long) = apply { this.startTimeMs = timeMs }
  fun incrementExceptionsHandled() = apply { this.exceptionsHandled++ }
  fun setMemoryFactCount(count: Int) = apply { this.memoryFactCount = count }
  fun setError(message: String) = apply {
    this.state = ExecutionState.FAILED
    this.errorMessage = message
  }

  /**
   * Builds the execution status snapshot.
   *
   * @param currentTimeMs Current time in epoch milliseconds (caller provides for multiplatform support)
   */
  fun build(currentTimeMs: Long): ExecutionStatus {
    val elapsed = if (startTimeMs > 0) currentTimeMs - startTimeMs else 0L
    val progressPercent = if (totalSteps > 0) {
      ((completedSteps.toFloat() / totalSteps) * 100).toInt()
    } else {
      0
    }
    val estimatedRemaining = if (totalSteps > 0 && completedSteps > 0) {
      val avgTimePerStep = elapsed / completedSteps
      avgTimePerStep * (totalSteps - completedSteps)
    } else if (totalSteps > 0) {
      estimatedDurationPerStep * totalSteps
    } else {
      null
    }

    return ExecutionStatus(
      sessionId = sessionId,
      deviceId = deviceId,
      state = state,
      objective = objective,
      agentImplementation = agentImplementation,
      progressPercent = progressPercent,
      currentStep = currentStep,
      completedSteps = completedSteps,
      totalSteps = totalSteps,
      actionsExecuted = actionsExecuted,
      elapsedMs = elapsed,
      estimatedRemainingMs = estimatedRemaining,
      exceptionsHandled = exceptionsHandled,
      memoryFactCount = memoryFactCount,
      errorMessage = errorMessage,
      lastUpdated = currentTimeMs,
    )
  }
}

/**
 * Creates a new ExecutionStatusBuilder.
 *
 * @param sessionId Unique session identifier
 * @param objective The objective being achieved
 * @param agentImplementation The agent implementation being used
 * @param deviceId Device ID for tracking progress per-device
 * @return A new builder instance
 */
fun executionStatusBuilder(
  sessionId: SessionId,
  objective: String,
  agentImplementation: AgentImplementation,
  deviceId: TrailblazeDeviceId? = null,
): ExecutionStatusBuilder = ExecutionStatusBuilder(sessionId, objective, agentImplementation, deviceId)
