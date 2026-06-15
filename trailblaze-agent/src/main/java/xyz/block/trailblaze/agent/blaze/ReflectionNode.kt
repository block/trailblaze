package xyz.block.trailblaze.agent.blaze

import xyz.block.trailblaze.agent.BlazeState
import xyz.block.trailblaze.agent.Confidence
import xyz.block.trailblaze.agent.RecordedAction
import xyz.block.trailblaze.agent.ReflectionResult
import xyz.block.trailblaze.agent.ScreenAnalysis

/**
 * Reflection node for self-assessment during blaze exploration.
 *
 * Implements Mobile-Agent-v3 style reflection for detecting when the agent
 * is stuck, looping, or making poor progress toward the objective.
 *
 * ## How It Works
 *
 * The reflection node examines:
 * 1. **Action History**: Detects loops (same actions repeated) and failures
 * 2. **Screen State**: Compares current screen to objective
 * 3. **Progress Indicators**: Evaluates if meaningful progress is being made
 *
 * ## When Reflection Triggers
 *
 * Reflection is triggered by [shouldReflect] when:
 * - The agent has been looping (same tool called 3+ times)
 * - Multiple low-confidence actions in a row
 * - Periodic checkpoints (every N iterations)
 * - Multiple consecutive failures
 *
 * ## Backtracking
 *
 * When the reflection determines the agent is off-track, it can suggest
 * backtracking to an earlier state. The [backtrackSteps] indicates how
 * many actions to "undo" (conceptually - by trying a different approach
 * from that point).
 *
 * @see ReflectionResult for the output of reflection
 * @see BlazeGoalPlanner for integration with the exploration loop
 */
class ReflectionNode {

  /**
   * Reflects on the current exploration state and recent screens.
   *
   * This is a heuristic-based reflection that doesn't require LLM calls.
   * For more sophisticated reflection, consider adding an LLM-powered
   * variant that can reason about screenshots.
   *
   * @param state Current blaze exploration state
   * @param recentAnalyses Recent screen analyses (newest last)
   * @return Reflection result with assessment and suggested corrections
   */
  fun reflect(
    state: BlazeState,
    recentAnalyses: List<ScreenAnalysis>,
  ): ReflectionResult {
    val recentActions = state.actionHistory.takeLast(RECENT_ACTIONS_WINDOW)

    // Check for loops (same tool with same args called repeatedly)
    val loopInfo = detectLoop(recentActions)
    if (loopInfo != null) {
      return ReflectionResult(
        isOnTrack = false,
        progressAssessment = "Agent appears to be in a loop: ${loopInfo.description}",
        suggestedCorrection = loopInfo.suggestedCorrection,
        shouldBacktrack = true,
        backtrackSteps = loopInfo.loopLength,
        loopDetected = true,
      )
    }

    // Check for multiple consecutive failures
    val failureInfo = detectConsecutiveFailures(recentActions)
    if (failureInfo != null) {
      return ReflectionResult(
        isOnTrack = false,
        progressAssessment = "Multiple consecutive failures detected: ${failureInfo.description}",
        suggestedCorrection = failureInfo.suggestedCorrection,
        shouldBacktrack = failureInfo.shouldBacktrack,
        backtrackSteps = if (failureInfo.shouldBacktrack) failureInfo.failureCount else 0,
        stuckReason = if (!failureInfo.recoverable) failureInfo.description else null,
      )
    }

    // Check for too many low-confidence actions
    val confidenceInfo = detectLowConfidencePattern(recentActions)
    if (confidenceInfo != null) {
      return ReflectionResult(
        isOnTrack = false,
        progressAssessment = "Multiple low-confidence actions suggest uncertainty",
        suggestedCorrection = confidenceInfo.suggestedCorrection,
        shouldBacktrack = false,
      )
    }

    // Check for screen-based issues (same screen appearing multiple times)
    val screenLoopInfo = detectScreenLoop(recentAnalyses)
    if (screenLoopInfo != null) {
      return ReflectionResult(
        isOnTrack = false,
        progressAssessment = "Screen state appears unchanged despite actions",
        suggestedCorrection = screenLoopInfo.suggestedCorrection,
        shouldBacktrack = true,
        backtrackSteps = screenLoopInfo.stagnantCount,
        loopDetected = true,
      )
    }

    // Check for progress based on screen analyses
    val progressInfo = assessProgress(state, recentAnalyses)

    return ReflectionResult(
      isOnTrack = progressInfo.isPositive,
      progressAssessment = progressInfo.assessment,
      suggestedCorrection = progressInfo.correction,
    )
  }

  /**
   * Detects if the agent is in a loop (repeating the same action pattern).
   */
  private fun detectLoop(recentActions: List<RecordedAction>): LoopInfo? {
    if (recentActions.size < MIN_ACTIONS_FOR_LOOP_DETECTION) return null

    // Group by tool name
    val toolCounts = recentActions.groupingBy { it.toolName }.eachCount()
    val mostCommonTool = toolCounts.maxByOrNull { it.value }

    if (mostCommonTool != null && mostCommonTool.value >= LOOP_THRESHOLD) {
      val loopingActions = recentActions.filter { it.toolName == mostCommonTool.key }

      // Check if args are also similar (indicating true loop vs legitimate repetition)
      val uniqueArgs = loopingActions.map { it.toolArgs.toString() }.toSet()
      if (uniqueArgs.size <= 2) {
        // Count consecutive repetitions from the end of the action history
        // (this is how many actions to actually backtrack, not total occurrences)
        val consecutiveCount = recentActions.reversed()
          .takeWhile { it.toolName == mostCommonTool.key }
          .size
          .coerceAtLeast(1)

        return LoopInfo(
          description = "Tool '${mostCommonTool.key}' called ${mostCommonTool.value} times " +
            "with similar arguments",
          suggestedCorrection = "Try a different approach - perhaps scroll, navigate back, " +
            "or try an alternative element",
          loopLength = consecutiveCount,
        )
      }
    }

    return null
  }

  /**
   * Detects multiple consecutive action failures.
   */
  private fun detectConsecutiveFailures(recentActions: List<RecordedAction>): FailureInfo? {
    if (recentActions.isEmpty()) return null

    var consecutiveFailures = 0
    for (action in recentActions.reversed()) {
      if (!action.success) {
        consecutiveFailures++
      } else {
        break
      }
    }

    if (consecutiveFailures >= FAILURE_THRESHOLD) {
      val errorMessages = recentActions.takeLast(consecutiveFailures)
        .mapNotNull { it.errorMessage }
        .distinct()

      val recoverable = errorMessages.none { it.contains("not found", ignoreCase = true) }

      return FailureInfo(
        description = "$consecutiveFailures consecutive action failures. " +
          "Errors: ${errorMessages.joinToString("; ")}",
        suggestedCorrection = if (recoverable) {
          "Verify the current screen state and try a different interaction"
        } else {
          "Target elements may not be available - try navigating to a different screen"
        },
        failureCount = consecutiveFailures,
        shouldBacktrack = consecutiveFailures >= FAILURE_THRESHOLD + 1,
        recoverable = recoverable,
      )
    }

    return null
  }

  /**
   * Detects a pattern of low-confidence actions.
   */
  private fun detectLowConfidencePattern(recentActions: List<RecordedAction>): ConfidenceInfo? {
    if (recentActions.size < MIN_ACTIONS_FOR_CONFIDENCE_CHECK) return null

    val lowConfidenceCount = recentActions.count { it.confidence == Confidence.LOW }

    if (lowConfidenceCount >= LOW_CONFIDENCE_THRESHOLD) {
      return ConfidenceInfo(
        suggestedCorrection = "Multiple uncertain actions detected. Consider providing " +
          "more specific guidance or trying a different approach",
        lowConfidenceCount = lowConfidenceCount,
      )
    }

    return null
  }

  /**
   * Detects if the screen state hasn't changed despite actions.
   */
  private fun detectScreenLoop(recentAnalyses: List<ScreenAnalysis>): ScreenLoopInfo? {
    if (recentAnalyses.size < MIN_SCREENS_FOR_LOOP_DETECTION) return null

    // Compare screen summaries - if they're very similar, we might be stuck
    val summaries = recentAnalyses.map { it.screenSummary }
    val uniqueSummaries = summaries.toSet()

    // If we have few unique summaries compared to total, we're likely stuck
    if (uniqueSummaries.size <= summaries.size / 3 && summaries.size >= 3) {
      return ScreenLoopInfo(
        suggestedCorrection = "Screen appears unchanged - try scrolling, pressing back, " +
          "or interacting with a different element",
        stagnantCount = summaries.size - uniqueSummaries.size,
      )
    }

    return null
  }

  /**
   * Assesses overall progress based on state and recent analyses.
   */
  private fun assessProgress(
    state: BlazeState,
    recentAnalyses: List<ScreenAnalysis>,
  ): ProgressInfo {
    // If we have recent analyses, check for progress indicators
    if (recentAnalyses.isNotEmpty()) {
      val latestAnalysis = recentAnalyses.last()

      // Strong progress signals
      if (latestAnalysis.objectiveAppearsAchieved) {
        return ProgressInfo(
          isPositive = true,
          assessment = "Objective appears achieved based on screen state",
        )
      }

      // Check if blockers were recently cleared
      val progressIndicators = latestAnalysis.progressIndicators
      if (progressIndicators.isNotEmpty()) {
        return ProgressInfo(
          isPositive = true,
          assessment = "Making progress: ${progressIndicators.joinToString(", ")}",
        )
      }

      // Check for blockers
      val blockers = latestAnalysis.potentialBlockers
      if (blockers.isNotEmpty()) {
        return ProgressInfo(
          isPositive = false,
          assessment = "Potential blockers detected: ${blockers.joinToString(", ")}",
          correction = "Address blockers: ${latestAnalysis.alternativeApproaches.firstOrNull() 
            ?: "try dismissing dialogs or waiting for loading to complete"}",
        )
      }
    }

    // Default: assume on track if no red flags
    val successRate = if (state.actionHistory.isNotEmpty()) {
      state.actionHistory.count { it.success }.toFloat() / state.actionHistory.size
    } else {
      1.0f
    }

    return if (successRate >= 0.7f) {
      ProgressInfo(
        isPositive = true,
        assessment = "Iteration ${state.iteration}: ${state.actionHistory.size} actions taken, " +
          "${(successRate * 100).toInt()}% success rate",
      )
    } else {
      ProgressInfo(
        isPositive = false,
        assessment = "Low success rate (${(successRate * 100).toInt()}%) may indicate difficulties",
        correction = "Consider trying alternative approaches from the screen analysis",
      )
    }
  }

  companion object {
    /** Number of recent actions to consider for reflection */
    const val RECENT_ACTIONS_WINDOW = 10

    /** Minimum actions needed to detect a loop */
    const val MIN_ACTIONS_FOR_LOOP_DETECTION = 5

    /** Number of times a tool must be called to be considered a loop */
    const val LOOP_THRESHOLD = 3

    /** Minimum actions to check for low confidence pattern */
    const val MIN_ACTIONS_FOR_CONFIDENCE_CHECK = 5

    /** Number of low-confidence actions that triggers concern */
    const val LOW_CONFIDENCE_THRESHOLD = 3

    /** Number of consecutive failures that triggers concern */
    const val FAILURE_THRESHOLD = 2

    /** Minimum screens needed to detect screen loop */
    const val MIN_SCREENS_FOR_LOOP_DETECTION = 3

    /** Default reflection interval (every N iterations) */
    const val DEFAULT_REFLECTION_INTERVAL = 10
  }
}

// Internal data classes for reflection analysis

private data class LoopInfo(
  val description: String,
  val suggestedCorrection: String,
  val loopLength: Int,
)

private data class FailureInfo(
  val description: String,
  val suggestedCorrection: String,
  val failureCount: Int,
  val shouldBacktrack: Boolean,
  val recoverable: Boolean,
)

private data class ConfidenceInfo(
  val suggestedCorrection: String,
  val lowConfidenceCount: Int,
)

private data class ScreenLoopInfo(
  val suggestedCorrection: String,
  val stagnantCount: Int,
)

private data class ProgressInfo(
  val isPositive: Boolean,
  val assessment: String,
  val correction: String? = null,
)

/**
 * Outcome of the target-discipline check: when the step's named target is absent from the
 * tappable refs, the agent must recover rather than tap an unrelated distractor.
 *
 * @see detectTargetMissingRecovery
 */
enum class TargetMissingRecovery {
  /** Target is present (or no named target) — proceed with the recommended action. */
  PROCEED,

  /** A scroll-to-reveal affordance for the target is on screen — scroll toward it, then retry. */
  SCROLL_TO_REVEAL,

  /** No target and no affordance anywhere — likely the wrong screen; surface and stop. */
  WRONG_SCREEN,
}

/** Human-facing message surfaced when the target is not on this screen at all. */
const val WRONG_SCREEN_MESSAGE: String =
  "target not found on this screen — you may be on the wrong screen; this step may need to be revised"

/** Tap-style tool name fragments. Matches `tap`, `tapOnElementByNodeId`, `compose_click`, etc. */
private val TAP_TOOL_FRAGMENTS = listOf("tap", "click")

/**
 * Decides whether the agent should avoid tapping an unrelated clickable when the step's named
 * target isn't present among the tappable refs (the Hardware-Hub trap).
 *
 * Only engages for tap-style actions — scrolls, inputs, waits, and status calls are left alone.
 * The decision is driven entirely by the compact snapshot text the analyzer saw:
 * - The target text appears in the snapshot → [TargetMissingRecovery.PROCEED] (the LLM can act).
 * - The target is absent but a `(scroll … to reveal)` affordance is present →
 *   [TargetMissingRecovery.SCROLL_TO_REVEAL] (the offscreen-target flavor: scroll, don't tap).
 * - The target is absent and no affordance exists → [TargetMissingRecovery.WRONG_SCREEN]
 *   (the no-target flavor: surface the wrong-screen signal and stop).
 *
 * @param objective The step's natural-language instruction (e.g. "Tap the search field").
 * @param screenText The compact view-hierarchy snapshot text the analyzer was shown.
 * @param recommendedTool The tool the analyzer chose this iteration.
 */
fun detectTargetMissingRecovery(
  objective: String,
  screenText: String?,
  recommendedTool: String,
): TargetMissingRecovery {
  val tool = recommendedTool.lowercase()
  if (TAP_TOOL_FRAGMENTS.none { it in tool }) return TargetMissingRecovery.PROCEED

  val target = extractTargetPhrase(objective) ?: return TargetMissingRecovery.PROCEED

  // Absent compact text is not evidence of a wrong screen: drivers like Android HOST mode
  // intentionally leave viewHierarchyTextRepresentation null and feed the analyzer a JSON/tree
  // fallback instead. Don't intercept when we can't see the snapshot.
  val text = screenText ?: return TargetMissingRecovery.PROCEED

  // The target counts as "present and tappable" only when it appears on a line carrying a
  // ref marker (e.g. `[c596]`, `[n12]`). Static labels, container headers, and the
  // non-tappable `(scroll … to reveal)` affordance must NOT satisfy the present check.
  val lines = text.lines()
  if (lines.any { it.containsRefMarker() && it.contains(target, ignoreCase = true) }) {
    return TargetMissingRecovery.PROCEED
  }

  val affordanceLines = lines.filter { it.contains("to reveal)", ignoreCase = true) }
  return if (affordanceLines.any { it.contains(target, ignoreCase = true) }) {
    TargetMissingRecovery.SCROLL_TO_REVEAL
  } else {
    TargetMissingRecovery.WRONG_SCREEN
  }
}

/**
 * Matches a compact element-list ref marker — one letter, 1-3 digits, optional collision
 * suffix letter, e.g. `[a1]`, `[k42]`, `[z103]`, `[k42b]` (see [xyz.block.trailblaze.api.ElementRef]).
 * Deliberately excludes state annotations like `[checked]`, `[disabled]`, `[id=…]`.
 */
private val REF_MARKER = Regex("\\[[a-z]\\d{1,3}[a-z]?]", RegexOption.IGNORE_CASE)

private fun String.containsRefMarker(): Boolean = REF_MARKER.containsMatchIn(this)

/**
 * Reads the scroll direction ("up" / "down") from the target's `(scroll … to reveal)`
 * affordance line, so the recovery note can name the concrete direction. Defaults to "down".
 */
fun scrollDirectionFromAffordance(target: String, screenText: String?): String {
  val line = screenText?.lines()?.firstOrNull {
    it.contains("to reveal)", ignoreCase = true) && it.contains(target, ignoreCase = true)
  } ?: return "down"
  return if (line.contains("scroll up", ignoreCase = true)) "up" else "down"
}

/**
 * Extracts the named target phrase from a step instruction so it can be checked against the
 * snapshot. Prefers an explicitly quoted phrase; otherwise takes the noun phrase after a
 * leading action verb (tap/select/find/search/open/choose). Returns null when no specific
 * target can be identified (e.g. "go back", "scroll down") — those steps are left to proceed.
 */
internal fun extractTargetPhrase(objective: String): String? {
  Regex("[\"“']([^\"”']{2,})[\"”']").find(objective)?.let { return it.groupValues[1].trim() }

  val verb = Regex(
    "^\\s*(?:tap|click|select|find|search\\s+for|open|choose|press)\\s+(?:on\\s+|the\\s+)*(.+)$",
    RegexOption.IGNORE_CASE,
  ).find(objective) ?: return null

  return verb.groupValues[1]
    .trim()
    .removeSuffix(".")
    .removeSuffix(" field")
    .removeSuffix(" button")
    .removeSuffix(" icon")
    .trim()
    .takeIf { it.length >= 2 }
}

/**
 * Determines whether reflection should be triggered based on state.
 *
 * This is the entry point for deciding when to run [ReflectionNode.reflect].
 * Reflection triggers when:
 * - Agent has been looping (same tool called 3+ times recently)
 * - Multiple low-confidence actions in a row
 * - Periodic checkpoint (every [reflectionInterval] iterations)
 * - Multiple consecutive failures
 *
 * @param state Current blaze exploration state
 * @param reflectionInterval How often to trigger periodic reflection (iterations)
 * @return True if reflection should be performed
 */
fun shouldReflect(
  state: BlazeState,
  reflectionInterval: Int = ReflectionNode.DEFAULT_REFLECTION_INTERVAL,
): Boolean {
  val recentActions = state.actionHistory.takeLast(ReflectionNode.RECENT_ACTIONS_WINDOW)

  // Trigger 1: Same tool called 3+ times in recent history
  val toolCounts = recentActions.groupingBy { it.toolName }.eachCount()
  if (toolCounts.any { it.value >= ReflectionNode.LOOP_THRESHOLD }) {
    return true
  }

  // Trigger 2: Multiple low-confidence actions in a row
  if (recentActions.count { it.confidence == Confidence.LOW } >= ReflectionNode.LOW_CONFIDENCE_THRESHOLD) {
    return true
  }

  // Trigger 3: Periodic reflection every N iterations
  if (state.iteration > 0 && state.iteration % reflectionInterval == 0) {
    return true
  }

  // Trigger 4: Multiple consecutive failures
  val consecutiveFailures = recentActions.reversed().takeWhile { !it.success }.size
  return consecutiveFailures >= ReflectionNode.FAILURE_THRESHOLD
}
