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
