package xyz.block.trailblaze.agent.blaze

import xyz.block.trailblaze.agent.BlazeConfig
import xyz.block.trailblaze.agent.BlazeState
import xyz.block.trailblaze.toolcalls.CoreTools

/**
 * Builds a progress summary from the current state for the screen analyzer.
 *
 * When task decomposition is active, includes subtask progress information.
 * Also detects behavioral anti-patterns (like clicking a field repeatedly instead
 * of typing) and injects corrective hints.
 */
internal fun buildProgressSummary(state: BlazeState, config: BlazeConfig): String = buildString {
  append("Iteration ${state.iteration + 1}")

  // Include subtask progress when task decomposition is active
  state.taskPlan?.let { plan ->
    append(". Overall objective: \"${plan.objective}\"")
    append(". Subtask ${plan.currentSubtaskIndex + 1}/${plan.subtasks.size}")
    plan.currentSubtask?.let { subtask ->
      append(": \"${subtask.description}\"")
      append(" (${state.currentSubtaskActions}/${config.maxActionsPerSubtask} actions)")
      append(". Success criteria: ${subtask.successCriteria}")
    }
    // Include next subtask so the analyzer can take proactive action.
    // CRITICAL: Qualify with initial state so the agent doesn't confuse pre-existing
    // screen elements with task completion. For example, if a 7:00 AM alarm already
    // exists when the task starts, seeing it on screen doesn't mean "create alarm" is done.
    val nextSubtask = plan.subtasks.getOrNull(plan.currentSubtaskIndex + 1)
    nextSubtask?.let { next ->
      if (state.actionHistory.isEmpty()) {
        // No actions taken yet — screen is in initial state.
        // Don't suggest skipping since nothing has been done by the agent.
        append(". Next step after this: \"${next.description}\"")
      } else {
        append(
          ". IMPORTANT: If current subtask is already satisfied on screen " +
            "as a result of YOUR previous actions (not pre-existing state), " +
            "take the first action needed for the next step: \"${next.description}\"",
        )
      }
    }
    // Include initial screen context so the LLM can distinguish pre-existing state
    state.initialScreenSummary?.let { initialScreen ->
      if (state.actionHistory.size <= 2) {
        append(". INITIAL SCREEN STATE (before any actions): $initialScreen")
      }
    }
    if (plan.replanCount > 0) {
      append(". Replanned ${plan.replanCount} time(s)")
    }
  }

  if (state.actionHistory.isNotEmpty()) {
    append(". Total actions: ${state.actionHistory.size}")
    val lastAction = state.actionHistory.last()
    append(". Last action: ${lastAction.toolName}")
    if (lastAction.success) {
      append(" (success)")
    } else {
      append(" (failed: ${lastAction.errorMessage})")
    }
  }

  state.screenSummary?.let { summary ->
    append(". Current screen: $summary")
  }

  if (state.reflectionNotes.isNotEmpty()) {
    append(". Notes: ${state.reflectionNotes.last()}")
  }

  // Detect repetitive action patterns and inject corrective hints.
  // This is tool-agnostic: it works for any tool (present or future) by
  // detecting when the same action is repeated without making progress.
  detectRepetitiveActionHint(state)?.let { hint ->
    append(". $hint")
  }

  // Detect keyboard-visible state after text entry.
  // After a 'type' action the on-screen keyboard remains visible, compressing the
  // visible area. If the next action should be a tap/click on a different form
  // field, the keyboard must be dismissed first to avoid misclicks on nearby elements.
  detectKeyboardAfterTypingHint(state)?.let { hint ->
    append(". $hint")
  }
}

/**
 * Detects when the agent is repeating the same action without making progress.
 *
 * This is a **tool-agnostic** detector — it doesn't hardcode specific tool names.
 * Instead, it looks at the recent action history and detects when the same tool
 * with the same (or very similar) arguments is called consecutively. This works
 * for any tool, including dynamically provided tools.
 *
 * Common patterns this catches:
 * - Clicking the same field repeatedly instead of typing
 * - Scrolling in the same direction without new content appearing
 * - Any action that keeps repeating without the screen state changing
 *
 * The hint escalates in urgency based on how many times the action repeated:
 * - 2 repetitions: gentle suggestion to try a different approach
 * - 3+ repetitions: strong warning that the current approach isn't working
 *
 * @return Corrective hint string, or null if no repetition is detected
 */
internal fun detectRepetitiveActionHint(state: BlazeState): String? {
  // Scope to current subtask actions to avoid cross-subtask false positives.
  // When task decomposition is disabled, currentSubtaskActions stays at 0,
  // so fall back to the full action history.
  val actionScope = if (state.currentSubtaskActions > 0) {
    state.currentSubtaskActions
  } else {
    state.actionHistory.size
  }
  val subtaskActions = state.actionHistory.takeLast(actionScope)
  if (subtaskActions.size < 2) return null

  // Count consecutive identical actions from the end of subtask history
  val lastAction = subtaskActions.last()
  var consecutiveCount = 1
  for (i in subtaskActions.size - 2 downTo 0) {
    val action = subtaskActions[i]
    if (action.toolName == lastAction.toolName &&
      action.toolArgs.toString() == lastAction.toolArgs.toString()
    ) {
      consecutiveCount++
    } else {
      break
    }
  }

  if (consecutiveCount < 2) return null

  // Build a tool-agnostic hint based on severity
  val toolDesc = "'${lastAction.toolName}'"
  return if (consecutiveCount == 2) {
    "WARNING: You have called $toolDesc with the same arguments $consecutiveCount times in a row. " +
      "This action is not making progress. Try a DIFFERENT action or approach to achieve the objective."
  } else {
    "CRITICAL: You have called $toolDesc with the same arguments $consecutiveCount times " +
      "consecutively without progress. STOP repeating this action. The current approach is not working. " +
      "Try a completely different strategy — use a different tool, different coordinates, or address " +
      "what might be blocking progress (e.g., dismiss a keyboard, close a dialog, navigate differently)."
  }
}

/**
 * Detects when the keyboard may still be visible after text entry despite auto-dismiss.
 *
 * The `type` and `type_into` tools auto-dismiss the keyboard using ESCAPE after typing.
 * This hint only triggers for edge cases where the auto-dismiss may not have worked
 * (e.g., type → click → click pattern where clicks keep hitting wrong targets).
 *
 * @return Keyboard-related hint string, or null if no keyboard issue is detected
 */
internal fun detectKeyboardAfterTypingHint(state: BlazeState): String? {
  if (state.actionHistory.isEmpty()) return null

  // The type/type_into tools auto-dismiss the keyboard, so we no longer need to
  // warn immediately after typing. Only detect the type → click → click pattern
  // where clicks may be hitting wrong targets (suggesting keyboard didn't dismiss).
  val recentActions = state.actionHistory.takeLast(
    minOf(state.actionHistory.size, maxOf(state.currentSubtaskActions, 3)),
  )
  if (recentActions.size >= 3) {
    val lastThree = recentActions.takeLast(3)
    val typeAction = lastThree[0]
    val isTypeFollowedByClicks = CoreTools.isTextInputAction(typeAction.toolName) &&
      lastThree.drop(1).all { CoreTools.isTapAction(it.toolName) }

    if (isTypeFollowedByClicks) {
      return "KEYBOARD MAY STILL BE VISIBLE: A text entry was followed by clicks that may be " +
        "hitting incorrect targets. If a keyboard or dialog is blocking the form, dismiss it " +
        "by tapping an empty/non-interactive area of the screen. Do NOT use navigate_back — " +
        "it may close the form or trigger a discard dialog. Use the view hierarchy coordinates " +
        "(@x,y annotations) for precise tapping."
    }
  }

  return null
}
