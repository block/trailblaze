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
 * Detects when the agent is repeating actions without making progress.
 *
 * This is a **tool-agnostic** detector — it doesn't hardcode specific tool names.
 * Instead, it looks at the recent action history and detects two failure modes:
 *
 * 1. The same tool+args repeated consecutively (e.g. tapping the same field over and over).
 * 2. A short cycle of actions repeating (e.g. tap → undo → tap → undo → …), which is
 *    common when the agent's tap appears to navigate but lands on the wrong screen,
 *    so it backs out and tries the same tap again.
 *
 * Cycles of length 1 (consecutive identical), 2 (alternating, A-B-A-B), and 3
 * (A-B-C-A-B-C) are detected. Lengths beyond 3 are intentionally not flagged: in
 * practice they more often represent legitimate exploration than a stuck loop.
 *
 * The hint escalates in urgency based on how many full cycles have repeated:
 * - 2 full cycles: gentle suggestion to try a different approach
 * - 3+ full cycles: strong warning that the current approach isn't working
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
  val signatures = subtaskActions.map { "${it.toolName}(${it.toolArgs})" }
  return detectActionCycleHint(signatures)
}

/**
 * Per-cycle-length thresholds for escalating a detected loop from `WARNING` (gentle hint
 * to the LLM) to `CRITICAL` (consumed by [TrailblazeRunner] to terminate the step).
 *
 * Length-1 (consecutive identical actions) tolerates the most repetition because legitimate
 * cases are common: counter-driven flows ("add the same item three times"), sequential PIN
 * digit entry that happens to use the same tool with the same args, scrolling a long list
 * in a fixed direction, etc. Length-2/3 cycles (alternating, three-step) are far less
 * likely to be intentional, so the threshold drops, but still leaves a buffer so the LLM
 * can recover after the WARNING hint without us hard-killing the run.
 *
 * Numbers chosen to hit roughly the same total entry count (≈ 30) across all three lengths
 * — that's what determines the sliding window's required size in [TrailblazeRunner]'s
 * `STUCK_FINGERPRINT_WINDOW`.
 */
internal const val LENGTH_1_CRITICAL_REPEATS: Int = 30
internal const val LENGTH_2_CRITICAL_REPEATS: Int = 15
internal const val LENGTH_3_CRITICAL_REPEATS: Int = 10

/**
 * Generic cycle detector over a list of action signatures.
 *
 * Each entry in [signatures] should be a stable string representation of one action
 * (typically `"toolName(args)"`). Two actions are considered "the same" when their
 * signatures are equal. The detector scans the tail for cycles of length 1, 2, and 3
 * — shortest match wins, so a true single-action repeat reports as length 1 even
 * though it would also match length 2.
 *
 * @return Hint message describing the loop, or null if no loop is detected.
 */
internal fun detectActionCycleHint(signatures: List<String>): String? {
  if (signatures.size < 2) return null

  for (cycleLen in 1..3) {
    if (signatures.size < cycleLen * 2) continue

    val tail = signatures.takeLast(cycleLen)
    var fullRepeats = 1 // tail itself counts as one occurrence
    var i = signatures.size - cycleLen * 2
    while (i >= 0) {
      val candidate = signatures.subList(i, i + cycleLen)
      if (candidate == tail) {
        fullRepeats++
        i -= cycleLen
      } else {
        break
      }
    }

    if (fullRepeats >= 2) {
      return formatCycleHint(tail, cycleLen, fullRepeats)
    }
  }
  return null
}

/**
 * Formats a corrective hint for a detected loop. Length-1 phrasing is preserved from
 * the prior single-action detector to avoid regressing prompt quality on the most
 * common case; length-2/3 phrasing surfaces the cycle explicitly.
 *
 * The CRITICAL threshold is per cycle length so the bar for hard-failing the run scales
 * with the loop's "obviousness." Tight single-action repetition can be legitimate (entering
 * a PIN sequentially, "add the same item N times" instructions, scrolling a long list with
 * a fixed direction) so we tolerate a much longer streak before declaring stuck. Multi-step
 * loops (length 2 / 3) are easier to mistake for genuine progress, so they need fewer full
 * repetitions before we flip to CRITICAL — but still a healthy buffer beyond the previous
 * 3-cycle bar to give the LLM room to back out on its own after seeing the WARNING-level
 * hint. All three thresholds intentionally land near 30 raw entries so a single sliding
 * window (see [STUCK_FINGERPRINT_WINDOW_SIZE] in TrailblazeRunner) sizes to fit any of them.
 */
private fun formatCycleHint(cycle: List<String>, cycleLen: Int, fullRepeats: Int): String {
  val criticalThreshold = when (cycleLen) {
    1 -> LENGTH_1_CRITICAL_REPEATS
    2 -> LENGTH_2_CRITICAL_REPEATS
    3 -> LENGTH_3_CRITICAL_REPEATS
    else -> LENGTH_1_CRITICAL_REPEATS
  }
  val isCritical = fullRepeats >= criticalThreshold
  return when (cycleLen) {
    1 -> {
      val signature = cycle.first()
      val toolName = signature.substringBefore('(', missingDelimiterValue = signature)
      val toolDesc = "'$toolName'"
      if (!isCritical) {
        "WARNING: You have called $toolDesc with the same arguments $fullRepeats times in a row. " +
          "This action is not making progress. Try a DIFFERENT action or approach to achieve the objective."
      } else {
        "CRITICAL: You have called $toolDesc with the same arguments $fullRepeats times " +
          "consecutively without progress. STOP repeating this action. The current approach is not working. " +
          "Try a completely different strategy — use a different tool, different coordinates, or address " +
          "what might be blocking progress (e.g., dismiss a keyboard, close a dialog, navigate differently)."
      }
    }
    else -> {
      val seq = cycle.joinToString(" → ") { sig ->
        "'${sig.substringBefore('(', missingDelimiterValue = sig)}'"
      }
      if (!isCritical) {
        "WARNING: You have repeated a cycle of $cycleLen actions ($seq) $fullRepeats times. " +
          "This pattern is not making progress — the actions appear to be undoing each other or " +
          "returning to the same state. Try a DIFFERENT approach to achieve the objective."
      } else {
        "CRITICAL: You have repeated a cycle of $cycleLen actions ($seq) $fullRepeats times " +
          "without progress. STOP this loop. Try a completely different strategy — use a different " +
          "element, address what might be blocking progress (e.g., dismiss a popup, scroll the screen, " +
          "navigate via a different path)."
      }
    }
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
