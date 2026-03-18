package xyz.block.trailblaze.agent.blaze

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.block.trailblaze.agent.BlazeConfig
import xyz.block.trailblaze.agent.BlazeState
import xyz.block.trailblaze.agent.Confidence
import xyz.block.trailblaze.agent.ExecutionResult
import xyz.block.trailblaze.agent.ExceptionalScreenState
import xyz.block.trailblaze.agent.RecommendationContext
import xyz.block.trailblaze.agent.RecordedAction
import xyz.block.trailblaze.agent.ReflectionResult
import xyz.block.trailblaze.agent.ScreenAnalysis
import xyz.block.trailblaze.agent.ScreenAnalyzer
import xyz.block.trailblaze.agent.TaskPlan
import xyz.block.trailblaze.agent.UiActionExecutor
import xyz.block.trailblaze.agent.WorkingMemory
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.logs.model.TraceId.Companion.TraceOrigin
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor
import xyz.block.trailblaze.util.Console

/**
 * Minimum consecutive identical actions (same tool + same args) before triggering
 * early stuck detection. This catches loops like repeated scrolling or clicking
 * before the full [BlazeConfig.maxActionsPerSubtask] budget is exhausted.
 *
 * Shared between [BlazeGoalPlanner] and [PlanningNode] for consistent behavior.
 */
internal const val REPETITIVE_ACTION_THRESHOLD = 3

/**
 * Goal planner for blaze exploration mode.
 *
 * Blaze mode discovers actions dynamically by analyzing the current screen state.
 * Unlike trail mode which follows predefined steps, blaze explores toward an objective
 * and records the discovered action sequence for future trail generation.
 *
 * ## How it works
 *
 * The planner runs an "analyze and execute" loop. Each iteration:
 * 1. Captures the current screen state
 * 2. Analyzes it with the ScreenAnalyzer to get a recommended action
 * 3. Executes the recommended action via UiActionExecutor
 * 4. Records the action for trail generation (if enabled)
 * 5. Updates state with new screen summary and iteration count
 *
 * ## Cost Model
 *
 * Action cost is based on the screen analyzer's confidence:
 * - HIGH confidence: 1.0 (cheap, execute immediately)
 * - MEDIUM confidence: 3.0 (moderate, may need retry)
 * - LOW confidence: 5.0 (expensive, consider alternatives)
 *
 * ## Goal Condition
 *
 * The goal "objective achieved" is satisfied when:
 * - [BlazeState.achieved] is true (analyzer reports objective complete)
 * - OR [BlazeState.stuck] is true (cannot make further progress)
 *
 * ## Example Usage
 *
 * ```kotlin
 * val planner = BlazeGoalPlanner(
 *   config = BlazeConfig.DEFAULT,
 *   screenAnalyzer = ScreenAnalyzerImpl(samplingSource, model),
 *   executor = MaestroUiActionExecutor(maestroAgent),
 * )
 *
 * // Run exploration
 * val initialState = BlazeState(objective = "Log in with email test@example.com")
 * val finalState = planner.execute(initialState)
 *
 * if (finalState.achieved) {
 *   Console.log("Objective achieved in ${finalState.actionCount} actions")
 * }
 * ```
 *
 * @param config Configuration for exploration (max iterations, confidence threshold, etc.)
 * @param screenAnalyzer Inner agent that analyzes screens and recommends actions
 * @param executor Executes UI actions on the connected device
 *
 * @see BlazeConfig for configuration options
 * @see BlazeState for state tracking
 * @see ScreenAnalyzer for screen analysis interface
 * @see UiActionExecutor for action execution interface
 */
class BlazeGoalPlanner(
  private val config: BlazeConfig,
  private val screenAnalyzer: ScreenAnalyzer,
  private val executor: UiActionExecutor,
  private val planningNode: PlanningNode? = null,
  private val memoryNode: MemoryNode? = null,
  private val availableToolsProvider: () -> List<TrailblazeToolDescriptor> = { emptyList() },
) {

  /** Handles exceptional screen states (popups, ads, errors, etc.) */
  private val exceptionalStateHandler = ExceptionalStateHandler(executor)

  /** Reflection node for self-assessment during exploration */
  private val reflectionNode = ReflectionNode()

  /** Recent screen analyses for reflection (kept in memory during execution) */
  private val recentAnalyses = CopyOnWriteArrayList<ScreenAnalysis>()

  /** Whether task decomposition is enabled and available */
  private val isDecompositionEnabled: Boolean
    get() = config.enableTaskDecomposition && planningNode != null

  /** Whether cross-app memory is enabled and available */
  private val isMemoryEnabled: Boolean
    get() = memoryNode != null

  /**
   * Executes blaze exploration toward the objective.
   *
   * Runs the analyze-and-execute loop until:
   * - The objective is achieved ([BlazeState.achieved])
   * - The exploration gets stuck ([BlazeState.stuck])
   * - Maximum iterations are reached
   *
   * When reflection is enabled, the loop periodically performs self-assessment
   * to detect loops, stuck states, and course corrections. Reflection triggers:
   * - When the agent loops (same action repeated 3+ times)
   * - When multiple low-confidence actions occur
   * - On periodic intervals (configurable)
   * - After consecutive failures
   *
   * When task decomposition is enabled (Phase 3: Mobile-Agent-v3), complex objectives
   * are broken into subtasks before execution. Each subtask has success criteria and
   * the planner tracks progress per-subtask for better reporting and replanning.
   *
   * @param initialState Initial state with the objective to achieve
   * @return Final state with recorded actions and outcome
   */
  suspend fun execute(initialState: BlazeState): BlazeState {
    var state = initialState
    recentAnalyses.clear() // Reset analyses for new execution

    // Phase 3: Task Decomposition - decompose complex objectives into subtasks
    if (isDecompositionEnabled && state.taskPlan == null) {
      state = decomposeObjective(state)
    }

    while (!state.isComplete && state.iteration < config.maxIterations) {
      // Check if reflection should trigger
      if (config.enableReflection && shouldReflect(state, config.reflectionInterval)) {
        val reflectionState = performReflection(state)
        if (reflectionState.stuck) {
          return reflectionState
        }
        state = reflectionState
      }

      // Phase 3: Lightweight state-only subtask pre-checks (no LLM call needed)
      if (state.hasTaskPlan) {
        val plan = state.taskPlan!!
        if (plan.isComplete) {
          return state.copy(
            achieved = true,
            reflectionNotes = state.reflectionNotes +
              "[Task Decomposition] All ${plan.subtasks.size} subtasks completed!",
          )
        }
        // State-only stuck detection (action count, loops, low-confidence streaks)
        if (isSubtaskStuckFromState(state)) {
          state = handleSubtaskStuckFromState(state)
          if (state.stuck) return state
          continue // Retry with replanned subtasks
        }
      }

      state = analyzeAndExecute(state)
    }

    // If we hit max iterations without achieving or getting stuck
    if (!state.isComplete) {
      return state.copy(
        stuck = true,
        stuckReason = "Maximum iterations (${config.maxIterations}) reached without achieving objective",
      )
    }

    return state
  }

  // ==========================================================================
  // Task Decomposition (Phase 3: Mobile-Agent-v3 Integration)
  // ==========================================================================

  /**
   * Decomposes the objective into subtasks using the PlanningNode.
   *
   * This is called at the start of execution when task decomposition is enabled.
   * The objective is analyzed and broken into 3-10 ordered subtasks, each with
   * clear success criteria.
   *
   * @param state Current state with objective to decompose
   * @return State with task plan populated, or unchanged if decomposition fails
   */
  private suspend fun decomposeObjective(state: BlazeState): BlazeState {
    val node = planningNode ?: return state

    // Generate trace ID for the planning step — correlates the LLM request with the tool result
    val traceId = TraceId.generate(TraceOrigin.LLM)

    // Capture initial screen for context-aware decomposition
    val screenState = executor.captureScreenState()
    val screenContext = screenState?.let {
      // Extract visible text elements from the view hierarchy to give the planner
      // context about what's currently on screen (e.g., "Settings is already open")
      val textElements = mutableListOf<String>()
      fun extractText(node: ViewHierarchyTreeNode) {
        node.text?.takeIf { it.isNotBlank() }?.let { textElements.add(it) }
        node.children.forEach { extractText(it) }
      }
      extractText(it.viewHierarchy)
      if (textElements.isNotEmpty()) {
        "The app is already open. Visible text on screen: ${textElements.take(20).joinToString(", ")}"
      } else {
        "App screen captured - ready for analysis"
      }
    }

    return try {
      val result = node.decompose(
        objective = state.objective,
        screenContext = screenContext,
        traceId = traceId,
        screenshotBytes = screenState?.screenshotBytes,
      )

      state.copy(
        taskPlan = result.plan,
        reflectionNotes = state.reflectionNotes +
          "[Task Decomposition] ${result.reasoning} (${result.plan.subtasks.size} subtasks, confidence: ${result.confidence})",
        initialScreenSummary = screenContext,
      )
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // Decomposition failed - continue without task plan
      state.copy(
        reflectionNotes = state.reflectionNotes +
          "[Task Decomposition] Failed to decompose: ${e.message}. Proceeding without subtasks.",
      )
    }
  }

  /**
   * Checks if the current subtask appears stuck using only state history.
   *
   * This is a lightweight check that doesn't require an LLM call, used as a
   * pre-check before the single Screen Analyzer call per iteration.
   * Analysis-dependent checks (like objectiveAppearsImpossible) are handled
   * in [analyzeAndExecute] where the analysis is already available.
   */
  private fun isSubtaskStuckFromState(state: BlazeState): Boolean {
    // Check action count threshold
    if (state.currentSubtaskActions >= config.maxActionsPerSubtask) {
      return true
    }

    // No actions taken yet for this subtask (e.g., just replanned or just advanced).
    // Can't be stuck if we haven't tried anything yet — the loop/confidence checks
    // below look at global actionHistory which may contain stale data from a
    // previous subtask or pre-replan actions.
    if (state.currentSubtaskActions == 0) {
      return false
    }

    // Scope all history checks to the current subtask's actions only.
    // actionHistory is global across subtasks, so without scoping we'd compare
    // actions from a previous subtask against the current one — causing false
    // stuck detection on subtask boundaries (e.g., subtask A ends with 2 scrolls,
    // subtask B starts with 1 scroll → falsely looks like 3 consecutive scrolls).
    val subtaskActions = state.actionHistory.takeLast(state.currentSubtaskActions)

    // Check for consecutive identical actions (same tool + same args).
    // This is tool-agnostic: catches any repeated action pattern regardless
    // of what tools are available (works for dynamically provided tools too).
    // 3 consecutive identical calls is a strong signal that the action isn't
    // making progress — trigger replanning immediately instead of waiting
    // for the maxActionsPerSubtask budget to be exhausted.
    if (subtaskActions.size >= REPETITIVE_ACTION_THRESHOLD) {
      val recentActions = subtaskActions.takeLast(REPETITIVE_ACTION_THRESHOLD)
      val allSameTool = recentActions.map { it.toolName }.toSet().size == 1
      val allSameArgs = recentActions.map { it.toolArgs.toString() }.toSet().size == 1
      if (allSameTool && allSameArgs) {
        return true
      }
    }

    // Check for repeated actions (potential loop) — less strict check.
    // Catches cases where the same tool type is used with slightly different args
    // (e.g., clicking nearby coordinates, scrolling with minor variations).
    if (subtaskActions.size >= 5) {
      val recentActions = subtaskActions.takeLast(5)
      val uniqueTools = recentActions.map { it.toolName }.distinct()
      if (uniqueTools.size == 1) {
        return true
      }
    }

    // Check for consecutive low-confidence actions (also scoped to subtask)
    val lowConfidenceStreak = subtaskActions.takeLastWhile {
      it.confidence == Confidence.LOW
    }.size
    if (lowConfidenceStreak >= 3) {
      return true
    }

    return false
  }

  /**
   * Handles a stuck subtask detected from state-only checks.
   *
   * Unlike [handleSubtaskStuck] which requires a ScreenAnalysis, this method
   * works with state-only information to trigger replanning when the subtask
   * has exceeded action limits or is looping.
   */
  private suspend fun handleSubtaskStuckFromState(state: BlazeState): BlazeState {
    val node = planningNode ?: return state.copy(
      stuck = true,
      stuckReason = "Subtask stuck: exceeded action limits (no planner available for replanning)",
    )
    val plan = state.taskPlan ?: return state

    if (plan.replanCount >= config.maxReplanAttempts) {
      return state.copy(
        stuck = true,
        stuckReason = "Subtask '${plan.currentSubtask?.description}' blocked after " +
          "${config.maxReplanAttempts} replan attempts",
      )
    }

    val blockReason = when {
      state.currentSubtaskActions >= config.maxActionsPerSubtask ->
        "Subtask took ${state.currentSubtaskActions} actions without completing (max: ${config.maxActionsPerSubtask})"
      else ->
        "Subtask appears stuck: repeated actions or low-confidence streak"
    }

    // Generate trace ID for the replan LLM call
    val replanTraceId = TraceId.generate(TraceOrigin.LLM)

    // Capture a fresh screenshot for visual context during replanning
    val screenshotBytes = executor.captureScreenState()?.screenshotBytes

    // Use the last real screen analysis if available — it has the full LLM-generated
    // screen description, progress indicators, and blockers. Only fall back to a
    // synthetic analysis if no recent analyses exist.
    val lastAnalysis = recentAnalyses.lastOrNull()
    val screenForReplan = lastAnalysis ?: ScreenAnalysis(
      screenSummary = state.screenSummary ?: "Screen state unavailable",
      recommendedTool = "wait",
      recommendedArgs = JsonObject(emptyMap()),
      reasoning = blockReason,
      confidence = Confidence.LOW,
    )

    return try {
      val replanResult = node.replan(
        state = state,
        blockReason = blockReason,
        currentScreen = screenForReplan,
        traceId = replanTraceId,
        screenshotBytes = screenshotBytes,
      )

      // If the planner recognized the overall objective is already achieved
      // (e.g. a side-effect of a previous action already navigated to the destination),
      // skip remaining subtasks and declare success.
      if (replanResult.objectiveAlreadyAchieved) {
        return state.copy(
          achieved = true,
          screenSummary = screenForReplan.screenSummary,
          reflectionNotes = state.reflectionNotes +
            "[Overall objective already achieved] ${replanResult.reasoning}",
        )
      }

      if (replanResult.newSubtasks.isEmpty()) {
        return state.copy(
          stuck = true,
          stuckReason = "Replanning failed: ${replanResult.reasoning}",
        )
      }

      val updatedPlan = plan.replan(replanResult.newSubtasks)
      state.copy(
        taskPlan = updatedPlan,
        currentSubtaskActions = 0,
        reflectionNotes = state.reflectionNotes +
          "[Replan #${updatedPlan.replanCount}] $blockReason → ${replanResult.reasoning}",
      )
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      state.copy(
        stuck = true,
        stuckReason = "Replanning failed with error: ${e.message}",
      )
    }
  }

  /**
   * Advances to the next subtask after current one completes.
   *
   * Updates the task plan to mark the current subtask as completed and
   * advances the index to the next subtask. Resets per-subtask counters.
   *
   * @param state Current state with subtask to advance
   * @param analysis Screen analysis showing subtask completion
   * @return Updated state at next subtask
   */
  private fun advanceToNextSubtask(state: BlazeState, analysis: ScreenAnalysis): BlazeState {
    val plan = state.taskPlan ?: return state
    val completedSubtask = plan.currentSubtask

    val updatedPlan = plan.advanceToNextSubtask()
    val note = "[Subtask ${plan.currentSubtaskIndex + 1}/${plan.subtasks.size}] " +
      "Completed: ${completedSubtask?.description} (${state.currentSubtaskActions} actions)"

    // Check if this was the last subtask
    if (updatedPlan.isComplete) {
      return state.copy(
        taskPlan = updatedPlan,
        currentSubtaskActions = 0,
        achieved = true,
        reflectionNotes = state.reflectionNotes + note +
          " → All subtasks completed!",
      )
    }

    return state.copy(
      taskPlan = updatedPlan,
      currentSubtaskActions = 0,
      reflectionNotes = state.reflectionNotes + note +
        " → Next: ${updatedPlan.currentSubtask?.description}",
    )
  }

  /**
   * Handles a stuck subtask by triggering replanning.
   *
   * When a subtask cannot be completed (too many actions, looping, etc.),
   * the PlanningNode generates alternative subtasks to work around the blocker.
   *
   * @param state Current state with stuck subtask
   * @param analysis Screen analysis showing stuck state
   * @return Updated state with replanned subtasks, or stuck if replanning fails
   */
  private suspend fun handleSubtaskStuck(
    state: BlazeState,
    analysis: ScreenAnalysis,
    screenshotBytes: ByteArray? = null,
  ): BlazeState {
    val node = planningNode ?: return state
    val plan = state.taskPlan ?: return state

    // Check if we've exceeded replan attempts
    if (plan.replanCount >= config.maxReplanAttempts) {
      return state.copy(
        stuck = true,
        stuckReason = "Subtask '${plan.currentSubtask?.description}' blocked after " +
          "${config.maxReplanAttempts} replan attempts",
      )
    }

    // Determine block reason
    val blockReason = when {
      state.currentSubtaskActions >= config.maxActionsPerSubtask ->
        "Subtask took ${state.currentSubtaskActions} actions without completing (max: ${config.maxActionsPerSubtask})"
      analysis.objectiveAppearsImpossible ->
        "Screen analysis indicates subtask is impossible: ${analysis.screenSummary}"
      else ->
        "Subtask appears stuck: ${analysis.potentialBlockers.joinToString()}"
    }

    // Generate trace ID for the replan LLM call
    val replanTraceId = TraceId.generate(TraceOrigin.LLM)

    return try {
      val replanResult = node.replan(
        state = state,
        blockReason = blockReason,
        currentScreen = analysis,
        traceId = replanTraceId,
        screenshotBytes = screenshotBytes,
      )

      // If the planner recognized the overall objective is already achieved
      if (replanResult.objectiveAlreadyAchieved) {
        return state.copy(
          achieved = true,
          screenSummary = analysis.screenSummary,
          reflectionNotes = state.reflectionNotes +
            "[Overall objective already achieved] ${replanResult.reasoning}",
        )
      }

      if (replanResult.newSubtasks.isEmpty()) {
        // Replanning produced no alternatives - mark as stuck
        return state.copy(
          stuck = true,
          stuckReason = "Replanning failed: ${replanResult.reasoning}",
        )
      }

      // Apply the new subtasks
      val updatedPlan = plan.replan(replanResult.newSubtasks)
      state.copy(
        taskPlan = updatedPlan,
        currentSubtaskActions = 0,
        reflectionNotes = state.reflectionNotes +
          "[Replan #${updatedPlan.replanCount}] $blockReason → ${replanResult.reasoning}",
      )
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      state.copy(
        stuck = true,
        stuckReason = "Replanning failed with error: ${e.message}",
      )
    }
  }

  /**
   * Performs reflection on the current exploration state.
   *
   * Uses the [ReflectionNode] to analyze recent actions and screens,
   * detecting loops, stuck states, and suggesting course corrections.
   *
   * @param state Current blaze exploration state
   * @return Updated state with reflection results applied
   */
  private fun performReflection(state: BlazeState): BlazeState {
    val reflection = reflectionNode.reflect(state, recentAnalyses.toList())

    // Record reflection in notes
    val reflectionNote = buildReflectionNote(reflection)
    val updatedNotes = state.reflectionNotes + reflectionNote

    // Handle reflection results
    return when {
      // If reflection says we should stop (unrecoverable stuck state)
      reflection.shouldStop -> state.copy(
        stuck = true,
        stuckReason = reflection.stuckReason ?: "Reflection determined exploration cannot continue",
        reflectionNotes = updatedNotes,
      )

      // If reflection suggests backtracking
      reflection.shouldBacktrack -> {
        val backtrackSteps = minOf(reflection.backtrackSteps, config.maxBacktrackSteps)
        applyBacktrack(state, backtrackSteps, reflection, updatedNotes)
      }

      // If we need correction but not backtracking
      reflection.needsCorrection -> state.copy(
        reflectionNotes = updatedNotes + "Correction suggested: ${reflection.suggestedCorrection}",
      )

      // On track - just record the assessment
      else -> state.copy(
        reflectionNotes = updatedNotes,
      )
    }
  }

  /**
   * Applies backtracking by removing recent actions from history.
   *
   * Backtracking allows the agent to "undo" recent actions when reflection
   * determines they were heading in the wrong direction. The agent then
   * tries a different approach.
   *
   * Note: This is a conceptual backtrack - we can't actually undo UI actions.
   * Instead, we remove them from the recording history and add a note about
   * why we're trying a different approach.
   */
  private fun applyBacktrack(
    state: BlazeState,
    steps: Int,
    reflection: ReflectionResult,
    notes: List<String>,
  ): BlazeState {
    val trimmedHistory = if (steps > 0 && state.actionHistory.size >= steps) {
      state.actionHistory.dropLast(steps)
    } else {
      state.actionHistory
    }

    // Also trim recent analyses to match
    if (steps > 0 && recentAnalyses.size >= steps) {
      val toRemove = minOf(steps, recentAnalyses.size)
      repeat(toRemove) {
        if (recentAnalyses.isNotEmpty()) recentAnalyses.removeLastOrNull()
      }
    }

    val backtrackNote = "Backtracked $steps steps: ${reflection.progressAssessment}. " +
      "Suggestion: ${reflection.suggestedCorrection ?: "try alternative approach"}"

    return state.copy(
      actionHistory = trimmedHistory,
      reflectionNotes = notes + backtrackNote,
    )
  }

  /**
   * Builds a human-readable note from a reflection result.
   */
  private fun buildReflectionNote(reflection: ReflectionResult): String = buildString {
    append("[Reflection] ")
    if (reflection.isOnTrack) {
      append("On track: ")
    } else {
      append("Course correction needed: ")
    }
    append(reflection.progressAssessment)

    if (reflection.loopDetected) {
      append(" (loop detected)")
    }

    reflection.suggestedCorrection?.let {
      append(". Suggestion: $it")
    }
  }

  /**
   * Performs one analyze-and-execute cycle for blaze exploration.
   *
   * This function:
   * 1. Captures the current screen state
   * 2. Analyzes it to determine the next action
   * 3. Handles exceptional states and objective-impossible (early return, no execution)
   * 4. Executes the recommended action and records it
   * 5. Checks objective achieved / subtask completion (advances subtask after execution)
   *
   * When task decomposition is active, uses the current subtask's description
   * as the objective for more focused action recommendations.
   *
   * @param state Current blaze exploration state
   * @return Updated state with new action history and screen summary
   */
  private suspend fun analyzeAndExecute(state: BlazeState): BlazeState {
    // Step 1: Capture current screen state
    var screenState = executor.captureScreenState()
      ?: return state.copy(
        stuck = true,
        stuckReason = "Failed to capture screen state - device may be disconnected",
      )

    // Generate trace ID for this iteration — correlates the LLM analysis with tool execution
    val traceId = TraceId.generate(TraceOrigin.LLM)

    // Step 1.5: Auto-dismiss known blocking dialogs before LLM analysis.
    // This saves an LLM iteration by handling common popups locally.
    val autoDismissed = exceptionalStateHandler.autoDismissBlockingDialog(screenState, traceId)
    if (autoDismissed) {
      // Dialog was dismissed — re-capture screen and continue with clean state
      screenState = executor.captureScreenState()
        ?: return state.copy(
          stuck = true,
          stuckReason = "Failed to capture screen after auto-dismissing dialog",
        )
    }

    // Step 2: Build context for the analyzer
    // When task decomposition is active, use subtask as the objective for focus
    // but also include the overall objective so the analyzer can make decisions
    // that serve the bigger picture (e.g., proactively act toward next subtask).
    val currentObjective = state.currentSubtask?.description ?: state.objective
    val hint = state.currentSubtask?.successCriteria // Use success criteria as hint
    val plan = state.taskPlan
    val overallObjective = if (plan != null && state.currentSubtask != null) state.objective else null
    val nextSubtask = plan?.subtasks?.getOrNull((plan.currentSubtaskIndex) + 1)
    val nextSubtaskHint = nextSubtask?.let {
      "Next step after this: ${it.description}"
    }
    val recommendationContext = RecommendationContext(
      objective = currentObjective,
      overallObjective = overallObjective,
      nextSubtaskHint = nextSubtaskHint,
      progressSummary = buildProgressSummary(state, config),
      hint = hint,
      attemptNumber = state.iteration + 1,
    )

    // Step 3: Analyze the screen (passing trace ID for LLM request correlation)
    val analysis = screenAnalyzer.analyze(
      context = recommendationContext,
      screenState = screenState,
      traceId = traceId,
      availableTools = availableToolsProvider(),
    )

    // Store analysis for reflection (keep last N for pattern detection)
    recentAnalyses.add(analysis)
    if (recentAnalyses.size > ReflectionNode.RECENT_ACTIONS_WINDOW) {
      recentAnalyses.removeAt(0)
    }

    // Step 4: Handle exceptional screen states (popup, ad, error, etc.)
    if (analysis.screenState != ExceptionalScreenState.NORMAL) {
      val recoveredState = exceptionalStateHandler.handleExceptionalState(state, analysis, traceId)
      // If recovery changed state (e.g., marked stuck), return it
      if (recoveredState.stuck || recoveredState != state) {
        return recoveredState.copy(iteration = state.iteration + 1)
      }
      // Otherwise, we recovered successfully - re-analyze on next iteration
      return recoveredState.copy(
        iteration = state.iteration + 1,
        reflectionNotes = state.reflectionNotes +
          "Recovered from ${analysis.screenState}: ${analysis.recoveryAction}",
      )
    }

    // Step 5: Check if objective appears impossible (don't execute, trigger replanning)
    if (analysis.objectiveAppearsImpossible) {
      // When task decomposition is active, trigger replanning instead of immediate failure
      if (state.hasTaskPlan && planningNode != null) {
        val stuckState = handleSubtaskStuck(state, analysis, screenState.screenshotBytes)
        if (stuckState.stuck) {
          return stuckState.copy(iteration = state.iteration + 1)
        }
        return stuckState.copy(iteration = state.iteration + 1)
      }
      return state.copy(
        stuck = true,
        stuckReason = "Objective appears impossible from current screen: ${analysis.screenSummary}",
        screenSummary = analysis.screenSummary,
        iteration = state.iteration + 1,
      )
    }

    // Step 6: Add low-confidence reflection note before execution
    val stateForExecution = if (config.enableReflection && analysis.confidence == Confidence.LOW) {
      val reflectionNote = "Low confidence on iteration ${state.iteration + 1}: " +
        "${analysis.reasoning}. Potential blockers: ${analysis.potentialBlockers.joinToString()}"
      state.copy(reflectionNotes = state.reflectionNotes + reflectionNote)
    } else {
      state
    }

    // Step 7: Check if the Screen Analyzer reported subtask completion via objectiveStatus.
    //
    // The objectiveStatus tool is a status-reporting tool, not a UI action. When the
    // Screen Analyzer calls objectiveStatus(COMPLETED), it means the current subtask's
    // success criteria are already satisfied on screen — no UI action is needed.
    //
    // We detect this and advance to the next subtask WITHOUT executing objectiveStatus
    // as a UI action (which would be a no-op and waste an iteration).
    val isObjectiveStatusCompleted = isObjectiveStatusCompleted(analysis)
    if (isObjectiveStatusCompleted && !stateForExecution.stuck) {
      val currentPlan = stateForExecution.taskPlan
      if (currentPlan != null && !currentPlan.isComplete) {
        // Guard against skipping subtasks based on pre-existing state.
        // If the agent has taken 0 actions for this subtask, the screen
        // state is unchanged from before — "COMPLETED" likely means the
        // agent confused pre-existing elements with task completion.
        if (stateForExecution.currentSubtaskActions == 0) {
          // Don't skip — force the agent to take an actual action.
          // Pre-existing state is not task completion.
        } else {
          // Subtask satisfied by agent's actions — advance
          return advanceToNextSubtask(
            stateForExecution.copy(iteration = stateForExecution.iteration + 1),
            analysis,
          )
        }
      } else {
        // No plan or plan complete — overall objective achieved
        return stateForExecution.copy(
          achieved = true,
          screenSummary = analysis.screenSummary,
          iteration = stateForExecution.iteration + 1,
        )
      }
    }

    // Step 8: Execute the recommended action and record it.
    // IMPORTANT: Always execute BEFORE checking completion flags. The LLM's tool call
    // IS the action that achieves the objective — objectiveAppearsAchieved means
    // "this action completes it", not "it's already done, skip the action".
    val executedState = executeAndRecord(stateForExecution, analysis, traceId)

    // Step 9: Check if objective is achieved (after executing the action)
    // Following Mobile-Agent-v3's Progress Manager pattern: when the Operating Agent
    // (Screen Analyzer) reports the objective is achieved, trust it and advance.
    // The Reflection Agent catches mistakes via periodic self-assessment.
    if (analysis.objectiveAppearsAchieved && !executedState.stuck) {
      val plan = executedState.taskPlan
      if (plan != null && !plan.isComplete) {
        return advanceToNextSubtask(executedState, analysis)
      }
      return executedState.copy(
        achieved = true,
        screenSummary = analysis.screenSummary,
      )
    }

    // Step 9b: Check subtask completion via success criteria string matching (free, no LLM)
    if (!executedState.stuck && executedState.hasTaskPlan && planningNode != null) {
      if (planningNode.isSubtaskComplete(executedState, analysis)) {
        return advanceToNextSubtask(executedState, analysis)
      }
    }

    return executedState
  }

  /**
   * Executes the recommended action and records it in state.
   */
  private suspend fun executeAndRecord(
    state: BlazeState,
    analysis: ScreenAnalysis,
    traceId: TraceId,
  ): BlazeState {
    val startTime = System.currentTimeMillis()

    // Execute the action (same traceId as the LLM analysis that recommended it)
    val result: ExecutionResult = executor.execute(
      toolName = analysis.recommendedTool,
      args = analysis.recommendedArgs,
      traceId = traceId,
    )

    val durationMs = System.currentTimeMillis() - startTime

    // Create the recorded action
    val recordedAction = createRecordedAction(
      analysis = analysis,
      result = result,
      durationMs = durationMs,
    )

    // Always track action history for stuck detection, reflection, and loop detection.
    // The generateRecording flag controls trail file export, not internal tracking.
    val newHistory = state.actionHistory + recordedAction

    return when (result) {
      is ExecutionResult.Success -> state.copy(
        actionHistory = newHistory,
        // Use the LLM's screen analysis summary (rich description of what's on screen)
        // instead of the executor's post-action summary (e.g. "Tapped at (x, y)").
        // This ensures the planner receives meaningful screen context during replanning.
        screenSummary = analysis.screenSummary,
        iteration = state.iteration + 1,
        currentSubtaskActions = if (state.hasTaskPlan) state.currentSubtaskActions + 1 else state.currentSubtaskActions,
      )
      is ExecutionResult.Failure -> {
        if (result.recoverable) {
          // Recoverable failure - record but continue
          // Increment currentSubtaskActions so stuck-detection and maxActionsPerSubtask
          // correctly account for failed attempts within the current subtask.
          state.copy(
            actionHistory = newHistory,
            iteration = state.iteration + 1,
            currentSubtaskActions = if (state.hasTaskPlan) state.currentSubtaskActions + 1 else state.currentSubtaskActions,
            reflectionNotes = state.reflectionNotes +
              "Action failed (recoverable): ${result.error}",
          )
        } else {
          // Non-recoverable failure - mark as stuck
          state.copy(
            actionHistory = newHistory,
            stuck = true,
            stuckReason = "Non-recoverable action failure: ${result.error}",
            iteration = state.iteration + 1,
            currentSubtaskActions = if (state.hasTaskPlan) state.currentSubtaskActions + 1 else state.currentSubtaskActions,
          )
        }
      }
    }
  }

  /**
   * Creates a [RecordedAction] from analysis and execution results.
   *
   * This captures all information needed to replay the action deterministically
   * in future trail executions.
   */
  private fun createRecordedAction(
    analysis: ScreenAnalysis,
    result: ExecutionResult,
    durationMs: Long,
  ): RecordedAction = RecordedAction(
    toolName = analysis.recommendedTool,
    toolArgs = analysis.recommendedArgs,
    reasoning = analysis.reasoning,
    screenSummaryBefore = analysis.screenSummary,
    screenSummaryAfter = when (result) {
      is ExecutionResult.Success -> result.screenSummaryAfter
      is ExecutionResult.Failure -> null
    },
    confidence = analysis.confidence,
    durationMs = durationMs,
    success = result is ExecutionResult.Success,
    errorMessage = (result as? ExecutionResult.Failure)?.error,
  )

  /**
   * Checks if the Screen Analyzer recommended `objectiveStatus` with COMPLETED status.
   *
   * The `objectiveStatus` tool is a status-reporting tool from the Koog agent framework.
   * When the Screen Analyzer calls it, it means the LLM believes the current subtask's
   * success criteria are already met on screen. Since `objectiveStatus` is not wrapped
   * with analysis parameters (reasoning, objectiveAppearsAchieved, etc.), the
   * `objectiveAppearsAchieved` flag in the ScreenAnalysis defaults to false.
   *
   * This method detects that case and enables the BlazeGoalPlanner to properly advance
   * to the next subtask without executing a no-op UI action.
   *
   * @param analysis The screen analysis to check
   * @return True if the analysis recommends objectiveStatus with COMPLETED status
   */
  private fun isObjectiveStatusCompleted(analysis: ScreenAnalysis): Boolean {
    if (analysis.recommendedTool != "objectiveStatus") return false
    val status = analysis.recommendedArgs["status"]?.jsonPrimitive?.content
    return status.equals("COMPLETED", ignoreCase = true)
  }

}

/**
 * Creates a [BlazeState] initialized for exploring toward the given objective.
 *
 * @param objective The goal to achieve, expressed in natural language
 * @param taskPlan Optional pre-decomposed task plan (if null and decomposition is enabled,
 *                 the planner will decompose automatically)
 * @return Initial blaze state ready for exploration
 */
fun initialBlazeState(
  objective: String,
  taskPlan: TaskPlan? = null,
  workingMemory: WorkingMemory = WorkingMemory.EMPTY,
): BlazeState = BlazeState(
  objective = objective,
  actionHistory = emptyList(),
  iteration = 0,
  achieved = false,
  stuck = false,
  stuckReason = null,
  screenSummary = null,
  reflectionNotes = emptyList(),
  taskPlan = taskPlan,
  currentSubtaskActions = 0,
  workingMemory = workingMemory,
)

/**
 * Convenience function to create a blaze goal planner.
 *
 * @param config Configuration for exploration (max iterations, confidence threshold, etc.)
 * @param screenAnalyzer Inner agent that analyzes screens and recommends actions
 * @param executor Executes UI actions on the connected device
 * @param planningNode Optional planning node for task decomposition (Phase 3)
 * @param memoryNode Optional memory node for cross-app workflows (Phase 4)
 * @return A [BlazeGoalPlanner] configured for blaze exploration
 */
fun createBlazeGoalPlanner(
  config: BlazeConfig,
  screenAnalyzer: ScreenAnalyzer,
  executor: UiActionExecutor,
  planningNode: PlanningNode? = null,
  memoryNode: MemoryNode? = null,
): BlazeGoalPlanner = BlazeGoalPlanner(config, screenAnalyzer, executor, planningNode, memoryNode)

/**
 * Creates a blaze goal planner with task decomposition enabled.
 *
 * This factory function creates a [BlazeGoalPlanner] with a [PlanningNode] for
 * Phase 3 task decomposition. Complex objectives are automatically broken into
 * subtasks for better progress tracking and replanning capability.
 *
 * @param config Configuration for exploration (should have enableTaskDecomposition = true)
 * @param screenAnalyzer Inner agent that analyzes screens and recommends actions
 * @param executor Executes UI actions on the connected device
 * @param plannerLlmCall Tool-calling LLM function for planning (used by PlanningNode)
 * @return A [BlazeGoalPlanner] with task decomposition enabled
 */
fun createBlazeGoalPlannerWithDecomposition(
  config: BlazeConfig,
  screenAnalyzer: ScreenAnalyzer,
  executor: UiActionExecutor,
  plannerLlmCall: PlannerLlmCall,
  availableToolsProvider: () -> List<TrailblazeToolDescriptor> = { emptyList() },
): BlazeGoalPlanner {
  val planningNode = createPlanningNode(config, plannerLlmCall)
  return BlazeGoalPlanner(config, screenAnalyzer, executor, planningNode, availableToolsProvider = availableToolsProvider)
}

/**
 * Creates a fully-featured blaze goal planner with all Phase 3-4 capabilities.
 *
 * This factory function creates a [BlazeGoalPlanner] with:
 * - **Task Decomposition** (Phase 3): Complex objectives broken into subtasks
 * - **Cross-App Memory** (Phase 4): Facts and screenshots persist across app switches
 *
 * Use this for complex multi-app workflows that require both planning and memory.
 *
 * @param config Configuration for exploration
 * @param screenAnalyzer Inner agent that analyzes screens and recommends actions
 * @param executor Executes UI actions on the connected device
 * @param plannerLlmCall Tool-calling LLM function for planning
 * @param ocrExtractor Optional OCR function for extracting text from screenshots
 * @return A [BlazeGoalPlanner] with full Mobile-Agent-v3 capabilities
 */
fun createFullFeaturedBlazeGoalPlanner(
  config: BlazeConfig,
  screenAnalyzer: ScreenAnalyzer,
  executor: UiActionExecutor,
  plannerLlmCall: PlannerLlmCall,
  ocrExtractor: OcrExtractor? = null,
  availableToolsProvider: () -> List<TrailblazeToolDescriptor> = { emptyList() },
): BlazeGoalPlanner {
  val planningNode = createPlanningNode(config, plannerLlmCall)
  val memoryNode = createMemoryNode(executor, ocrExtractor)
  return BlazeGoalPlanner(config, screenAnalyzer, executor, planningNode, memoryNode, availableToolsProvider)
}
