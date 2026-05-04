package xyz.block.trailblaze.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.devices.TrailblazeDeviceId

/**
 * Execution mode for trail playback.
 *
 * Controls how a trail file is executed, balancing between deterministic
 * replay of recordings and AI-powered step execution.
 *
 * ## Cost vs. Reliability Trade-offs
 *
 * - [DETERMINISTIC]: Zero LLM cost, fastest, but requires recordings for all steps
 * - [RECORDING_WITH_FALLBACK]: Low cost, uses AI only when recordings fail
 * - [HYBRID]: Medium cost, uses AI for analysis but recordings for execution
 * - [AI_ONLY]: Highest cost, most flexible, ignores recordings entirely
 *
 * @see TrailState for execution state tracking
 */
@Serializable
enum class TrailExecutionMode {
  /**
   * Execute only from recordings, fail if any recording is missing or fails.
   *
   * Use this mode for:
   * - CI/CD pipelines where determinism is critical
   * - Cost-sensitive environments
   * - Tests that must be reproducible
   *
   * Zero LLM calls are made in this mode.
   */
  DETERMINISTIC,

  /**
   * Try recordings first, fall back to AI if recording fails or is missing.
   *
   * Use this mode for:
   * - Development and recording generation
   * - Tests with partial recordings
   * - Environments where occasional self-heal is acceptable
   *
   * LLM calls are only made when recordings fail.
   */
  RECORDING_WITH_FALLBACK,

  /**
   * Use AI for screen analysis but prefer recordings for action execution.
   *
   * Use this mode for:
   * - Tests that need AI verification of screen state
   * - Complex flows where recordings need validation
   * - Generating more robust recordings
   *
   * Inner agent is used for analysis, recordings for execution.
   */
  HYBRID,

  /**
   * Use AI for all steps, ignoring any recordings.
   *
   * Use this mode for:
   * - Recording generation from scratch
   * - Exploratory testing
   * - Debugging recording failures
   *
   * All steps use LLM calls regardless of available recordings.
   */
  AI_ONLY,
}

/**
 * State of a trail execution session.
 *
 * Tracks progress through a predefined sequence of steps from a .trail.yaml file.
 * This state is used by the goal planner to determine which actions to take and
 * to track completion progress.
 *
 * @property steps The list of step prompts to execute in order
 * @property currentStepIndex Zero-based index of the step currently being executed
 * @property completedSteps Indices of steps that have been successfully completed
 * @property failed True if execution has failed and cannot continue
 * @property failureReason Human-readable explanation of why execution failed
 * @property usedRecordings Map of step index to whether recording was used (true) or AI (false)
 * @property retryCount Number of retry attempts on the current step
 */
@Serializable
data class TrailState(
  /** The list of step prompts to execute in order */
  val steps: List<String>,
  /** Zero-based index of the step currently being executed */
  val currentStepIndex: Int = 0,
  /** Indices of steps that have been successfully completed */
  val completedSteps: Set<Int> = emptySet(),
  /** True if execution has failed and cannot continue */
  val failed: Boolean = false,
  /** Human-readable explanation of why execution failed */
  val failureReason: String? = null,
  /** Map of step index to whether recording was used (true) or AI was used (false) */
  val usedRecordings: Map<Int, Boolean> = emptyMap(),
  /** Number of retry attempts on the current step */
  val retryCount: Int = 0,
  /** Target device ID for parallel execution tracking */
  val targetDeviceId: TrailblazeDeviceId? = null,
) {
  /** True if all steps have been completed successfully */
  val isComplete: Boolean
    get() = completedSteps.size == steps.size && !failed

  /** The prompt for the current step, or null if all steps are complete or failed */
  val currentStepPrompt: String?
    get() = steps.getOrNull(currentStepIndex)

  /** Progress as a percentage (0.0 to 1.0) */
  val progress: Float
    get() = if (steps.isEmpty()) 1.0f else completedSteps.size.toFloat() / steps.size

  /** Number of steps that used recordings vs AI */
  val recordingUsageStats: RecordingUsageStats
    get() = RecordingUsageStats(
      recordingSteps = usedRecordings.count { it.value },
      aiSteps = usedRecordings.count { !it.value },
      pendingSteps = steps.size - completedSteps.size,
    )
}

/**
 * Statistics about recording vs AI usage in trail execution.
 *
 * @property recordingSteps Number of steps executed from recordings
 * @property aiSteps Number of steps executed using AI
 * @property pendingSteps Number of steps not yet executed
 */
@Serializable
data class RecordingUsageStats(
  /** Number of steps executed from recordings */
  val recordingSteps: Int,
  /** Number of steps executed using AI */
  val aiSteps: Int,
  /** Number of steps not yet executed */
  val pendingSteps: Int,
) {
  /** Total LLM cost is zero if all executed steps used recordings */
  val isZeroLlmCost: Boolean
    get() = aiSteps == 0
}

/**
 * State of a blaze exploration session.
 *
 * Tracks progress toward an objective through screen analysis and action execution.
 * Unlike [TrailState], blaze discovers actions dynamically based on screen content.
 *
 * @property objective The goal to achieve, expressed in natural language
 * @property actionHistory List of actions taken during this exploration session
 * @property iteration Number of analysis-action cycles completed
 * @property achieved True if the objective has been achieved
 * @property stuck True if the agent cannot make progress toward the objective
 * @property stuckReason Explanation of why the agent is stuck
 * @property screenSummary Description of the current screen state
 * @property reflectionNotes Notes from reflection nodes (if enabled)
 */
@Serializable
data class BlazeState(
  /** The goal to achieve, expressed in natural language */
  val objective: String,
  /** List of actions taken during this exploration session */
  val actionHistory: List<RecordedAction> = emptyList(),
  /** Number of analysis-action cycles completed */
  val iteration: Int = 0,
  /** True if the objective has been achieved */
  val achieved: Boolean = false,
  /** True if the agent cannot make progress toward the objective */
  val stuck: Boolean = false,
  /** Explanation of why the agent is stuck */
  val stuckReason: String? = null,
  /** Description of the current screen state */
  val screenSummary: String? = null,
  /** Notes from reflection nodes (if enabled) */
  val reflectionNotes: List<String> = emptyList(),
  /** Task plan for complex objectives (from Phase 3 task decomposition) */
  val taskPlan: TaskPlan? = null,
  /** Actions taken for the current subtask (reset on subtask completion) */
  val currentSubtaskActions: Int = 0,
  /** Working memory for cross-app workflows (from Phase 4 cross-app memory) */
  val workingMemory: WorkingMemory = WorkingMemory.EMPTY,
  /** Target device ID for parallel execution tracking */
  val targetDeviceId: TrailblazeDeviceId? = null,
  /**
   * Summary of the initial screen state before any agent actions.
   * Used to distinguish pre-existing state from changes made by the agent.
   * For example, if an alarm already exists on screen when the task starts,
   * the agent should not consider the "create alarm" task already done.
   */
  val initialScreenSummary: String? = null,
) {
  /** True if exploration is complete (either achieved or stuck) */
  val isComplete: Boolean
    get() = achieved || stuck

  /** Number of actions taken so far */
  val actionCount: Int
    get() = actionHistory.size

  /** The current subtask being executed, if task decomposition is active */
  val currentSubtask: Subtask?
    get() = taskPlan?.currentSubtask

  /** True if task decomposition is being used */
  val hasTaskPlan: Boolean
    get() = taskPlan != null

  /** Progress summary including subtask information if available */
  val progressSummary: String
    get() = buildString {
      append("Iteration $iteration")
      taskPlan?.let { plan ->
        append(", Subtask ${plan.currentSubtaskIndex + 1}/${plan.subtasks.size}")
        plan.currentSubtask?.let { subtask ->
          append(": ${subtask.description}")
        }
      }
      append(", ${actionHistory.size} total actions")
      if (workingMemory.hasContent) {
        append(", ${workingMemory.factCount} facts in memory")
      }
    }

  /** True if working memory contains stored information */
  val hasWorkingMemory: Boolean
    get() = workingMemory.hasContent
}

/**
 * A recorded action from a blaze exploration or AI-powered trail step.
 *
 * Captures all information needed to replay an action deterministically,
 * including the screen context in which the action was taken.
 *
 * @property toolName Name of the tool that was executed (e.g., "tap", "input_text")
 * @property toolArgs Arguments passed to the tool
 * @property reasoning Explanation of why this action was chosen
 * @property screenSummaryBefore Description of the screen state before the action
 * @property screenSummaryAfter Description of the screen state after the action
 * @property confidence Confidence level of the recommendation
 * @property durationMs How long the action took to execute in milliseconds
 * @property success True if the action executed successfully
 * @property errorMessage Error message if the action failed
 */
@Serializable
data class RecordedAction(
  /** Name of the tool that was executed (e.g., "tap", "input_text", "scroll") */
  val toolName: String,
  /** Arguments passed to the tool as a JSON object */
  val toolArgs: JsonObject,
  /** Explanation of why this action was chosen based on the screen state */
  val reasoning: String,
  /** Description of the screen state before the action */
  val screenSummaryBefore: String? = null,
  /** Description of the screen state after the action */
  val screenSummaryAfter: String? = null,
  /** Confidence level of the recommendation */
  val confidence: Confidence? = null,
  /** How long the action took to execute in milliseconds */
  val durationMs: Long? = null,
  /** True if the action executed successfully */
  val success: Boolean = true,
  /** Error message if the action failed */
  val errorMessage: String? = null,
)

/**
 * Result of executing a trail.
 *
 * Represents the final outcome of running a trail file, including
 * execution statistics and any generated recordings.
 *
 * @property success True if all steps completed successfully
 * @property state Final state of the trail execution
 * @property durationMs Total execution time in milliseconds
 * @property generatedRecordings Actions recorded during self-heal execution
 * @property errorMessage Error message if execution failed
 */
@Serializable
data class TrailResult(
  /** True if all steps completed successfully */
  val success: Boolean,
  /** Final state of the trail execution */
  val state: TrailState,
  /** Total execution time in milliseconds */
  val durationMs: Long,
  /** Actions recorded during self-heal execution (for recording generation) */
  val generatedRecordings: Map<Int, List<RecordedAction>> = emptyMap(),
  /** Error message if execution failed */
  val errorMessage: String? = null,
  /** Target device ID for parallel execution tracking */
  val targetDeviceId: TrailblazeDeviceId? = null,
) {
  /** True if any recordings were generated during execution */
  val hasGeneratedRecordings: Boolean
    get() = generatedRecordings.isNotEmpty()
}

/**
 * Result of executing a blaze exploration.
 *
 * Represents the final outcome of exploring toward an objective,
 * including the discovered action sequence that can be saved as a trail.
 *
 * @property success True if the objective was achieved
 * @property state Final state of the blaze exploration
 * @property durationMs Total exploration time in milliseconds
 * @property recordedActions All actions taken during exploration (for trail generation)
 * @property errorMessage Error message if exploration failed
 */
@Serializable
data class BlazeResult(
  /** True if the objective was achieved */
  val success: Boolean,
  /** Final state of the blaze exploration */
  val state: BlazeState,
  /** Total exploration time in milliseconds */
  val durationMs: Long,
  /** All actions taken during exploration (for trail generation) */
  val recordedActions: List<RecordedAction> = emptyList(),
  /** Error message if exploration failed */
  val errorMessage: String? = null,
  /** Target device ID for parallel execution tracking */
  val targetDeviceId: TrailblazeDeviceId? = null,
) {
  /** Number of actions taken to achieve (or fail) the objective */
  val actionCount: Int
    get() = recordedActions.size
}

// =============================================================================
// Task Decomposition Models (Phase 3: Mobile-Agent-v3 Integration)
// =============================================================================

/**
 * A plan of subtasks to achieve a complex objective.
 *
 * Task decomposition breaks high-level objectives into manageable steps,
 * improving success rate for complex goals. This is inspired by Mobile-Agent-v3's
 * dynamic task decomposition capability.
 *
 * ## Example
 *
 * ```
 * Objective: "Order a large pepperoni pizza from Domino's"
 *
 * TaskPlan:
 *   1. Open Domino's app
 *   2. Navigate to pizza menu
 *   3. Select large pepperoni pizza
 *   4. Add to cart
 *   5. Proceed to checkout
 *   6. Confirm order
 * ```
 *
 * @property objective The original high-level objective
 * @property subtasks Ordered list of subtasks to complete
 * @property currentSubtaskIndex Index of the subtask currently being executed
 * @property replanCount Number of times the plan has been revised
 */
@Serializable
data class TaskPlan(
  /** The original high-level objective */
  val objective: String,
  /** Ordered list of subtasks to complete */
  val subtasks: List<Subtask>,
  /** Index of the subtask currently being executed (0-based) */
  val currentSubtaskIndex: Int = 0,
  /** Number of times the plan has been revised due to blockers */
  val replanCount: Int = 0,
) {
  /** The current subtask being executed, or null if all complete */
  val currentSubtask: Subtask?
    get() = subtasks.getOrNull(currentSubtaskIndex)

  /** True if all subtasks have been completed */
  val isComplete: Boolean
    get() = currentSubtaskIndex >= subtasks.size

  /** Progress as a percentage (0.0 to 1.0) */
  val progress: Float
    get() = if (subtasks.isEmpty()) 1.0f
      else (currentSubtaskIndex.toFloat() / subtasks.size).coerceIn(0.0f, 1.0f)

  /** Number of completed subtasks */
  val completedCount: Int
    get() = subtasks.count { it.completed }

  /** Number of remaining subtasks */
  val remainingCount: Int
    get() = subtasks.size - completedCount

  /**
   * Returns a new TaskPlan with the current subtask marked as completed
   * and the index advanced to the next subtask.
   */
  fun advanceToNextSubtask(): TaskPlan {
    val updatedSubtasks = subtasks.mapIndexed { index, subtask ->
      if (index == currentSubtaskIndex) subtask.copy(completed = true) else subtask
    }
    return copy(
      subtasks = updatedSubtasks,
      currentSubtaskIndex = currentSubtaskIndex + 1,
    )
  }

  /**
   * Returns a new TaskPlan with updated subtasks from replanning.
   */
  fun replan(newSubtasks: List<Subtask>): TaskPlan = copy(
    subtasks = subtasks.take(currentSubtaskIndex) +
      subtasks.getOrNull(currentSubtaskIndex)?.copy(blocked = true).let { listOfNotNull(it) } +
      newSubtasks,
    currentSubtaskIndex = currentSubtaskIndex + 1,
    replanCount = replanCount + 1,
  )
}

/**
 * A single subtask within a [TaskPlan].
 *
 * Subtasks are atomic units of work that can be completed in a few actions.
 * Each subtask has clear success criteria for determining completion.
 *
 * @property description What this subtask should accomplish
 * @property successCriteria Observable criteria to verify completion
 * @property estimatedActions Estimated number of UI actions to complete
 * @property completed True if this subtask has been completed
 * @property blocked True if this subtask was blocked and caused replanning
 * @property actionsTaken Actual number of actions taken for this subtask
 */
@Serializable
data class Subtask(
  /** What this subtask should accomplish, in imperative form */
  val description: String,
  /** Observable criteria to verify the subtask is complete */
  val successCriteria: String,
  /** Estimated number of UI actions to complete this subtask */
  val estimatedActions: Int = 5,
  /** True if this subtask has been completed successfully */
  val completed: Boolean = false,
  /** True if this subtask was blocked and caused replanning */
  val blocked: Boolean = false,
  /** Actual number of actions taken for this subtask (populated after completion) */
  val actionsTaken: Int = 0,
)

/**
 * Result of task decomposition by the PlanningNode.
 *
 * @property plan The generated task plan with subtasks
 * @property reasoning Explanation of how the objective was decomposed
 * @property confidence Confidence in the plan's correctness
 */
@Serializable
data class DecompositionResult(
  /** The generated task plan with subtasks */
  val plan: TaskPlan,
  /** Explanation of how the objective was decomposed */
  val reasoning: String,
  /** Confidence in the plan's correctness */
  val confidence: Confidence,
)

/**
 * Result of replanning when the original plan is blocked.
 *
 * @property originalPlan The plan before replanning
 * @property newSubtasks New subtasks to replace the blocked portion
 * @property reasoning Explanation of why replanning was needed
 * @property blockReason What caused the original plan to be blocked
 * @property objectiveAlreadyAchieved True if the planner determined the overall objective is already met
 */
@Serializable
data class ReplanResult(
  /** The plan before replanning */
  val originalPlan: TaskPlan,
  /** New subtasks to replace the blocked portion */
  val newSubtasks: List<Subtask>,
  /** Explanation of why replanning was needed and the new approach */
  val reasoning: String,
  /** What caused the original plan to be blocked */
  val blockReason: String,
  /** True if the planner determined the overall objective is already achieved on the current screen */
  val objectiveAlreadyAchieved: Boolean = false,
)

/**
 * Result of a reflection analysis during blaze exploration.
 *
 * Reflection is a self-assessment mechanism that evaluates whether the agent
 * is making progress toward the objective. It can detect loops, stuck states,
 * and suggest course corrections.
 *
 * ## How Reflection Works
 *
 * The reflection node periodically examines:
 * 1. Recent action history for patterns (loops, repeated failures)
 * 2. Current screen state vs objective
 * 3. Progress indicators vs blockers
 *
 * Based on this analysis, it provides guidance for the agent to:
 * - Continue as planned
 * - Try an alternative approach
 * - Backtrack to an earlier state
 * - Mark the objective as blocked
 *
 * @property isOnTrack True if the agent appears to be making progress
 * @property progressAssessment Summary of progress toward the objective
 * @property suggestedCorrection If not on track, a suggested course correction
 * @property shouldBacktrack True if the agent should undo recent actions
 * @property backtrackSteps Number of steps to backtrack (if shouldBacktrack is true)
 * @property loopDetected True if the agent appears to be in a loop
 * @property stuckReason If stuck, explanation of why progress is blocked
 *
 * @see BlazeState for how reflection notes are tracked
 */
@Serializable
data class ReflectionResult(
  /** True if the agent appears to be making progress toward the objective */
  val isOnTrack: Boolean,
  /** Human-readable summary of progress toward the objective */
  val progressAssessment: String,
  /** Suggested correction if not on track (e.g., "Try scrolling down to find the button") */
  val suggestedCorrection: String? = null,
  /** True if the agent should undo recent actions and try a different path */
  val shouldBacktrack: Boolean = false,
  /** Number of steps to backtrack (0 if not backtracking) */
  val backtrackSteps: Int = 0,
  /** True if the agent appears to be stuck in a repeating pattern */
  val loopDetected: Boolean = false,
  /** If blocked, explanation of what is preventing progress */
  val stuckReason: String? = null,
) {
  /** True if the reflection suggests the exploration should stop */
  val shouldStop: Boolean
    get() = stuckReason != null && !shouldBacktrack

  /** True if any correction is needed (backtrack or try alternative) */
  val needsCorrection: Boolean
    get() = !isOnTrack || shouldBacktrack || loopDetected
}

// =============================================================================
// Cross-Application Memory Models (Phase 4: Mobile-Agent-v3 Integration)
// =============================================================================

/**
 * Working memory for cross-application workflows.
 *
 * Enables the agent to remember information discovered in one app and use it
 * in another app. This is critical for complex multi-app workflows like
 * comparing prices across apps, copying data between apps, or verifying
 * information across sources.
 *
 * Inspired by Mobile-Agent-v3's cross-app memory capability.
 *
 * ## Example: Flight Booking Workflow
 *
 * ```
 * 1. In Google Flights: Remember("flight_price", "$245")
 * 2. In Google Flights: Remember("airline", "JetBlue")
 * 3. Switch to JetBlue app
 * 4. Recall("flight_price") → verify price matches
 * ```
 *
 * @property facts Key-value store for extracted information
 * @property keyScreenshots Screenshots of important states for reference
 * @property clipboard Text copied for cross-app transfer
 *
 * @see MemoryOperation for operations that modify working memory
 */
@Serializable
data class WorkingMemory(
  /** Key-value store for extracted information (e.g., "flight_price" → "$245") */
  val facts: Map<String, String> = emptyMap(),

  /** Screenshots of important states for visual reference */
  val keyScreenshots: List<KeyScreenshot> = emptyList(),

  /** Text copied for cross-app transfer (simulated clipboard) */
  val clipboard: String? = null,
) {
  /** True if memory contains any stored information */
  val hasContent: Boolean
    get() = facts.isNotEmpty() || keyScreenshots.isNotEmpty() || clipboard != null

  /** Number of facts stored in memory */
  val factCount: Int
    get() = facts.size

  /**
   * Stores a fact in memory.
   *
   * @param key Identifier for the fact (e.g., "flight_price", "order_number")
   * @param value The value to store
   * @return New WorkingMemory with the fact stored
   */
  fun remember(key: String, value: String): WorkingMemory =
    copy(facts = facts + (key to value))

  /**
   * Retrieves a fact from memory.
   *
   * @param key The key to look up
   * @return The stored value, or null if not found
   */
  fun recall(key: String): String? = facts[key]

  /**
   * Stores a screenshot in memory.
   *
   * @param screenshot The screenshot to store
   * @return New WorkingMemory with the screenshot stored
   */
  fun addScreenshot(screenshot: KeyScreenshot): WorkingMemory =
    copy(keyScreenshots = keyScreenshots + screenshot)

  /**
   * Copies text to the simulated clipboard.
   *
   * @param text The text to copy
   * @return New WorkingMemory with the clipboard updated
   */
  fun copyToClipboard(text: String): WorkingMemory =
    copy(clipboard = text)

  /**
   * Clears all stored information.
   */
  fun clear(): WorkingMemory = WorkingMemory()

  companion object {
    /** Empty working memory */
    val EMPTY = WorkingMemory()

    /** Maximum screenshots to retain on device (memory constrained environments) */
    const val MAX_SCREENSHOTS_ON_DEVICE = 3

    /** Maximum screenshots for desktop/host mode with more available memory */
    const val MAX_SCREENSHOTS_HOST = 10
  }

  /**
   * Adds a screenshot with bounded storage using FIFO eviction.
   *
   * When the number of stored screenshots exceeds the specified limit,
   * oldest screenshots are evicted to maintain the memory constraint.
   * This is critical for on-device execution where memory is limited.
   *
   * @param screenshot The screenshot to add
   * @param limit Maximum number of screenshots to retain. Defaults to [MAX_SCREENSHOTS_HOST]
   * @return New WorkingMemory with the screenshot added and old ones evicted if necessary
   */
  fun addScreenshotWithLimit(
    screenshot: KeyScreenshot,
    limit: Int = MAX_SCREENSHOTS_HOST,
  ): WorkingMemory {
    val newScreenshots = (keyScreenshots + screenshot).takeLast(limit)
    return copy(keyScreenshots = newScreenshots)
  }
}

/**
 * A screenshot stored in working memory for visual reference.
 *
 * Key screenshots capture important states that the agent may need to
 * reference later, such as search results, price comparisons, or
 * confirmation screens.
 *
 * @property description Human-readable description of what the screenshot shows
 * @property extractedText Text extracted from the screenshot via OCR
 * @property timestamp When the screenshot was taken (epoch milliseconds)
 * @property screenshotData Base64-encoded screenshot data (optional, for verification)
 */
@Serializable
data class KeyScreenshot(
  /** Human-readable description of the screenshot content */
  val description: String,
  /** Text extracted from the screenshot via OCR (key → extracted value) */
  val extractedText: Map<String, String> = emptyMap(),
  /** Timestamp when the screenshot was captured (epoch milliseconds). Defaults to 0; callers should provide actual timestamp. */
  val timestamp: Long = 0L,
  /** Optional base64-encoded screenshot data for visual verification */
  val screenshotData: String? = null,
)

/**
 * Operations that can be performed on working memory.
 *
 * Memory operations allow the agent to store, retrieve, and transfer
 * information across app boundaries during complex workflows.
 *
 * ## Usage
 *
 * Memory operations are typically generated by the screen analyzer when
 * it detects information that should be remembered for later use, or
 * when the current task requires previously stored information.
 *
 * @see WorkingMemory for the storage mechanism
 * @see BlazeState.workingMemory for where memory is stored in state
 */
@Serializable
sealed interface MemoryOperation {

  /**
   * Store a fact in memory for later use.
   *
   * Use this when the agent discovers information that may be needed
   * later in the workflow, such as prices, account numbers, or dates.
   *
   * @property key Identifier for the fact (e.g., "flight_price", "order_number")
   * @property value The value to store
   * @property source Where the fact was extracted from (optional, for debugging)
   */
  @Serializable
  data class Remember(
    val key: String,
    val value: String,
    val source: String? = null,
  ) : MemoryOperation

  /**
   * Recall a previously stored fact.
   *
   * Use this when the current task requires information that was
   * stored earlier in the workflow.
   *
   * @property key The key to look up
   * @property required If true, failing to recall is an error; if false, returns null gracefully
   */
  @Serializable
  data class Recall(
    val key: String,
    val required: Boolean = true,
  ) : MemoryOperation

  /**
   * Save the current screen as a reference snapshot.
   *
   * Use this when the current screen contains important information
   * that should be preserved for later reference or verification.
   *
   * @property description What this screenshot captures
   * @property extractKeys Keys to extract from the screen via OCR
   */
  @Serializable
  data class Snapshot(
    val description: String,
    val extractKeys: List<String> = emptyList(),
  ) : MemoryOperation

  /**
   * Copy text to the simulated clipboard for cross-app transfer.
   *
   * Use this when text needs to be transferred to another app,
   * such as copying an address or confirmation number.
   *
   * @property text The text to copy
   */
  @Serializable
  data class CopyToClipboard(
    val text: String,
  ) : MemoryOperation

  /**
   * Paste text from the simulated clipboard.
   *
   * Use this to retrieve previously copied text for input in another app.
   */
  @Serializable
  data object PasteFromClipboard : MemoryOperation

  /**
   * Clear all stored information from memory.
   *
   * Use this when starting a new workflow or when memory should be reset.
   */
  @Serializable
  data object ClearMemory : MemoryOperation
}

/**
 * Result of a memory operation.
 *
 * @property success True if the operation completed successfully
 * @property value The retrieved value (for Recall/PasteFromClipboard operations)
 * @property error Error message if the operation failed
 */
@Serializable
data class MemoryOperationResult(
  val success: Boolean,
  val value: String? = null,
  val error: String? = null,
) {
  companion object {
    fun success(value: String? = null) = MemoryOperationResult(success = true, value = value)
    fun failure(error: String) = MemoryOperationResult(success = false, error = error)
  }
}
