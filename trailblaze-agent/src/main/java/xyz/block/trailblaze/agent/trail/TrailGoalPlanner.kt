package xyz.block.trailblaze.agent.trail

import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.agent.ToolCallAnalysisResponse
import xyz.block.trailblaze.agent.ExecutionResult
import xyz.block.trailblaze.agent.RecommendationContext
import xyz.block.trailblaze.agent.ScreenAnalyzer
import xyz.block.trailblaze.agent.TrailConfig
import xyz.block.trailblaze.agent.TrailExecutionMode
import xyz.block.trailblaze.agent.TrailState
import xyz.block.trailblaze.agent.UiActionExecutor
import xyz.block.trailblaze.agent.blaze.detectActionCycleHint
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.logs.model.TraceId.Companion.TraceOrigin
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor
import xyz.block.trailblaze.yaml.PromptStep
import xyz.block.trailblaze.yaml.VerificationStep

/**
 * Action to execute for a trail step.
 *
 * Represents either a recording-based or AI-based action for a single step.
 * The [cost] influences which action is preferred when both are available.
 *
 * @property stepIndex Index of the step this action executes
 * @property type Whether this uses recording or AI
 * @property cost Relative cost (lower = preferred). Recordings cost 1.0, AI costs 5.0.
 */
data class TrailStepAction(
  val stepIndex: Int,
  val type: ActionType,
  val cost: Double,
) {
  enum class ActionType {
    RECORDING,
    SELF_HEAL,
  }
}

/**
 * Plans and executes trail files step by step.
 *
 * This planner converts a list of [PromptStep]s into an executable plan,
 * choosing between recordings and self-heal based on availability and
 * the [TrailConfig.mode].
 *
 * ## Execution Strategy
 *
 * For each step, the planner prefers recordings over AI (lower cost):
 * - **Recording**: Cost 1.0 - fast, deterministic, no LLM calls
 * - **self-heal**: Cost 5.0 - slower, uses screen analysis
 *
 * ## Execution Modes
 *
 * - [TrailExecutionMode.DETERMINISTIC]: Only recordings, fail if missing
 * - [TrailExecutionMode.RECORDING_WITH_FALLBACK]: Try recording, AI on failure
 * - [TrailExecutionMode.HYBRID]: AI for analysis, recordings for execution
 * - [TrailExecutionMode.AI_ONLY]: Always use AI, ignore recordings
 *
 * @property steps The trail steps to execute in order
 * @property config Execution configuration
 * @property screenAnalyzer Screen analyzer for AI execution (nullable for DETERMINISTIC)
 * @property executor UI action executor for running tools
 */
class TrailStepPlanner(
  private val steps: List<PromptStep>,
  private val config: TrailConfig,
  private val screenAnalyzer: ScreenAnalyzer?,
  private val executor: UiActionExecutor,
  private val availableToolsProvider: () -> List<TrailblazeToolDescriptor> = { emptyList() },
  private val initialActionHistory: List<String> = emptyList(),
  /**
   * The overall test case title (e.g. TestRail case name) that encompasses all steps.
   *
   * When non-null, this is passed as [RecommendationContext.overallObjective] so the inner
   * agent can reason about each step in the context of the broader test goal. This enables
   * early [objectiveAppearsImpossible] detection — for example, recognising that a step
   * requiring business banking cannot succeed on a personal account — instead of exhausting
   * all retry attempts before failing.
   */
  private val caseTitle: String? = null,
) {

  /** Cost for executing a recorded action (cheap, fast, deterministic) */
  private companion object {
    const val RECORDING_COST = 1.0
    const val SELF_HEAL_COST = 5.0
  }

  /**
   * Plans available actions for the current step.
   *
   * Returns actions sorted by cost (cheapest first). For most modes,
   * recording actions come before AI actions.
   *
   * @param state Current trail execution state
   * @return List of available actions for the current step, sorted by cost
   */
  fun planActionsForStep(state: TrailState): List<TrailStepAction> {
    if (state.isComplete || state.failed) return emptyList()

    val index = state.currentStepIndex
    if (index >= steps.size) return emptyList()

    val step = steps[index]
    val hasRecording = step.recording != null
    val actions = mutableListOf<TrailStepAction>()

    when (config.mode) {
      TrailExecutionMode.DETERMINISTIC -> {
        // Only recordings allowed
        if (hasRecording) {
          actions.add(TrailStepAction(index, TrailStepAction.ActionType.RECORDING, RECORDING_COST))
        }
        // If no recording, no actions available (will fail)
      }

      TrailExecutionMode.RECORDING_WITH_FALLBACK -> {
        // Try recording first, then AI
        if (hasRecording) {
          actions.add(TrailStepAction(index, TrailStepAction.ActionType.RECORDING, RECORDING_COST))
        }
        if (screenAnalyzer != null) {
          actions.add(TrailStepAction(index, TrailStepAction.ActionType.SELF_HEAL, SELF_HEAL_COST))
        }
      }

      TrailExecutionMode.HYBRID -> {
        // Recording for execution, but AI may be used for verification
        if (hasRecording) {
          actions.add(TrailStepAction(index, TrailStepAction.ActionType.RECORDING, RECORDING_COST))
        }
        if (screenAnalyzer != null) {
          actions.add(TrailStepAction(index, TrailStepAction.ActionType.SELF_HEAL, SELF_HEAL_COST))
        }
      }

      TrailExecutionMode.AI_ONLY -> {
        // Only AI actions
        if (screenAnalyzer != null) {
          actions.add(TrailStepAction(index, TrailStepAction.ActionType.SELF_HEAL, SELF_HEAL_COST))
        }
      }
    }

    return actions.sortedBy { it.cost }
  }

  /**
   * Executes a single action and returns the updated state.
   *
   * @param state Current trail state
   * @param action The action to execute
   * @return Updated trail state after execution
   */
  suspend fun executeAction(state: TrailState, action: TrailStepAction): TrailState {
    val step = steps.getOrNull(action.stepIndex)
      ?: return state.copy(
        failed = true,
        failureReason = "Invalid step index: ${action.stepIndex}",
      )

    return when (action.type) {
      TrailStepAction.ActionType.RECORDING -> executeRecording(state, step, action.stepIndex)
      TrailStepAction.ActionType.SELF_HEAL -> executeWithAi(state, step, action.stepIndex)
    }
  }

  /**
   * Executes a step using its recorded tool sequence.
   */
  private suspend fun executeRecording(
    state: TrailState,
    step: PromptStep,
    index: Int,
  ): TrailState {
    val recording = step.recording
      ?: return state.copy(
        failed = true,
        failureReason = "Step $index has no recording",
      )

    // Execute each tool in the recording
    for (tool in recording.tools) {
      val args = tool.toJsonArgs()
      val result = executor.execute(
        toolName = tool.name,
        args = args,
        traceId = null,
      )

      when (result) {
        is ExecutionResult.Failure -> {
          // Recording failed
          return state.copy(
            failed = true,
            failureReason = "Recording failed at step $index: ${result.error}",
          )
        }
        is ExecutionResult.Success -> {
          // Continue to next tool in recording
        }
      }
    }

    // All tools succeeded
    return state.copy(
      currentStepIndex = index + 1,
      completedSteps = state.completedSteps + index,
      usedRecordings = state.usedRecordings + (index to true),
      retryCount = 0,
    )
  }

  /**
   * Executes a step using AI-based screen analysis.
   *
   * Maintains an action history across attempts so the LLM can see what was
   * already tried and avoid repeating the same ineffective actions.
   */
  private suspend fun executeWithAi(
    state: TrailState,
    step: PromptStep,
    index: Int,
  ): TrailState {
    val analyzer = screenAnalyzer
      ?: return state.copy(
        failed = true,
        failureReason = "No screen analyzer available for AI execution at step $index",
      )

    var currentState = state
    var attempts = 0
    val maxAttempts = config.maxRetries + 1
    val actionHistory = initialActionHistory.toMutableList()

    while (attempts < maxAttempts) {
      attempts++

      // Capture current screen
      val screenState = executor.captureScreenState()
        ?: return currentState.copy(
          failed = true,
          failureReason = "Failed to capture screen state at step $index",
        )

      // Generate trace ID for this attempt — correlates LLM analysis with tool execution
      val traceId = TraceId.generate(TraceOrigin.LLM)

      // Analyze screen and get recommendation, including history of previous actions
      val analysis = analyzer.analyze(
        context = RecommendationContext(
          objective = step.prompt,
          overallObjective = caseTitle,
          progressSummary = buildProgressSummary(currentState, index, actionHistory),
          attemptNumber = attempts,
          isVerification = step is VerificationStep,
        ),
        screenState = screenState,
        traceId = traceId,
        availableTools = availableToolsProvider(),
      )

      // Check if objective already achieved
      if (analysis.objectiveAppearsAchieved) {
        return currentState.copy(
          currentStepIndex = index + 1,
          completedSteps = currentState.completedSteps + index,
          usedRecordings = currentState.usedRecordings + (index to false),
          retryCount = 0,
        )
      }

      // Check if impossible
      if (analysis.objectiveAppearsImpossible) {
        return currentState.copy(
          failed = true,
          failureReason = "Objective appears impossible at step $index: ${analysis.reasoning}",
        )
      }

      // Execute recommended action (same traceId as the LLM analysis that recommended it)
      val result = executor.execute(
        toolName = analysis.recommendedTool,
        args = analysis.recommendedArgs,
        traceId = traceId,
      )

      when (result) {
        is ExecutionResult.Success -> {
          actionHistory.add("${analysis.recommendedTool}(${analysis.recommendedArgs}) → SUCCESS")

          // Check if this completes the step (re-analyze with a new traceId for the verification call)
          val verifyScreen = executor.captureScreenState()
          if (verifyScreen != null) {
            val verifyAnalysis = analyzer.analyze(
              context = RecommendationContext(
                objective = step.prompt,
                overallObjective = caseTitle,
                progressSummary = buildProgressSummary(currentState, index, actionHistory),
                isVerification = step is VerificationStep,
              ),
              screenState = verifyScreen,
              traceId = TraceId.generate(TraceOrigin.LLM),
              availableTools = availableToolsProvider(),
            )

            if (verifyAnalysis.objectiveAppearsAchieved) {
              return currentState.copy(
                currentStepIndex = index + 1,
                completedSteps = currentState.completedSteps + index,
                usedRecordings = currentState.usedRecordings + (index to false),
                retryCount = 0,
              )
            }

            // If verification recommends the same tool on the same target, the action
            // was performed but the screen didn't visibly change. Treat as achieved to
            // avoid infinite loops on tap/assert steps that don't alter the UI.
            if (verifyAnalysis.recommendedTool == analysis.recommendedTool &&
              stripAnalysisFields(verifyAnalysis.recommendedArgs) ==
              stripAnalysisFields(analysis.recommendedArgs)
            ) {
              return currentState.copy(
                currentStepIndex = index + 1,
                completedSteps = currentState.completedSteps + index,
                usedRecordings = currentState.usedRecordings + (index to false),
                retryCount = 0,
              )
            }
          }

          // Action succeeded but objective not yet achieved - continue loop
          currentState = currentState.copy(retryCount = attempts)
        }

        is ExecutionResult.Failure -> {
          actionHistory.add("${analysis.recommendedTool}(${analysis.recommendedArgs}) → FAILED: ${result.error}")

          if (!result.recoverable || attempts >= maxAttempts) {
            return currentState.copy(
              failed = true,
              failureReason = "AI execution failed at step $index after $attempts attempts: ${result.error}",
            )
          }
          // Recoverable error - retry
          currentState = currentState.copy(retryCount = attempts)
        }
      }
    }

    // Max attempts reached without completing objective
    return currentState.copy(
      failed = true,
      failureReason = "Step $index did not complete after $maxAttempts attempts",
    )
  }

  private fun buildProgressSummary(
    state: TrailState,
    currentIndex: Int,
    actionHistory: List<String> = emptyList(),
  ): String = buildString {
    val completedCount = state.completedSteps.size
    val totalSteps = state.steps.size
    append("Completed $completedCount of $totalSteps steps. Now on step ${currentIndex + 1}.")
    if (actionHistory.isNotEmpty()) {
      appendLine()
      appendLine("Actions already attempted for this step:")
      for ((i, action) in actionHistory.withIndex()) {
        appendLine("  ${i + 1}. $action")
      }
      appendLine("Try a DIFFERENT approach if previous actions did not achieve the objective.")

      // Detect repeating action cycles (single-action repeats AND short A-B-A-B / A-B-C-A-B-C
      // loops) and surface a stronger nudge. The success/failure suffix is stripped so a tap
      // that succeeds in landing on the wrong screen still matches the same tap on a later
      // iteration. Without this, a 2-action ping-pong loop ("tap Items → tap Back → tap Items
      // → tap Back …") burns through the per-step retry budget without escalation.
      val signatures = actionHistory.map(::stripActionOutcomeSuffix)
      detectActionCycleHint(signatures)?.let { hint ->
        appendLine()
        appendLine(hint)
      }
    }
  }
}

/**
 * Strips the trailing " → SUCCESS" or " → FAILED[: <error>]" outcome suffix from a Trail
 * action history entry, leaving the underlying "tool(args)" signature so the cycle detector
 * can match the same action across success and failure outcomes.
 *
 * Anchored at the end of the string with [RegexOption.DOT_MATCHES_ALL], so a `→` that appears
 * inside a tool argument (e.g. a label like `"Menu → Settings"` or a breadcrumb in typed
 * text) is preserved instead of accidentally truncating distinct actions to the same prefix.
 *
 * Outcome shapes the planner emits today (see [TrailStepPlanner.executeWithAi]):
 * - `tool(args) → SUCCESS`
 * - `tool(args) → FAILED: <error message, possibly multi-line>`
 *
 * Entries that do not end with one of those outcomes (e.g. pre-formatted history passed in
 * from elsewhere, or a future planner that records actions before knowing the outcome) pass
 * through unchanged.
 */
internal fun stripActionOutcomeSuffix(entry: String): String =
  ACTION_OUTCOME_SUFFIX_REGEX.replace(entry, "")

private val ACTION_OUTCOME_SUFFIX_REGEX: Regex =
  Regex("""\s*→\s*(SUCCESS|FAILED(?::.*)?)\s*$""", RegexOption.DOT_MATCHES_ALL)

/**
 * Returns a [JsonObject] containing only tool-specific parameters, stripping out
 * analysis metadata (reasoning, screenSummary, confidence, etc.) so that two LLM
 * responses can be compared by their actionable content.
 */
private fun stripAnalysisFields(args: JsonObject): JsonObject {
  val analysisKeys = ToolCallAnalysisResponse.fieldNames
  return JsonObject(args.filterKeys { it !in analysisKeys })
}

/**
 * Creates a [TrailState] initialized for executing the given steps.
 *
 * @param steps The step prompts to execute
 * @return Initial trail state ready for execution
 */
fun initialTrailState(steps: List<PromptStep>): TrailState = TrailState(
  steps = steps.map { it.prompt },
  currentStepIndex = 0,
  completedSteps = emptySet(),
  failed = false,
  failureReason = null,
  usedRecordings = emptyMap(),
  retryCount = 0,
)

