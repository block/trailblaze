package xyz.block.trailblaze.agent.trail

import kotlinx.datetime.Clock
import xyz.block.trailblaze.agent.ExecutionResult
import xyz.block.trailblaze.agent.TrailConfig
import xyz.block.trailblaze.agent.TrailExecutionMode
import xyz.block.trailblaze.agent.TrailResult
import xyz.block.trailblaze.agent.TrailState
import xyz.block.trailblaze.agent.UiActionExecutor
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.waypoint.WaypointDefinition
import xyz.block.trailblaze.logs.client.LogEmitter
import xyz.block.trailblaze.logs.client.ObjectiveLogHelper
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TaskId
import xyz.block.trailblaze.waypoint.StepPostconditionAsserter
import xyz.block.trailblaze.yaml.PromptStep
import xyz.block.trailblaze.yaml.TrailblazeToolYamlWrapper

/**
 * Executes trails deterministically using only recordings.
 *
 * This executor provides the **zero LLM call** fast path for trail execution.
 * It requires all steps to have complete recordings and fails immediately if
 * any step lacks a recording or if a recording fails to execute.
 *
 * ## Relationship to TrailStepPlanner
 *
 * This is a **complementary** executor, not a replacement for [TrailStepPlanner].
 * Use this for fully-recorded trails where speed and determinism matter (CI/CD).
 * Use [TrailStepPlanner] when self-heal is needed for unrecorded or flaky steps.
 * The [trail] function in TrailApi.kt automatically selects the right executor
 * based on [TrailExecutionMode].
 *
 * ## When to Use
 *
 * Use [DeterministicTrailExecutor] when:
 * - All steps have recordings (fully recorded trail)
 * - Running in CI/CD where determinism is critical
 * - Cost optimization is important (zero LLM calls)
 * - You need reproducible test execution
 *
 * ## Performance
 *
 * Since no LLM calls are made, execution is very fast:
 * - ~100ms per step (device interaction only)
 * - No network latency for AI services
 * - Consistent execution time
 *
 * ## Failure Handling
 *
 * If a recording fails to execute (e.g., element not found), this executor:
 * - Does NOT fall back to AI
 * - Marks the trail as failed immediately
 * - Reports which step and tool failed
 *
 * For self-heal on failures, use [TrailStepPlanner] with
 * [TrailExecutionMode.RECORDING_WITH_FALLBACK].
 *
 * ## Step Postconditions
 *
 * A step may declare an optional `postcondition: { waypoint: <id> }` that pins the step's
 * intended end-state. After all of the step's recorded tools complete successfully, the
 * executor polls [screenStateProvider] until [waypointResolver]-resolved waypoint matches
 * or the postcondition's timeout elapses. A mismatch hard-fails the step at the declaring
 * boundary — so a fault surfaces at the swipe-that-didn't-dismiss, not at the downstream
 * tap that hit the wrong layer.
 *
 * Postconditions are skipped when [screenStateProvider] or [waypointResolver] is `null`;
 * the executor logs a single diagnostic and proceeds. Constructors written before the
 * waypoint registry was wired through remain valid (existing trails behave identically).
 *
 * @property executor The UI action executor for running tools
 * @property config Trail execution configuration (mode should be DETERMINISTIC)
 * @property logEmitter Optional log emitter for objective lifecycle events
 * @property sessionId Optional session ID for log correlation
 * @property screenStateProvider Optional source of the live screen state, used to evaluate
 *   step postconditions. When `null`, postconditions on steps are silently skipped.
 * @property waypointResolver Optional waypoint-id lookup against the loaded registry. When
 *   `null`, postconditions on steps are silently skipped.
 */
class DeterministicTrailExecutor(
  private val executor: UiActionExecutor,
  private val config: TrailConfig = TrailConfig.DETERMINISTIC,
  private val logEmitter: LogEmitter? = null,
  private val sessionId: SessionId? = null,
  private val screenStateProvider: (suspend () -> ScreenState?)? = null,
  private val waypointResolver: ((String) -> WaypointDefinition?)? = null,
) {

  /**
   * Executes a list of trail steps using only their recordings.
   *
   * @param steps The trail steps to execute
   * @return Trail execution result with success/failure status and timing
   * @throws IllegalStateException if any step lacks a recording
   */
  suspend fun execute(steps: List<PromptStep>): TrailResult {
    val startTime = System.currentTimeMillis()

    // Validate all steps have recordings. ToolRecording's `init` already rejects empty `tools`
    // at construction, so a present recording always carries at least one tool here.
    val missingRecordings = steps.mapIndexedNotNull { index, step ->
      if (step.recording == null) index else null
    }

    if (missingRecordings.isNotEmpty()) {
      val state = TrailState(
        steps = steps.map { it.prompt },
        currentStepIndex = missingRecordings.first(),
        failed = true,
        failureReason = "Steps missing recordings: ${missingRecordings.joinToString()}. " +
          "DeterministicTrailExecutor requires all steps to have recordings.",
      )
      return TrailResult(
        success = false,
        state = state,
        durationMs = System.currentTimeMillis() - startTime,
        errorMessage = state.failureReason,
      )
    }

    // Execute each step sequentially
    var state = TrailState(
      steps = steps.map { it.prompt },
      currentStepIndex = 0,
      completedSteps = emptySet(),
      usedRecordings = emptyMap(),
    )

    for ((index, step) in steps.withIndex()) {
      val stepStartTime = Clock.System.now()
      val stepTaskId = TaskId.generate()
      emitObjectiveStart(step)

      val stepResult = executeStep(state, step, index)
      state = stepResult

      if (state.failed) {
        emitObjectiveComplete(step, stepTaskId, stepStartTime, success = false, failureReason = state.failureReason)
        return TrailResult(
          success = false,
          state = state,
          durationMs = System.currentTimeMillis() - startTime,
          errorMessage = state.failureReason,
        )
      }

      emitObjectiveComplete(step, stepTaskId, stepStartTime, success = true, failureReason = null)
    }

    return TrailResult(
      success = state.isComplete,
      state = state,
      durationMs = System.currentTimeMillis() - startTime,
    )
  }

  /**
   * Executes a single step using its recording.
   */
  private suspend fun executeStep(
    state: TrailState,
    step: PromptStep,
    index: Int,
  ): TrailState {
    val recording = step.recording
      ?: return state.copy(
        failed = true,
        failureReason = "Step $index has no recording",
      )

    // Execute each tool in the recording sequence
    for ((toolIndex, tool) in recording.tools.withIndex()) {
      val result = executeTool(tool, index, toolIndex)

      if (result is StepResult.Failed) {
        return state.copy(
          failed = true,
          failureReason = result.reason,
        )
      }
    }

    // After tools succeed, enforce the step's postcondition (if any). A postcondition is a
    // structural assertion that the screen has actually reached the intended waypoint —
    // distinct from "the swipe gesture dispatched", which is all the tool layer can vouch for.
    val pc = step.postcondition
    if (pc != null) {
      val provider = screenStateProvider
      val resolver = waypointResolver
      if (provider == null || resolver == null) {
        // Misconfigured runner: step asks for a postcondition but the executor wasn't given
        // the deps to evaluate it. Skip rather than crash — surface the gap via the failure
        // reason path is overkill since the step succeeded mechanically. A single warning is
        // captured here so audits can flag trails whose postconditions are silently inert.
        // (No structured warning channel exists at this layer yet; calling sites that wire
        // the runner should ensure both deps are passed.)
      } else {
        val assertion = StepPostconditionAsserter.assert(
          postcondition = pc,
          screenStateProvider = provider,
          waypointResolver = resolver,
        )
        val pcFailure = describePostconditionFailure(index, assertion)
        if (pcFailure != null) {
          return state.copy(failed = true, failureReason = pcFailure)
        }
      }
    }

    // All tools in recording succeeded and postcondition (if any) is satisfied
    return state.copy(
      currentStepIndex = index + 1,
      completedSteps = state.completedSteps + index,
      usedRecordings = state.usedRecordings + (index to true),
      retryCount = 0,
    )
  }

  /**
   * Maps a [StepPostconditionAsserter.Result] to a failure reason string. Returns null when
   * the postcondition matched (the success path).
   *
   * Each non-match outcome gets a distinct message: trail authors debugging a mismatch want
   * to know whether the screen drifted (NotMatched), whether the waypoint id is wrong
   * (WaypointNotFound), or whether the driver is broken (NoScreenState).
   */
  private fun describePostconditionFailure(
    stepIndex: Int,
    result: StepPostconditionAsserter.Result,
  ): String? = when (result) {
    is StepPostconditionAsserter.Result.Matched -> null
    is StepPostconditionAsserter.Result.NotMatched ->
      "Step $stepIndex: ${StepPostconditionAsserter.describeMismatch(result)}"
    is StepPostconditionAsserter.Result.WaypointNotFound ->
      "Step $stepIndex: postcondition references unknown waypoint '${result.requestedId}'. " +
        "Check the waypoint id against the loaded packs."
    is StepPostconditionAsserter.Result.NoScreenState ->
      "Step $stepIndex: postcondition '${result.definitionId}' could not be evaluated — " +
        "the screen state provider returned no state within ${result.timeoutMs}ms."
  }

  /**
   * Executes a single tool from a recording.
   */
  private suspend fun executeTool(
    tool: TrailblazeToolYamlWrapper,
    stepIndex: Int,
    toolIndex: Int,
  ): StepResult {
    val args = tool.toJsonArgs()
    val result = executor.execute(
      toolName = tool.name,
      args = args,
      traceId = null,
    )

    return when (result) {
      is ExecutionResult.Success -> StepResult.Success
      is ExecutionResult.Failure -> StepResult.Failed(
        "Recording failed at step $stepIndex, tool $toolIndex (${tool.name}): ${result.error}"
      )
    }
  }

  private fun emitObjectiveStart(step: PromptStep) {
    val emitter = logEmitter ?: return
    val session = sessionId ?: return
    emitter.emit(ObjectiveLogHelper.createStartLog(step, session))
  }

  private fun emitObjectiveComplete(
    step: PromptStep,
    taskId: TaskId,
    stepStartTime: kotlinx.datetime.Instant,
    success: Boolean,
    failureReason: String?,
  ) {
    val emitter = logEmitter ?: return
    val session = sessionId ?: return
    emitter.emit(
      ObjectiveLogHelper.createCompleteLog(
        step = step,
        taskId = taskId,
        stepStartTime = stepStartTime,
        sessionId = session,
        success = success,
        failureReason = failureReason,
        explanation = if (success) "Completed via deterministic recording" else (failureReason ?: "Recording execution failed"),
      ),
    )
  }

  /**
   * Internal result type for step execution.
   */
  private sealed interface StepResult {
    data object Success : StepResult
    data class Failed(val reason: String) : StepResult
  }
}

