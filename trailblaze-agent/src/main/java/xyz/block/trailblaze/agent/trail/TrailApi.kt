package xyz.block.trailblaze.agent.trail

import xyz.block.trailblaze.agent.ScreenAnalyzer
import xyz.block.trailblaze.agent.TrailConfig
import xyz.block.trailblaze.agent.TrailExecutionMode
import xyz.block.trailblaze.agent.TrailResult
import xyz.block.trailblaze.agent.UiActionExecutor
import xyz.block.trailblaze.yaml.DirectionStep
import xyz.block.trailblaze.yaml.PromptStep
import xyz.block.trailblaze.yaml.ToolRecording

/**
 * Executes a trail - a predefined sequence of steps from a .trail.yaml file.
 *
 * This is the main entry point for trail execution. It automatically selects
 * the optimal execution strategy based on [config]:
 *
 * - **DETERMINISTIC mode**: Uses [DeterministicTrailExecutor] for zero-LLM execution
 * - **Other modes**: Uses [TrailStepPlanner] with AI fallback when needed
 *
 * ## Example Usage
 *
 * ```kotlin
 * // Execute with zero LLM calls (all steps must have recordings)
 * val result = trail(
 *   steps = trailFile.steps,
 *   executor = maestroExecutor,
 *   config = TrailConfig.DETERMINISTIC,
 * )
 *
 * // Execute with AI fallback for missing/failed recordings
 * val result = trail(
 *   steps = trailFile.steps,
 *   executor = maestroExecutor,
 *   screenAnalyzer = screenAnalyzer,
 *   config = TrailConfig(mode = TrailExecutionMode.RECORDING_WITH_FALLBACK),
 * )
 * ```
 *
 * ## Execution Modes
 *
 * | Mode | LLM Calls | Behavior |
 * |------|-----------|----------|
 * | DETERMINISTIC | 0 | Recordings only, fail if missing |
 * | RECORDING_WITH_FALLBACK | On failure | Try recording, AI on failure |
 * | HYBRID | For analysis | AI verifies, recordings execute |
 * | AI_ONLY | Every step | Ignore recordings, use AI |
 *
 * @param steps The trail steps to execute in order
 * @param executor UI action executor for running tools on the device
 * @param screenAnalyzer Screen analyzer for AI fallback (required unless DETERMINISTIC)
 * @param config Trail execution configuration
 * @return Result containing success/failure status, final state, and timing
 */
suspend fun trail(
  steps: List<PromptStep>,
  executor: UiActionExecutor,
  screenAnalyzer: ScreenAnalyzer? = null,
  config: TrailConfig = TrailConfig.DEFAULT,
): TrailResult {
  // Validate configuration
  if (config.mode != TrailExecutionMode.DETERMINISTIC && screenAnalyzer == null) {
    return TrailResult(
      success = false,
      state = initialTrailState(steps).copy(
        failed = true,
        failureReason = "ScreenAnalyzer required for ${config.mode} mode. " +
          "Use TrailConfig.DETERMINISTIC or provide a screenAnalyzer.",
      ),
      durationMs = 0,
      errorMessage = "Configuration error: ScreenAnalyzer required for non-deterministic modes",
    )
  }

  // Choose execution strategy based on mode
  return when (config.mode) {
    TrailExecutionMode.DETERMINISTIC -> {
      // Fast path: zero LLM calls, recordings only
      val deterministicExecutor = DeterministicTrailExecutor(executor, config)
      deterministicExecutor.execute(steps)
    }

    else -> {
      // Use TrailStepPlanner for modes that may need AI
      executeWithPlanner(steps, executor, screenAnalyzer!!, config)
    }
  }
}

/**
 * Executes trail steps using the [TrailStepPlanner].
 *
 * This handles all non-deterministic modes by planning and executing
 * one step at a time, with AI fallback when recordings fail.
 */
private suspend fun executeWithPlanner(
  steps: List<PromptStep>,
  executor: UiActionExecutor,
  screenAnalyzer: ScreenAnalyzer,
  config: TrailConfig,
): TrailResult {
  val startTime = System.currentTimeMillis()
  val planner = TrailStepPlanner(steps, config, screenAnalyzer, executor)
  var state = initialTrailState(steps)

  while (!state.isComplete && !state.failed) {
    val actions = planner.planActionsForStep(state)

    if (actions.isEmpty()) {
      // No actions available for current step
      state = state.copy(
        failed = true,
        failureReason = "No actions available for step ${state.currentStepIndex}. " +
          "Step may be missing recording and AI is not enabled.",
      )
      break
    }

    // Try actions in cost order (cheapest first)
    var actionSucceeded = false
    for (action in actions) {
      val newState = planner.executeAction(state, action)

      if (!newState.failed) {
        state = newState
        actionSucceeded = true
        break
      }

      // If this was a recording action and it failed, try the next action (AI fallback)
      if (action.type == TrailStepAction.ActionType.RECORDING &&
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
        failureReason = "All actions failed for step ${state.currentStepIndex}",
      )
    }
  }

  return TrailResult(
    success = state.isComplete,
    state = state,
    durationMs = System.currentTimeMillis() - startTime,
    errorMessage = state.failureReason,
  )
}

/**
 * Executes a trail from prompt strings (convenience overload).
 *
 * Creates [PromptStep]s from simple string prompts. Useful for testing
 * or when you don't have a full trail file.
 *
 * Note: Steps created this way have no recordings, so [TrailExecutionMode.AI_ONLY]
 * or [TrailExecutionMode.RECORDING_WITH_FALLBACK] is recommended.
 *
 * @param prompts The step prompts as strings
 * @param executor UI action executor
 * @param screenAnalyzer Screen analyzer (required since no recordings)
 * @param config Trail configuration (default uses AI)
 * @return Trail execution result
 */
@JvmName("trailFromPrompts")
suspend fun trail(
  prompts: List<String>,
  executor: UiActionExecutor,
  screenAnalyzer: ScreenAnalyzer,
  config: TrailConfig = TrailConfig.AI_ONLY,
): TrailResult {
  val steps = prompts.map { prompt ->
    DirectionStep(
      step = prompt,
      recordable = true,
      recording = null,
    )
  }
  return trail(steps, executor, screenAnalyzer, config)
}
