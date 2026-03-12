package xyz.block.trailblaze.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.agent.blaze.BlazeGoalPlanner
import xyz.block.trailblaze.agent.blaze.PlannerLlmCall
import xyz.block.trailblaze.agent.blaze.createFullFeaturedBlazeGoalPlanner
import xyz.block.trailblaze.agent.blaze.initialBlazeState
import xyz.block.trailblaze.agent.trail.DeterministicTrailExecutor
import xyz.block.trailblaze.agent.trail.DefaultConditionChecker
import xyz.block.trailblaze.agent.trail.EnhancedRecording
import xyz.block.trailblaze.agent.trail.RecordingValidator
import xyz.block.trailblaze.agent.trail.RecoveryStrategy
import xyz.block.trailblaze.agent.trail.TrailStepPlanner
import xyz.block.trailblaze.agent.trail.ValidationResult
import xyz.block.trailblaze.agent.trail.createEnhancedRecording
import xyz.block.trailblaze.agent.trail.initialTrailState
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.logs.model.TraceId.Companion.TraceOrigin
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.toolcalls.CoreTools
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor
import xyz.block.trailblaze.yaml.DirectionStep
import xyz.block.trailblaze.yaml.PromptStep
import xyz.block.trailblaze.util.Console

/**
 * Multi-Agent V3 Runner - Orchestrates all Mobile-Agent-v3 inspired features.
 *
 * This runner integrates all 6 phases of the Mobile-Agent-v3 implementation:
 *
 * - **Phase 1**: Exception Handling & Recovery (popups, ads, errors)
 * - **Phase 2**: Reflection & Self-Correction (loop detection, backtracking)
 * - **Phase 3**: Task Decomposition (breaking objectives into subtasks)
 * - **Phase 4**: Cross-App Memory (facts, screenshots, clipboard)
 * - **Phase 5**: Trail Recording Enhancement (pre/post conditions, validation)
 * - **Phase 6**: MCP Progress Reporting (real-time events, execution status)
 *
 * ## Blaze Mode (Exploratory)
 *
 * The runner executes in blaze mode, discovering actions dynamically to achieve
 * an objective. It records actions for future trail generation ("blaze once, trail forever").
 *
 * ## Example Usage
 *
 * ```kotlin
 * val runner = MultiAgentV3Runner.create(
 *   screenAnalyzer = myScreenAnalyzer,
 *   executor = myUiExecutor,
 *   llmCall = { prompt -> myLlm.complete(prompt) },
 * )
 *
 * val result = runner.blaze(
 *   objective = "Order a large pepperoni pizza from Domino's",
 *   config = BlazeConfig.DEFAULT,
 * )
 *
 * if (result.success) {
 *   Console.log("Objective achieved in ${result.actionCount} actions")
 *   // Generate trail file from recorded actions
 *   val trail = result.recordedActions
 * }
 * ```
 *
 * @see BlazeGoalPlanner for blaze execution
 * @see BlazeConfig for configuration options
 */
class MultiAgentV3Runner private constructor(
  private val blazePlanner: BlazeGoalPlanner,
  private val screenAnalyzer: ScreenAnalyzer?,
  private val executor: UiActionExecutor?,
  private val progressReporter: ProgressReporter?,
  private val deviceId: TrailblazeDeviceId?,
  private val recordingValidator: RecordingValidator? = null,
  private val availableToolsProvider: () -> List<TrailblazeToolDescriptor> = { emptyList() },
) {

  /**
   * Executes a blaze exploration toward an objective.
   *
   * Blaze mode discovers actions dynamically by analyzing screens and
   * executing actions. All Phase 1-6 features are active:
   *
   * - Exception handling for popups, ads, errors
   * - Reflection for self-correction
   * - Task decomposition for complex objectives
   * - Cross-app memory for multi-app workflows
   * - Progress reporting for MCP clients
   *
   * @param objective The goal to achieve
   * @param config Configuration for exploration (uses planner's config if not specified)
   * @param sessionId Session ID for progress reporting
   * @return Blaze result with recorded actions
   */
  suspend fun blaze(
    objective: String,
    sessionId: SessionId = SessionId.generate(),
  ): BlazeResult {
    val startTime = System.currentTimeMillis()

    // Report execution started
    progressReporter?.onExecutionStarted(
      sessionId = sessionId,
      objective = objective,
      hasTaskPlan = true, // Task decomposition is enabled by default
      deviceId = deviceId,
    )

    val initialState = initialBlazeState(objective).copy(targetDeviceId = deviceId)

    return try {
      val finalState = blazePlanner.execute(initialState)

      val result = BlazeResult(
        success = finalState.achieved,
        state = finalState,
        durationMs = System.currentTimeMillis() - startTime,
        recordedActions = finalState.actionHistory,
        errorMessage = finalState.stuckReason,
        targetDeviceId = deviceId,
      )

      // Report execution completed
      progressReporter?.onExecutionCompleted(
        sessionId = sessionId,
        success = result.success,
        totalDurationMs = result.durationMs,
        totalActions = result.actionCount,
        errorMessage = result.errorMessage,
        deviceId = deviceId,
      )

      result
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      val result = BlazeResult(
        success = false,
        state = initialState.copy(stuck = true, stuckReason = e.message),
        durationMs = System.currentTimeMillis() - startTime,
        errorMessage = e.message,
        targetDeviceId = deviceId,
      )

      progressReporter?.onExecutionCompleted(
        sessionId = sessionId,
        success = false,
        totalDurationMs = result.durationMs,
        totalActions = 0,
        errorMessage = e.message,
        deviceId = deviceId,
      )

      result
    }
  }

  /**
   * Executes a trail file step by step with enhanced recording support.
   *
   * This variant accepts EnhancedRecording objects alongside steps for
   * pre/post condition validation and recovery strategy handling.
   *
   * @param steps The trail steps to execute in order
   * @param recordings Optional enhanced recordings for each step (parallel to steps list)
   * @param config Trail configuration (execution mode, fallback settings)
   * @param sessionId Session ID for progress reporting
   * @return Trail execution result with success/failure and per-step details
   */
  suspend fun trailWithRecordings(
    steps: List<PromptStep>,
    recordings: List<EnhancedRecording>? = null,
    config: TrailConfig = TrailConfig.DEFAULT,
    sessionId: SessionId = SessionId.generate(),
  ): TrailResult {
    val startTime = System.currentTimeMillis()

    // Report execution started
    progressReporter?.onExecutionStarted(
      sessionId = sessionId,
      objective = "Execute trail with ${steps.size} steps",
      hasTaskPlan = false,
      deviceId = deviceId,
    )

    return try {
      val result = executeTrailWithRecordings(steps, recordings, config, sessionId)

      // Report execution completed
      progressReporter?.onExecutionCompleted(
        sessionId = sessionId,
        success = result.success,
        totalDurationMs = result.durationMs,
        totalActions = result.state.completedSteps.size,
        errorMessage = result.errorMessage,
        deviceId = deviceId,
      )

      result
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      val result = TrailResult(
        success = false,
        state = initialTrailState(steps).copy(failed = true, failureReason = e.message),
        durationMs = System.currentTimeMillis() - startTime,
        errorMessage = e.message,
        targetDeviceId = deviceId,
      )

      progressReporter?.onExecutionCompleted(
        sessionId = sessionId,
        success = false,
        totalDurationMs = result.durationMs,
        totalActions = 0,
        errorMessage = e.message,
        deviceId = deviceId,
      )

      result
    }
  }

  /**
   * Executes a trail file step by step.
   *
   * Trail mode follows predefined steps, preferring recordings (zero LLM cost)
   * and falling back to AI analysis when needed. This implements the "blaze
   * once, trail forever" philosophy by replaying recorded action sequences.
   *
   * ## Execution Strategy
   *
   * The method automatically selects the optimal execution strategy based on
   * [config.mode]:
   *
   * - **DETERMINISTIC**: Uses [DeterministicTrailExecutor] for zero-LLM execution
   * - **Other modes**: Uses [TrailStepPlanner] with AI fallback when needed
   *
   * ## Progress Reporting
   *
   * All major events are reported via [progressReporter]:
   * - stepStarted: When a step execution begins
   * - stepCompleted: When a step execution completes (with recording usage info)
   * - executionStarted/Completed: For overall trail execution
   *
   * @param steps The trail steps to execute in order
   * @param config Trail configuration (execution mode, fallback settings)
   * @param sessionId Session ID for progress reporting
   * @return Trail execution result with success/failure and per-step details
   */
  suspend fun trail(
    steps: List<PromptStep>,
    config: TrailConfig = TrailConfig.DEFAULT,
    sessionId: SessionId = SessionId.generate(),
  ): TrailResult {
    val startTime = System.currentTimeMillis()

    // Report execution started
    progressReporter?.onExecutionStarted(
      sessionId = sessionId,
      objective = "Execute trail with ${steps.size} steps",
      hasTaskPlan = false, // Trail mode doesn't use task decomposition
      deviceId = deviceId,
    )

    return try {
      val result = executeTrail(steps, config, sessionId)

      // Report execution completed
      progressReporter?.onExecutionCompleted(
        sessionId = sessionId,
        success = result.success,
        totalDurationMs = result.durationMs,
        totalActions = result.state.completedSteps.size,
        errorMessage = result.errorMessage,
        deviceId = deviceId,
      )

      result
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      val result = TrailResult(
        success = false,
        state = initialTrailState(steps).copy(failed = true, failureReason = e.message),
        durationMs = System.currentTimeMillis() - startTime,
        errorMessage = e.message,
        targetDeviceId = deviceId,
      )

      progressReporter?.onExecutionCompleted(
        sessionId = sessionId,
        success = false,
        totalDurationMs = result.durationMs,
        totalActions = 0,
        errorMessage = e.message,
        deviceId = deviceId,
      )

      result
    }
  }

  /**
   * Internal method that performs trail execution with enhanced recording support.
   *
   * Integrates EnhancedRecording validation (pre/post conditions, recovery strategies)
   * into the trail execution flow.
   */
  private suspend fun executeTrailWithRecordings(
    steps: List<PromptStep>,
    recordings: List<EnhancedRecording>?,
    config: TrailConfig,
    sessionId: SessionId,
  ): TrailResult {
    // Check if we have the necessary components
    if (executor == null) {
      return TrailResult(
        success = false,
        state = initialTrailState(steps).copy(
          failed = true,
          failureReason = "No UI action executor available",
        ),
        durationMs = 0,
        errorMessage = "Trail execution requires a UI action executor",
        targetDeviceId = deviceId,
      )
    }

    if (screenAnalyzer == null) {
      return TrailResult(
        success = false,
        state = initialTrailState(steps).copy(
          failed = true,
          failureReason = "ScreenAnalyzer required for trail execution with recordings",
        ),
        durationMs = 0,
        errorMessage = "Screen analyzer required for trail execution with recordings",
        targetDeviceId = deviceId,
      )
    }

    val startTime = System.currentTimeMillis()
    var state = initialTrailState(steps)

    for ((stepIndex, step) in steps.withIndex()) {
      if (state.failed) break

      val recording = recordings?.getOrNull(stepIndex)

      // Report step started
      progressReporter?.onStepStarted(
        sessionId = sessionId,
        stepIndex = stepIndex,
        stepPrompt = step.prompt,
        totalSteps = steps.size,
        deviceId = deviceId,
      )

      val stepStartTime = System.currentTimeMillis()
      var stepSuccess = false
      var usedRecording = false
      var skipStep = false

      // If we have an enhanced recording, validate preconditions
      if (recording != null) {
        try {
          val preconditionChecks = mutableListOf<String>()
          val screenState = executor.captureScreenState()
          val conditionChecker = DefaultConditionChecker()

          if (screenState != null) {
            for (condition in recording.preconditions) {
              val conditionMet = conditionChecker.check(condition, screenState)
              if (!conditionMet) {
                preconditionChecks.add(condition.toString())
              }
            }
          }

          // If preconditions fail, attempt recovery
          if (preconditionChecks.isNotEmpty()) {
            var recovered = false
            for (strategy in recording.recoveryStrategies) {
              try {
                // Simple recovery attempt for common strategies
                when (strategy) {
                  is RecoveryStrategy.OnPopup -> {
                    val result = executor.execute(
                      toolName = CoreTools.NAVIGATE_BACK,
                      args = JsonObject(emptyMap()),
                      traceId = TraceId.generate(TraceOrigin.TOOL),
                    )
                    recovered = result is ExecutionResult.Success
                  }
                  is RecoveryStrategy.OnLoading -> {
                    delay(strategy.checkIntervalMs)
                    recovered = true
                  }
                  else -> {} // Other strategies handled by validator
                }
              } catch (recoveryException: Exception) {
                if (recoveryException is CancellationException) throw recoveryException
                // Continue to next recovery strategy
              }
              if (recovered) break
            }

            if (!recovered) {
              // Preconditions failed and recovery exhausted - mark for re-recording
              state = state.copy(
                failed = true,
                failureReason = "Preconditions not met for step $stepIndex: ${preconditionChecks.joinToString()}",
              )
              skipStep = true
            }
          }
        } catch (validationException: Exception) {
          if (validationException is CancellationException) throw validationException
          // Continue with step even if validation fails
        }
      }

      // Execute the step if not skipped
      if (!skipStep) {
        // Delegate to existing execution logic for compatibility
        val planner = TrailStepPlanner(steps, config, screenAnalyzer, executor, availableToolsProvider)
        val actions = planner.planActionsForStep(state)

        if (actions.isNotEmpty()) {
          for (action in actions) {
            val newState = planner.executeAction(state, action)
            usedRecording = action.type.name == "RECORDING"

            if (!newState.failed) {
              state = newState
              stepSuccess = true
              break
            }

            // If recording failed and fallback available, try next action
            if (action.type.name == "RECORDING" &&
              config.mode == TrailExecutionMode.RECORDING_WITH_FALLBACK
            ) {
              continue
            }

            state = newState
          }

          // Validate postconditions if step was successful and we have a recording
          if (stepSuccess && recording != null) {
            try {
              val screenState = executor.captureScreenState()
              val postconditionChecks = mutableListOf<String>()
              val conditionChecker = DefaultConditionChecker()

              if (screenState != null) {
                for (condition in recording.postconditions) {
                  val conditionMet = conditionChecker.check(condition, screenState)
                  if (!conditionMet) {
                    postconditionChecks.add(condition.toString())
                  }
                }
              }

              if (postconditionChecks.isNotEmpty()) {
                // Postconditions failed - mark step as needing re-recording
                state = state.copy(
                  failed = true,
                  failureReason = "Postconditions not met for step $stepIndex: ${postconditionChecks.joinToString()}",
                )
                stepSuccess = false
              }
            } catch (postConditionException: Exception) {
              if (postConditionException is CancellationException) throw postConditionException
              // Continue even if postcondition validation fails
            }
          }
        } else {
          state = state.copy(
            failed = true,
            failureReason = "No actions available for step $stepIndex",
          )
        }
      }

      // Report step completed
      progressReporter?.onStepCompleted(
        sessionId = sessionId,
        stepIndex = stepIndex,
        usedRecording = usedRecording && stepSuccess,
        durationMs = System.currentTimeMillis() - stepStartTime,
        success = stepSuccess,
        deviceId = deviceId,
      )
    }

    return TrailResult(
      success = state.isComplete,
      state = state,
      durationMs = System.currentTimeMillis() - startTime,
      errorMessage = state.failureReason,
      targetDeviceId = deviceId,
    )
  }

  /**
   * Internal method that performs the actual trail execution.
   *
   * Selects between deterministic (recordings-only) or planner-based execution
   * based on the configuration mode.
   */
  private suspend fun executeTrail(
    steps: List<PromptStep>,
    config: TrailConfig,
    sessionId: SessionId,
  ): TrailResult {
    // Check if we have the necessary components
    if (executor == null) {
      return TrailResult(
        success = false,
        state = initialTrailState(steps).copy(
          failed = true,
          failureReason = "No UI action executor available",
        ),
        durationMs = 0,
        errorMessage = "Trail execution requires a UI action executor",
        targetDeviceId = deviceId,
      )
    }

    return when (config.mode) {
      TrailExecutionMode.DETERMINISTIC -> {
        // Fast path: zero LLM calls, recordings only
        val deterministicExecutor = DeterministicTrailExecutor(executor, config)
        val result = deterministicExecutor.execute(steps)
        reportTrailSteps(sessionId, result)
        result.copy(targetDeviceId = deviceId)
      }

      else -> {
        // Use TrailStepPlanner for modes that may need AI
        if (screenAnalyzer == null) {
          return TrailResult(
            success = false,
            state = initialTrailState(steps).copy(
              failed = true,
              failureReason = "ScreenAnalyzer required for ${config.mode} mode",
            ),
            durationMs = 0,
            errorMessage = "Screen analyzer required for non-deterministic modes",
            targetDeviceId = deviceId,
          )
        }

        val result = executePlannerBased(steps, config, sessionId, screenAnalyzer, executor)
        result.copy(targetDeviceId = deviceId)
      }
    }
  }

  /**
   * Executes trail using TrailStepPlanner for modes that support AI fallback.
   */
  private suspend fun executePlannerBased(
    steps: List<PromptStep>,
    config: TrailConfig,
    sessionId: SessionId,
    screenAnalyzer: ScreenAnalyzer,
    executor: UiActionExecutor,
  ): TrailResult {
    val startTime = System.currentTimeMillis()
    val planner = TrailStepPlanner(steps, config, screenAnalyzer, executor, availableToolsProvider)
    var state = initialTrailState(steps)

    while (!state.isComplete && !state.failed) {
      val stepIndex = state.currentStepIndex
      val step = steps.getOrNull(stepIndex)

      if (step == null) {
        state = state.copy(
          failed = true,
          failureReason = "Invalid step index: $stepIndex",
        )
        break
      }

      // Report step started
      progressReporter?.onStepStarted(
        sessionId = sessionId,
        stepIndex = stepIndex,
        stepPrompt = step.prompt,
        totalSteps = steps.size,
        deviceId = deviceId,
      )

      val stepStartTime = System.currentTimeMillis()
      val actions = planner.planActionsForStep(state)

      if (actions.isEmpty()) {
        // No actions available for current step
        state = state.copy(
          failed = true,
          failureReason = "No actions available for step $stepIndex",
        )
        progressReporter?.onStepCompleted(
          sessionId = sessionId,
          stepIndex = stepIndex,
          usedRecording = false,
          durationMs = System.currentTimeMillis() - stepStartTime,
          success = false,
          deviceId = deviceId,
        )
        break
      }

      // Try actions in cost order (cheapest first)
      var actionSucceeded = false
      var usedRecording = false

      for (action in actions) {
        val newState = planner.executeAction(state, action)
        usedRecording = action.type.name == "RECORDING"

        if (!newState.failed) {
          state = newState
          actionSucceeded = true
          break
        }

        // If this was a recording action and it failed, try the next action (AI fallback)
        if (action.type.name == "RECORDING" &&
          config.mode == TrailExecutionMode.RECORDING_WITH_FALLBACK
        ) {
          // Reset failed state and try next action
          continue
        }

        // Action failed and no fallback available
        state = newState
      }

      if (!actionSucceeded && !state.failed) {
        state = state.copy(
          failed = true,
          failureReason = "All actions failed for step $stepIndex",
        )
      }

      // Report step completed
      progressReporter?.onStepCompleted(
        sessionId = sessionId,
        stepIndex = stepIndex,
        usedRecording = usedRecording && actionSucceeded,
        durationMs = System.currentTimeMillis() - stepStartTime,
        success = actionSucceeded,
        deviceId = deviceId,
      )
    }

    return TrailResult(
      success = state.isComplete,
      state = state,
      durationMs = System.currentTimeMillis() - startTime,
      errorMessage = state.failureReason,
    )
  }

  /**
   * Validates a trail by running it and checking all pre/post conditions.
   *
   * Executes a trail with enhanced recording validation enabled. Returns a
   * ValidationResult indicating which steps passed, failed, or need re-recording.
   *
   * This is useful for:
   * - Checking if pre-recorded trails are still valid
   * - Detecting steps that need re-recording due to app changes
   * - Measuring recording reliability across multiple runs
   *
   * @param recordings The enhanced recordings to validate
   * @return ValidationResult with per-step pass/fail and re-recording needs
   */
  suspend fun validateTrail(
    recordings: List<EnhancedRecording>,
  ): ValidationResult {
    // Check if we have the necessary components
    if (executor == null) {
      return ValidationResult(
        success = false,
        stepResults = emptyList(),
        durationMs = 0,
        errorMessage = "No UI action executor available",
      )
    }

    // Use the provided validator or create a default one
    val validator = recordingValidator ?: RecordingValidator(DefaultConditionChecker())

    return validator.validate(recordings, executor)
  }

  /**
   * Reports trail step completion via progress reporter.
   */
  private fun reportTrailSteps(
    sessionId: SessionId,
    result: TrailResult,
  ) {
    for ((index, usedRecording) in result.state.usedRecordings) {
      progressReporter?.onStepCompleted(
        sessionId = sessionId,
        stepIndex = index,
        usedRecording = usedRecording,
        durationMs = 0, // Timing not available from deterministic executor
        success = index in result.state.completedSteps,
        deviceId = deviceId,
      )
    }
  }

  companion object {
    /**
     * Creates a MultiAgentV3Runner with all features enabled.
     *
     * @param screenAnalyzer Screen analyzer for blaze mode
     * @param executor UI action executor
     * @param plannerLlmCall Tool-calling LLM function for planning (task decomposition/replanning)
     * @param config Blaze configuration (optional)
     * @param progressReporter Progress reporter for MCP clients (optional)
     * @param deviceId Device ID for parallel execution tracking (optional)
     * @param recordingValidator Recording validator for EnhancedRecording support (optional)
     * @return Configured MultiAgentV3Runner
     */
    fun create(
      screenAnalyzer: ScreenAnalyzer,
      executor: UiActionExecutor,
      plannerLlmCall: PlannerLlmCall,
      config: BlazeConfig = BlazeConfig.DEFAULT,
      progressReporter: ProgressReporter? = null,
      deviceId: TrailblazeDeviceId? = null,
      recordingValidator: RecordingValidator? = null,
      availableToolsProvider: () -> List<TrailblazeToolDescriptor> = { emptyList() },
    ): MultiAgentV3Runner {
      // Create the full-featured blaze planner with all Phase 1-6 capabilities
      val blazePlanner = createFullFeaturedBlazeGoalPlanner(
        config = config,
        screenAnalyzer = screenAnalyzer,
        executor = executor,
        plannerLlmCall = plannerLlmCall,
        ocrExtractor = null, // Can be added later
        availableToolsProvider = availableToolsProvider,
      )

      return MultiAgentV3Runner(
        blazePlanner = blazePlanner,
        screenAnalyzer = screenAnalyzer,
        executor = executor,
        progressReporter = progressReporter,
        deviceId = deviceId,
        recordingValidator = recordingValidator,
        availableToolsProvider = availableToolsProvider,
      )
    }

    /**
     * Creates a MultiAgentV3Runner with a custom BlazeGoalPlanner.
     *
     * Use this when you need custom configuration of the planner.
     *
     * @param blazePlanner Pre-configured blaze planner
     * @param screenAnalyzer Screen analyzer for trail mode execution (optional)
     * @param executor UI action executor for trail mode execution (optional)
     * @param progressReporter Progress reporter for MCP clients (optional)
     * @param deviceId Device ID for parallel execution tracking (optional)
     * @param recordingValidator Recording validator for EnhancedRecording support (optional)
     * @return Configured MultiAgentV3Runner
     */
    fun createWithPlanner(
      blazePlanner: BlazeGoalPlanner,
      screenAnalyzer: ScreenAnalyzer? = null,
      executor: UiActionExecutor? = null,
      progressReporter: ProgressReporter? = null,
      deviceId: TrailblazeDeviceId? = null,
      recordingValidator: RecordingValidator? = null,
      availableToolsProvider: () -> List<TrailblazeToolDescriptor> = { emptyList() },
    ): MultiAgentV3Runner = MultiAgentV3Runner(
      blazePlanner = blazePlanner,
      screenAnalyzer = screenAnalyzer,
      executor = executor,
      progressReporter = progressReporter,
      deviceId = deviceId,
      recordingValidator = recordingValidator,
      availableToolsProvider = availableToolsProvider,
    )
  }
}

/**
 * Interface for reporting progress events to MCP clients.
 *
 * Implementations should forward events to connected MCP clients
 * for real-time progress display.
 */
interface ProgressReporter {
  fun onExecutionStarted(
    sessionId: SessionId,
    objective: String,
    hasTaskPlan: Boolean,
    deviceId: TrailblazeDeviceId? = null,
  )

  fun onExecutionCompleted(
    sessionId: SessionId,
    success: Boolean,
    totalDurationMs: Long,
    totalActions: Int,
    errorMessage: String?,
    deviceId: TrailblazeDeviceId? = null,
  )

  fun onStepStarted(
    sessionId: SessionId,
    stepIndex: Int,
    stepPrompt: String,
    totalSteps: Int,
    deviceId: TrailblazeDeviceId? = null,
  )

  fun onStepCompleted(
    sessionId: SessionId,
    stepIndex: Int,
    usedRecording: Boolean,
    durationMs: Long,
    success: Boolean,
    deviceId: TrailblazeDeviceId? = null,
  )

  fun onSubtaskProgress(
    sessionId: SessionId,
    subtaskIndex: Int,
    subtaskName: String,
    totalSubtasks: Int,
    percentComplete: Int,
    deviceId: TrailblazeDeviceId? = null,
  )

  fun onExceptionHandled(
    sessionId: SessionId,
    exceptionType: String,
    recoveryAction: String,
    success: Boolean,
    deviceId: TrailblazeDeviceId? = null,
  )

  fun onReflectionTriggered(
    sessionId: SessionId,
    reason: String,
    assessment: String,
    suggestedAction: String?,
    isOnTrack: Boolean,
    deviceId: TrailblazeDeviceId? = null,
  )

  fun onFactStored(
    sessionId: SessionId,
    key: String,
    valuePreview: String,
    deviceId: TrailblazeDeviceId? = null,
  )
}

/**
 * Default progress reporter that converts events to TrailblazeProgressEvents.
 *
 * @param listener Listener to receive progress events
 */
class DefaultProgressReporter(
  private val listener: ProgressEventListener,
) : ProgressReporter {

  override fun onExecutionStarted(
    sessionId: SessionId,
    objective: String,
    hasTaskPlan: Boolean,
    deviceId: TrailblazeDeviceId?,
  ) {
    listener.onProgressEvent(
      TrailblazeProgressEvent.ExecutionStarted(
        timestamp = System.currentTimeMillis(),
        sessionId = sessionId,
        deviceId = deviceId,
        objective = objective,
        agentImplementation = AgentImplementation.MULTI_AGENT_V3,
        hasTaskPlan = hasTaskPlan,
      )
    )
  }

  override fun onExecutionCompleted(
    sessionId: SessionId,
    success: Boolean,
    totalDurationMs: Long,
    totalActions: Int,
    errorMessage: String?,
    deviceId: TrailblazeDeviceId?,
  ) {
    listener.onProgressEvent(
      TrailblazeProgressEvent.ExecutionCompleted(
        timestamp = System.currentTimeMillis(),
        sessionId = sessionId,
        deviceId = deviceId,
        success = success,
        totalDurationMs = totalDurationMs,
        totalActions = totalActions,
        errorMessage = errorMessage,
      )
    )
  }

  override fun onStepStarted(
    sessionId: SessionId,
    stepIndex: Int,
    stepPrompt: String,
    totalSteps: Int,
    deviceId: TrailblazeDeviceId?,
  ) {
    listener.onProgressEvent(
      TrailblazeProgressEvent.StepStarted(
        timestamp = System.currentTimeMillis(),
        sessionId = sessionId,
        deviceId = deviceId,
        stepIndex = stepIndex,
        stepPrompt = stepPrompt,
        totalSteps = totalSteps,
      )
    )
  }

  override fun onStepCompleted(
    sessionId: SessionId,
    stepIndex: Int,
    usedRecording: Boolean,
    durationMs: Long,
    success: Boolean,
    deviceId: TrailblazeDeviceId?,
  ) {
    listener.onProgressEvent(
      TrailblazeProgressEvent.StepCompleted(
        timestamp = System.currentTimeMillis(),
        sessionId = sessionId,
        deviceId = deviceId,
        stepIndex = stepIndex,
        usedRecording = usedRecording,
        durationMs = durationMs,
        success = success,
      )
    )
  }

  override fun onSubtaskProgress(
    sessionId: SessionId,
    subtaskIndex: Int,
    subtaskName: String,
    totalSubtasks: Int,
    percentComplete: Int,
    deviceId: TrailblazeDeviceId?,
  ) {
    listener.onProgressEvent(
      TrailblazeProgressEvent.SubtaskProgress(
        timestamp = System.currentTimeMillis(),
        sessionId = sessionId,
        deviceId = deviceId,
        subtaskIndex = subtaskIndex,
        subtaskName = subtaskName,
        totalSubtasks = totalSubtasks,
        percentComplete = percentComplete,
        actionsInSubtask = 0,
      )
    )
  }

  override fun onExceptionHandled(
    sessionId: SessionId,
    exceptionType: String,
    recoveryAction: String,
    success: Boolean,
    deviceId: TrailblazeDeviceId?,
  ) {
    listener.onProgressEvent(
      TrailblazeProgressEvent.ExceptionHandled(
        timestamp = System.currentTimeMillis(),
        sessionId = sessionId,
        deviceId = deviceId,
        exceptionType = exceptionType,
        recoveryAction = recoveryAction,
        success = success,
      )
    )
  }

  override fun onReflectionTriggered(
    sessionId: SessionId,
    reason: String,
    assessment: String,
    suggestedAction: String?,
    isOnTrack: Boolean,
    deviceId: TrailblazeDeviceId?,
  ) {
    listener.onProgressEvent(
      TrailblazeProgressEvent.ReflectionTriggered(
        timestamp = System.currentTimeMillis(),
        sessionId = sessionId,
        deviceId = deviceId,
        reason = reason,
        assessment = assessment,
        suggestedAction = suggestedAction,
        isOnTrack = isOnTrack,
      )
    )
  }

  override fun onFactStored(
    sessionId: SessionId,
    key: String,
    valuePreview: String,
    deviceId: TrailblazeDeviceId?,
  ) {
    listener.onProgressEvent(
      TrailblazeProgressEvent.FactStored(
        timestamp = System.currentTimeMillis(),
        sessionId = sessionId,
        deviceId = deviceId,
        key = key,
        valuePreview = valuePreview,
      )
    )
  }
}

/**
 * Converts blaze exploration results into a trail-compatible format.
 *
 * This extension enables the "blaze once, trail forever" philosophy by
 * converting successful blaze exploration sequences into reusable trail steps.
 * The resulting steps can be serialized to .trail.yaml files for deterministic
 * replay.
 *
 * ## Example Usage
 *
 * ```kotlin
 * val blazeResult = runner.blaze("Order a large pepperoni pizza from Domino's")
 * if (blazeResult.success) {
 *   val trailSteps = blazeResult.toTrailSteps()
 *   // Save trailSteps to .trail.yaml file
 * }
 * ```
 *
 * @return List of PromptStep objects that can be executed as a trail
 */
fun BlazeResult.toTrailSteps(): List<PromptStep> {
  // Group recorded actions into logical steps based on task boundaries
  // For now, create one step per recorded action as a simple conversion
  return recordedActions.map { action ->
    DirectionStep(
      step = action.reasoning,
      recordable = true,
      recording = null, // Recording would need to be attached separately
    )
  }
}
