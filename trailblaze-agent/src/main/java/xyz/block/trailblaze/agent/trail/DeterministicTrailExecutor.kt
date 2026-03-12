package xyz.block.trailblaze.agent.trail

import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.agent.ExecutionResult
import xyz.block.trailblaze.agent.TrailConfig
import xyz.block.trailblaze.agent.TrailExecutionMode
import xyz.block.trailblaze.agent.TrailResult
import xyz.block.trailblaze.agent.TrailState
import xyz.block.trailblaze.agent.UiActionExecutor
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
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
 * Use [TrailStepPlanner] when AI fallback is needed for unrecorded or flaky steps.
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
 * For AI fallback on failures, use [TrailStepPlanner] with
 * [TrailExecutionMode.RECORDING_WITH_FALLBACK].
 *
 * @property executor The UI action executor for running tools
 * @property config Trail execution configuration (mode should be DETERMINISTIC)
 */
class DeterministicTrailExecutor(
  private val executor: UiActionExecutor,
  private val config: TrailConfig = TrailConfig.DETERMINISTIC,
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

    // Validate all steps have recordings
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
      val stepResult = executeStep(state, step, index)
      state = stepResult

      if (state.failed) {
        return TrailResult(
          success = false,
          state = state,
          durationMs = System.currentTimeMillis() - startTime,
          errorMessage = state.failureReason,
        )
      }
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

    // All tools in recording succeeded
    return state.copy(
      currentStepIndex = index + 1,
      completedSteps = state.completedSteps + index,
      usedRecordings = state.usedRecordings + (index to true),
      retryCount = 0,
    )
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

  /**
   * Internal result type for step execution.
   */
  private sealed interface StepResult {
    data object Success : StepResult
    data class Failed(val reason: String) : StepResult
  }
}

/**
 * Extension to convert TrailblazeToolYamlWrapper to JsonObject args.
 *
 * Serializes the [TrailblazeTool] to JSON and parses it as a JsonObject
 * to extract the tool arguments.
 */
private fun TrailblazeToolYamlWrapper.toJsonArgs(): JsonObject {
  // Serialize tool to JSON string, then parse back as JsonObject
  val toolJson = TrailblazeJsonInstance.encodeToString(trailblazeTool)
  return TrailblazeJsonInstance.decodeFromString<JsonObject>(toolJson)
}
