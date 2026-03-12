package xyz.block.trailblaze.agent.trail

import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.agent.ExecutionResult
import xyz.block.trailblaze.agent.RecordedAction
import xyz.block.trailblaze.agent.UiActionExecutor
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.toolcalls.CoreTools

/**
 * Enhanced recording for V3 agent internal use.
 *
 * **IMPORTANT: This is an internal runtime data structure, NOT a modification
 * to the existing .trail.yaml file format.** The production trail YAML format
 * uses [xyz.block.trailblaze.yaml.ToolRecording] and must not be changed.
 *
 * This class provides additional runtime metadata for the V3 agent during
 * execution, enabling more robust playback with:
 * - **Preconditions**: Expected screen state before the step (runtime check)
 * - **Postconditions**: Expected state after completion (runtime verification)
 * - **Recovery Strategies**: How to handle exceptions (runtime behavior)
 *
 * ## Usage Context
 *
 * - Created at runtime when loading trail files for V3 agent execution
 * - Enriches the basic [ToolRecording] with execution-time intelligence
 * - Never serialized back to trail files (preserves existing format)
 *
 * ## Relationship to Trail Files
 *
 * ```
 * .trail.yaml (file)          EnhancedRecording (runtime)
 * ─────────────────           ──────────────────────────────
 * - step: "Open Settings"  →  Load & enhance at runtime
 *   recording:             →  actions: [RecordedAction]
 *     tools:               →  preconditions: [inferred]
 *       - launchApp: ...   →  recoveryStrategies: [default]
 * ```
 *
 * @see xyz.block.trailblaze.yaml.ToolRecording for the production recording format
 * @see RecordedAction for action data
 * @see RecordingValidator for validation
 */
@Serializable
data class EnhancedRecording(
  /** The step prompt that this recording implements */
  val stepPrompt: String,

  /** The recorded actions (tool calls) for this step */
  val actions: List<RecordedAction>,

  /** Conditions that must be true before executing this step */
  val preconditions: List<RecordingCondition> = emptyList(),

  /** Conditions to verify after executing this step */
  val postconditions: List<RecordingCondition> = emptyList(),

  /** Recovery strategies for handling exceptions during this step */
  val recoveryStrategies: List<RecoveryStrategy> = emptyList(),

  /** Metadata about when and how this recording was created */
  val metadata: RecordingMetadata = RecordingMetadata(),
) {
  /** True if this recording has both pre and post conditions */
  val hasConditions: Boolean
    get() = preconditions.isNotEmpty() || postconditions.isNotEmpty()

  /** True if this recording has recovery strategies defined */
  val hasRecoveryStrategies: Boolean
    get() = recoveryStrategies.isNotEmpty()
}

/**
 * A condition that can be checked against screen state.
 *
 * Conditions are used for both preconditions (what must be true before)
 * and postconditions (what must be true after).
 *
 * Kept intentionally simple — complex screen assertions (element visibility,
 * foreground app checks) are handled by the existing tool infrastructure
 * (e.g., [xyz.block.trailblaze.agent.ScreenAnalyzer]).
 */
@Serializable
sealed interface RecordingCondition {

  /**
   * Screen must contain specific text.
   *
   * @property text Text that must be visible on screen
   * @property caseSensitive Whether matching is case-sensitive
   */
  @Serializable
  data class ScreenContains(
    val text: String,
    val caseSensitive: Boolean = false,
  ) : RecordingCondition

  /**
   * Screen must NOT contain specific text.
   *
   * @property text Text that must NOT be visible on screen
   * @property caseSensitive Whether matching is case-sensitive
   */
  @Serializable
  data class ScreenNotContains(
    val text: String,
    val caseSensitive: Boolean = false,
  ) : RecordingCondition

  /**
   * Custom condition expressed as a prompt for LLM verification.
   *
   * Used for conditions that can't be expressed as simple text matching,
   * e.g., "user is logged in" or "settings page is showing dark mode enabled".
   *
   * @property prompt Natural language description of what to verify
   */
  @Serializable
  data class CustomCondition(
    val prompt: String,
  ) : RecordingCondition
}

/**
 * Recovery strategy for handling exceptions during step execution.
 *
 * Each strategy defines what to do when a specific type of exception occurs.
 */
@Serializable
sealed interface RecoveryStrategy {

  /**
   * Dismiss popup dialogs automatically.
   *
   * @property method How to dismiss (BACK_BUTTON, TAP_OUTSIDE, TAP_DISMISS)
   * @property maxAttempts Maximum attempts to dismiss
   */
  @Serializable
  data class OnPopup(
    val method: DismissMethod = DismissMethod.BACK_BUTTON,
    val maxAttempts: Int = 3,
  ) : RecoveryStrategy

  /**
   * Handle errors with a specific strategy.
   *
   * @property action What to do on error (RETRY, SKIP, FAIL)
   * @property maxRetries Maximum retry attempts (if RETRY)
   */
  @Serializable
  data class OnError(
    val action: ErrorAction = ErrorAction.RETRY_ONCE,
    val maxRetries: Int = 1,
  ) : RecoveryStrategy

  /**
   * Wait for loading states to complete.
   *
   * @property maxWaitMs Maximum time to wait in milliseconds
   * @property checkIntervalMs How often to re-check screen state
   */
  @Serializable
  data class OnLoading(
    val maxWaitMs: Long = 10_000L,
    val checkIntervalMs: Long = 500L,
  ) : RecoveryStrategy

  /**
   * Skip advertisements automatically.
   *
   * @property waitForSkip Seconds to wait for skip button to appear
   */
  @Serializable
  data class OnAdvertisement(
    val waitForSkip: Int = 5,
  ) : RecoveryStrategy

  /**
   * Handle unexpected screens by navigating back.
   *
   * @property maxBackPresses Maximum back button presses
   */
  @Serializable
  data class OnUnexpectedScreen(
    val maxBackPresses: Int = 3,
  ) : RecoveryStrategy
}

/**
 * Methods for dismissing popup dialogs.
 */
@Serializable
enum class DismissMethod {
  /** Press the back button */
  BACK_BUTTON,
  /** Tap outside the dialog */
  TAP_OUTSIDE,
  /** Tap the dismiss/cancel button */
  TAP_DISMISS,
  /** Tap the confirm/OK button */
  TAP_CONFIRM,
}

/**
 * Actions to take when an error occurs.
 */
@Serializable
enum class ErrorAction {
  /** Retry the step once */
  RETRY_ONCE,
  /** Retry the step multiple times */
  RETRY_MULTIPLE,
  /** Skip this step and continue */
  SKIP,
  /** Fail the entire trail */
  FAIL,
}

/**
 * Metadata about a recording.
 */
@Serializable
data class RecordingMetadata(
  /** When this recording was created (ISO 8601) */
  val createdAt: String? = null,
  /** How long the original execution took in milliseconds */
  val executionDurationMs: Long? = null,
  /** Average confidence of the recorded actions */
  val averageConfidence: Double? = null,
  /** Number of times this recording has been validated successfully */
  val validationCount: Int = 0,
  /** Number of times this recording has failed validation */
  val failureCount: Int = 0,
  /** App version when this recording was created */
  val appVersion: String? = null,
  /** Device model where this recording was created */
  val deviceModel: String? = null,
  /** Whether this recording was auto-generated from blaze exploration */
  val autoGenerated: Boolean = true,
)

/**
 * Result of validating a recording.
 */
@Serializable
data class ValidationResult(
  /** True if the recording executed successfully */
  val success: Boolean,
  /** Results for each step in the recording */
  val stepResults: List<StepValidationResult>,
  /** Total validation time in milliseconds */
  val durationMs: Long,
  /** Overall error message if validation failed */
  val errorMessage: String? = null,
) {
  /** Number of steps that passed validation */
  val passedSteps: Int
    get() = stepResults.count { it.passed }

  /** Number of steps that failed validation */
  val failedSteps: Int
    get() = stepResults.count { !it.passed }

  /** Steps that need re-recording */
  val stepsNeedingRerecording: List<Int>
    get() = stepResults.mapIndexedNotNull { index, result ->
      if (result.needsRerecording) index else null
    }
}

/**
 * Validation result for a single step.
 */
@Serializable
data class StepValidationResult(
  /** Step index in the trail */
  val stepIndex: Int,
  /** The step prompt */
  val stepPrompt: String,
  /** True if this step passed validation */
  val passed: Boolean,
  /** Which preconditions failed (if any) */
  val failedPreconditions: List<String> = emptyList(),
  /** Which postconditions failed (if any) */
  val failedPostconditions: List<String> = emptyList(),
  /** Whether recovery was needed and what type */
  val recoveryUsed: String? = null,
  /** True if this step should be re-recorded */
  val needsRerecording: Boolean = false,
  /** Reason for needing re-recording */
  val rerecordingReason: String? = null,
)

/**
 * Validates recorded trails by re-executing and checking conditions.
 *
 * The validator executes each step of a trail and verifies:
 * 1. Preconditions are met before each step
 * 2. Actions execute successfully
 * 3. Postconditions are satisfied after each step
 * 4. Recovery strategies work when exceptions occur
 *
 * Steps that fail validation are flagged for re-recording.
 *
 * @see EnhancedRecording for the recording format
 * @see ValidationResult for validation output
 */
class RecordingValidator(
  private val conditionChecker: ConditionChecker,
) {

  /**
   * Validates a list of enhanced recordings.
   *
   * @param recordings The recordings to validate
   * @param executor UI action executor for re-executing steps
   * @return Validation result with per-step details
   */
  suspend fun validate(
    recordings: List<EnhancedRecording>,
    executor: UiActionExecutor,
  ): ValidationResult {
    val startTime = System.currentTimeMillis()
    val stepResults = mutableListOf<StepValidationResult>()
    var overallSuccess = true

    for ((index, recording) in recordings.withIndex()) {
      val stepResult = validateStep(index, recording, executor)
      stepResults.add(stepResult)
      if (!stepResult.passed) {
        overallSuccess = false
      }
    }

    return ValidationResult(
      success = overallSuccess,
      stepResults = stepResults,
      durationMs = System.currentTimeMillis() - startTime,
      errorMessage = if (overallSuccess) null else "Validation failed for ${stepResults.count { !it.passed }} steps",
    )
  }

  /**
   * Validates a single recording step.
   */
  private suspend fun validateStep(
    index: Int,
    recording: EnhancedRecording,
    executor: UiActionExecutor,
  ): StepValidationResult {
    val failedPreconditions = mutableListOf<String>()
    val failedPostconditions = mutableListOf<String>()
    var recoveryUsed: String? = null
    var needsRerecording = false
    var rerecordingReason: String? = null

    // Check preconditions
    val screenState = executor.captureScreenState()
    if (screenState != null) {
      for (condition in recording.preconditions) {
        if (!conditionChecker.check(condition, screenState)) {
          failedPreconditions.add(condition.toString())
        }
      }
    }

    // If preconditions failed, mark for re-recording
    if (failedPreconditions.isNotEmpty()) {
      needsRerecording = true
      rerecordingReason = "Preconditions not met: ${failedPreconditions.joinToString()}"
    }

    // Execute the recorded actions
    var executionFailed = false
    for (action in recording.actions) {
      var result = executor.execute(
        toolName = action.toolName,
        args = action.toolArgs,
        traceId = null,
      )
      if (result is ExecutionResult.Failure) {
        executionFailed = true
        // Try recovery strategies
        for (strategy in recording.recoveryStrategies) {
          val recovered = attemptRecovery(strategy, executor)
          if (recovered) {
            recoveryUsed = strategy.toString()
            // Retry the failed action after recovery
            result = executor.execute(
              toolName = action.toolName,
              args = action.toolArgs,
              traceId = null,
            )
            executionFailed = result is ExecutionResult.Failure
            break
          }
        }
        // If still failed after all recovery attempts, stop executing subsequent actions
        if (executionFailed) break
      }
    }

    if (executionFailed) {
      needsRerecording = true
      rerecordingReason = "Execution failed and recovery strategies exhausted"
    }

    // Check postconditions
    val finalScreenState = executor.captureScreenState()
    if (finalScreenState != null) {
      for (condition in recording.postconditions) {
        if (!conditionChecker.check(condition, finalScreenState)) {
          failedPostconditions.add(condition.toString())
        }
      }
    }

    if (failedPostconditions.isNotEmpty()) {
      needsRerecording = true
      rerecordingReason = "Postconditions not met: ${failedPostconditions.joinToString()}"
    }

    val passed = failedPreconditions.isEmpty() &&
      failedPostconditions.isEmpty() &&
      !executionFailed

    return StepValidationResult(
      stepIndex = index,
      stepPrompt = recording.stepPrompt,
      passed = passed,
      failedPreconditions = failedPreconditions,
      failedPostconditions = failedPostconditions,
      recoveryUsed = recoveryUsed,
      needsRerecording = needsRerecording,
      rerecordingReason = rerecordingReason,
    )
  }

  /**
   * Attempts to recover using a recovery strategy.
   */
  private suspend fun attemptRecovery(
    strategy: RecoveryStrategy,
    executor: UiActionExecutor,
  ): Boolean {
    return when (strategy) {
      is RecoveryStrategy.OnPopup -> {
        // Try to dismiss popup
        val result = executor.execute(
          toolName = CoreTools.NAVIGATE_BACK,
          args = JsonObject(emptyMap()),
          traceId = null,
        )
        result is ExecutionResult.Success
      }
      is RecoveryStrategy.OnError -> {
        // Signal that the caller should retry the failed action
        // SKIP and FAIL strategies don't recover — they let the failure propagate
        strategy.action == ErrorAction.RETRY_ONCE || strategy.action == ErrorAction.RETRY_MULTIPLE
      }
      is RecoveryStrategy.OnLoading -> {
        // Wait for loading
        delay(strategy.maxWaitMs)
        true
      }
      is RecoveryStrategy.OnAdvertisement -> {
        // Wait for skip button then press back
        delay(strategy.waitForSkip * 1000L)
        val result = executor.execute(
          toolName = CoreTools.NAVIGATE_BACK,
          args = JsonObject(emptyMap()),
          traceId = null,
        )
        result is ExecutionResult.Success
      }
      is RecoveryStrategy.OnUnexpectedScreen -> {
        // Press back to navigate away
        repeat(strategy.maxBackPresses) {
          executor.execute(
            toolName = CoreTools.NAVIGATE_BACK,
            args = JsonObject(emptyMap()),
            traceId = null,
          )
        }
        true
      }
    }
  }
}

/**
 * Interface for checking recording conditions against screen state.
 */
interface ConditionChecker {
  /**
   * Checks if a condition is satisfied by the current screen state.
   *
   * @param condition The condition to check
   * @param screenState Current screen state
   * @return True if the condition is satisfied
   */
  suspend fun check(
    condition: RecordingCondition,
    screenState: ScreenState,
  ): Boolean
}

/**
 * Default condition checker using view hierarchy analysis.
 */
class DefaultConditionChecker : ConditionChecker {

  override suspend fun check(
    condition: RecordingCondition,
    screenState: ScreenState,
  ): Boolean {
    return when (condition) {
      is RecordingCondition.ScreenContains -> {
        val hierarchy = screenState.viewHierarchy.toString()
        if (condition.caseSensitive) {
          hierarchy.contains(condition.text)
        } else {
          hierarchy.contains(condition.text, ignoreCase = true)
        }
      }
      is RecordingCondition.ScreenNotContains -> {
        val hierarchy = screenState.viewHierarchy.toString()
        if (condition.caseSensitive) {
          !hierarchy.contains(condition.text)
        } else {
          !hierarchy.contains(condition.text, ignoreCase = true)
        }
      }
      is RecordingCondition.CustomCondition -> {
        // Custom conditions need LLM verification
        // Return true by default - caller should use LLM-based checker
        true
      }
    }
  }
}

/**
 * Creates an EnhancedRecording from a list of RecordedActions.
 *
 * This function extracts conditions from the recorded actions' screen summaries
 * and generates appropriate pre/post conditions and recovery strategies.
 *
 * This is the runtime counterpart to the recording optimization pipeline described
 * in Decision 034 (Recording Optimization Pipeline). The full pipeline adds selector
 * computation, slot extraction, and value generalization as post-processing steps.
 *
 * @param stepPrompt The step prompt this recording implements
 * @param actions The recorded actions from blaze exploration
 * @return EnhancedRecording with inferred conditions
 */
fun createEnhancedRecording(
  stepPrompt: String,
  actions: List<RecordedAction>,
): EnhancedRecording {
  // Infer preconditions from first action's screen summary
  val preconditions = mutableListOf<RecordingCondition>()
  actions.firstOrNull()?.screenSummaryBefore?.let { summary ->
    // Extract key terms from screen summary as preconditions
    if (summary.isNotBlank()) {
      preconditions.add(RecordingCondition.CustomCondition(summary))
    }
  }

  // Infer postconditions from last action's screen summary
  val postconditions = mutableListOf<RecordingCondition>()
  actions.lastOrNull()?.screenSummaryAfter?.let { summary ->
    if (summary.isNotBlank()) {
      postconditions.add(RecordingCondition.CustomCondition(summary))
    }
  }

  // Add default recovery strategies
  val recoveryStrategies = listOf(
    RecoveryStrategy.OnPopup(),
    RecoveryStrategy.OnLoading(),
    RecoveryStrategy.OnError(ErrorAction.RETRY_ONCE),
  )

  // Calculate metadata
  val totalDuration = actions.mapNotNull { it.durationMs }.sum()
  val avgConfidence = actions.mapNotNull { it.confidence?.ordinal?.toDouble() }
    .takeIf { it.isNotEmpty() }
    ?.average()

  return EnhancedRecording(
    stepPrompt = stepPrompt,
    actions = actions,
    preconditions = preconditions,
    postconditions = postconditions,
    recoveryStrategies = recoveryStrategies,
    metadata = RecordingMetadata(
      executionDurationMs = totalDuration,
      averageConfidence = avgConfidence,
      autoGenerated = true,
    ),
  )
}
