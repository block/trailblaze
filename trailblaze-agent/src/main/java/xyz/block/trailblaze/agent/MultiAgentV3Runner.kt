package xyz.block.trailblaze.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.agent.trail.DeterministicTrailExecutor
import xyz.block.trailblaze.agent.trail.DefaultConditionChecker
import xyz.block.trailblaze.logs.client.LogEmitter
import xyz.block.trailblaze.logs.client.TrailblazeSessionManager
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
import xyz.block.trailblaze.yaml.PromptStep
import xyz.block.trailblaze.util.Console

/**
 * Executes a trail file step by step, preferring recordings (zero LLM cost) and falling
 * back to AI analysis when needed — the "blaze once, trail forever" replay path.
 *
 * Provides three entry points:
 *
 * - [trail] — run a list of steps in order (deterministic recordings or self-heal modes)
 * - [trailWithRecordings] — same, with per-step [EnhancedRecording] pre/post-condition
 *   validation and recovery strategies
 * - [validateTrail] — replay recordings purely to check which steps still pass
 *
 * All major execution events are surfaced to MCP clients via the optional [ProgressReporter].
 *
 * @see TrailConfig for execution-mode / fallback configuration
 */
class MultiAgentV3Runner private constructor(
  private val screenAnalyzer: ScreenAnalyzer?,
  private val executor: UiActionExecutor?,
  private val progressReporter: ProgressReporter?,
  private val deviceId: TrailblazeDeviceId?,
  private val recordingValidator: RecordingValidator? = null,
  private val availableToolsProvider: () -> List<TrailblazeToolDescriptor> = { emptyList() },
  private val logEmitter: LogEmitter? = null,
  // Optional waypoint id -> definition resolver, used by step postconditions in the
  // deterministic executor path. When null, postconditions declared on YAML steps are
  // silently skipped — existing trails behave identically.
  private val waypointResolver: ((String) -> xyz.block.trailblaze.api.waypoint.WaypointDefinition?)? = null,
) {

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
    sessionId: SessionId = TrailblazeSessionManager.generateSessionId("trail_with_recordings"),
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
   * - **Other modes**: Uses [TrailStepPlanner] with self-heal when needed
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
    sessionId: SessionId = TrailblazeSessionManager.generateSessionId("trail"),
    initialActionHistory: List<String> = emptyList(),
    /**
     * The overall test case title (e.g. an external test-case name) that encompasses all steps.
     *
     * Passed as [RecommendationContext.overallObjective] so the inner agent can reason
     * about each step in the context of the broader test goal and detect impossible
     * objectives early instead of exhausting all retry attempts.
     */
    caseTitle: String? = null,
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
      val result = executeTrail(steps, config, sessionId, initialActionHistory, caseTitle)

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

          // YAML-step postcondition: distinct from the EnhancedRecording.postconditions block
          // above. This evaluates `step.postcondition: { waypoint: <id> }` declared on the trail
          // YAML itself, against the live screen, using the same WaypointMatcher path the
          // deterministic executor uses. Hard-fails the trail at the declaring step on mismatch
          // so the failure surfaces where the goal actually drifted, not at a downstream tap
          // five steps later. Skipped silently when the runner wasn't constructed with a
          // waypointResolver (back-compat for callers that haven't been wired through yet).
          val stepPostcondition = step.postcondition
          val resolver = waypointResolver
          if (stepSuccess && stepPostcondition != null && resolver != null) {
            val assertion = xyz.block.trailblaze.waypoint.StepPostconditionAsserter.assert(
              postcondition = stepPostcondition,
              screenStateProvider = { executor.captureScreenState() },
              waypointResolver = resolver,
            )
            val pcFailure: String? = when (assertion) {
              is xyz.block.trailblaze.waypoint.StepPostconditionAsserter.Result.Matched -> null
              is xyz.block.trailblaze.waypoint.StepPostconditionAsserter.Result.NotMatched ->
                "Step $stepIndex: " +
                  xyz.block.trailblaze.waypoint.StepPostconditionAsserter.describeMismatch(assertion)
              is xyz.block.trailblaze.waypoint.StepPostconditionAsserter.Result.WaypointNotFound ->
                "Step $stepIndex: postcondition references unknown waypoint " +
                  "'${assertion.requestedId}'. Check the waypoint id against the loaded trailmaps."
              is xyz.block.trailblaze.waypoint.StepPostconditionAsserter.Result.NoScreenState ->
                "Step $stepIndex: postcondition '${assertion.definitionId}' could not be " +
                  "evaluated — the screen state provider returned no state within " +
                  "${assertion.timeoutMs}ms."
            }
            if (pcFailure != null) {
              state = state.copy(failed = true, failureReason = pcFailure)
              stepSuccess = false
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
    initialActionHistory: List<String> = emptyList(),
    caseTitle: String? = null,
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
        // Fast path: zero LLM calls, recordings only. Wire step postconditions into this
        // executor — the deterministic path is the one CI uses, so this is where waypoint
        // assertions need to fire if a YAML step declares them. Screen state comes from
        // the same executor that runs the recorded tools (one live source of truth,
        // matches whichever driver the trail is using). When the waypointResolver was not
        // wired by the caller (legacy entry points), postconditions silently no-op.
        val deterministicExecutor = DeterministicTrailExecutor(
          executor = executor,
          config = config,
          logEmitter = logEmitter,
          sessionId = sessionId,
          screenStateProvider = { executor.captureScreenState() },
          waypointResolver = waypointResolver,
        )
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

        val result = executePlannerBased(steps, config, sessionId, screenAnalyzer, executor, initialActionHistory, caseTitle)
        result.copy(targetDeviceId = deviceId)
      }
    }
  }

  /**
   * Executes trail using TrailStepPlanner for modes that support self-heal.
   */
  private suspend fun executePlannerBased(
    steps: List<PromptStep>,
    config: TrailConfig,
    sessionId: SessionId,
    screenAnalyzer: ScreenAnalyzer,
    executor: UiActionExecutor,
    initialActionHistory: List<String> = emptyList(),
    caseTitle: String? = null,
  ): TrailResult {
    val startTime = System.currentTimeMillis()
    val planner = TrailStepPlanner(steps, config, screenAnalyzer, executor, availableToolsProvider, initialActionHistory, caseTitle)
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

        // If this was a recording action and it failed, try the next action (self-heal)
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
     * Creates a MultiAgentV3Runner for trail execution.
     *
     * @param screenAnalyzer Screen analyzer for the AI-fallback trail modes
     * @param executor UI action executor
     * @param progressReporter Progress reporter for MCP clients (optional)
     * @param deviceId Device ID for parallel execution tracking (optional)
     * @param recordingValidator Recording validator for EnhancedRecording support (optional)
     * @return Configured MultiAgentV3Runner
     */
    fun create(
      screenAnalyzer: ScreenAnalyzer,
      executor: UiActionExecutor,
      progressReporter: ProgressReporter? = null,
      deviceId: TrailblazeDeviceId? = null,
      recordingValidator: RecordingValidator? = null,
      availableToolsProvider: () -> List<TrailblazeToolDescriptor> = { emptyList() },
      logEmitter: LogEmitter? = null,
      waypointResolver: ((String) -> xyz.block.trailblaze.api.waypoint.WaypointDefinition?)? = null,
    ): MultiAgentV3Runner {
      return MultiAgentV3Runner(
        screenAnalyzer = screenAnalyzer,
        executor = executor,
        progressReporter = progressReporter,
        deviceId = deviceId,
        recordingValidator = recordingValidator,
        availableToolsProvider = availableToolsProvider,
        logEmitter = logEmitter,
        waypointResolver = waypointResolver,
      )
    }
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
