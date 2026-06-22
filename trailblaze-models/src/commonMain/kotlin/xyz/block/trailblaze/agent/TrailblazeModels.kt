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
