package xyz.block.trailblaze.agent

import java.util.concurrent.atomic.AtomicInteger
import xyz.block.trailblaze.util.Console

/**
 * Default strategy for outer agent decision making.
 *
 * This strategy implements a simple but effective decision-making algorithm:
 *
 * ## Decision Logic
 *
 * 1. **Objective achieved** → Complete immediately
 * 2. **Objective impossible** → Fail immediately
 * 3. **HIGH confidence** → Execute the recommended action
 * 4. **MEDIUM confidence** → Execute but log for monitoring
 * 5. **LOW confidence** → Request new analysis with guidance hint (with retry limit)
 * 6. **Stuck detection** → Try alternatives or fail after threshold
 *
 * ## Configuration
 *
 * - [maxLowConfidenceRetries]: Maximum consecutive low-confidence retries before executing anyway
 * - [stuckThreshold]: Number of consecutive failures before considering "stuck"
 *
 * ## Example
 *
 * ```kotlin
 * val strategy = DefaultOuterStrategy(
 *   maxLowConfidenceRetries = 3,
 *   stuckThreshold = 3,
 * )
 *
 * val decision = strategy.decide(
 *   objective = "Log in",
 *   analysisResult = screenAnalysis,
 *   history = previousActions,
 * )
 * ```
 *
 * @param maxLowConfidenceRetries Maximum consecutive low-confidence retries before executing anyway
 * @param stuckThreshold Number of consecutive failures before trying alternatives or failing
 */
class DefaultOuterStrategy(
  private val maxLowConfidenceRetries: Int = DEFAULT_MAX_LOW_CONFIDENCE_RETRIES,
  private val stuckThreshold: Int = DEFAULT_STUCK_THRESHOLD,
) : OuterAgentStrategy {

  companion object {
    const val DEFAULT_MAX_LOW_CONFIDENCE_RETRIES = 2
    const val DEFAULT_STUCK_THRESHOLD = 3

    private const val LOW_CONFIDENCE_HINT =
      "The previous recommendation had low confidence. Please look more carefully at the screen and suggest a more certain action."
  }

  /** Tracks consecutive low-confidence retries. Thread-safe via AtomicInteger. */
  private val consecutiveLowConfidenceCount = AtomicInteger(0)

  override suspend fun decide(
    objective: String,
    analysisResult: ScreenAnalysis,
    history: List<AgentAction>,
  ): OuterAgentDecision {
    // 1. Check if objective is complete
    if (analysisResult.objectiveAppearsAchieved) {
      return OuterAgentDecision.Complete(
        summary = "Objective achieved: ${analysisResult.screenSummary}"
      )
    }

    // 2. Check if objective is impossible
    if (analysisResult.objectiveAppearsImpossible) {
      return OuterAgentDecision.Fail(
        reason = "Objective impossible: ${analysisResult.screenSummary}"
      )
    }

    // 3. Check if we're stuck (repeated failures)
    if (isStuck(history)) {
      if (analysisResult.alternativeApproaches.isNotEmpty()) {
        val alternative = analysisResult.alternativeApproaches.first()
        return OuterAgentDecision.RequestNewAnalysis(
          hint = "We appear stuck after repeated failures. Try this alternative approach: $alternative"
        )
      }
      return OuterAgentDecision.Fail(
        reason = "Stuck: $stuckThreshold consecutive action failures without progress. " +
          "Last screen: ${analysisResult.screenSummary}"
      )
    }

    // 4. Check for blockers and log them
    if (analysisResult.potentialBlockers.isNotEmpty()) {
      val blockersSummary = analysisResult.potentialBlockers.joinToString(", ")
      Console.log("[DefaultOuterStrategy] Potential blockers detected: $blockersSummary")
    }

    // 5. Handle based on confidence level
    return when (analysisResult.confidence) {
      Confidence.HIGH -> {
        // High confidence - execute immediately
        consecutiveLowConfidenceCount.set(0)
        OuterAgentDecision.Execute(
          tool = analysisResult.recommendedTool,
          args = analysisResult.recommendedArgs,
        )
      }

      Confidence.MEDIUM -> {
        // Medium confidence - execute but log for monitoring
        consecutiveLowConfidenceCount.set(0)
        Console.log("[DefaultOuterStrategy] Executing medium-confidence action: ${analysisResult.recommendedTool}")
        Console.log("[DefaultOuterStrategy] Reasoning: ${analysisResult.reasoning}")
        OuterAgentDecision.Execute(
          tool = analysisResult.recommendedTool,
          args = analysisResult.recommendedArgs,
        )
      }

      Confidence.LOW -> {
        handleLowConfidence(analysisResult)
      }
    }
  }

  /**
   * Handles low confidence recommendations with retry logic.
   */
  private fun handleLowConfidence(analysisResult: ScreenAnalysis): OuterAgentDecision {
    val count = consecutiveLowConfidenceCount.incrementAndGet()

    if (count > maxLowConfidenceRetries) {
      // Too many low confidence attempts - execute anyway
      Console.log("[DefaultOuterStrategy] Max low-confidence retries ($maxLowConfidenceRetries) exceeded, executing anyway")
      consecutiveLowConfidenceCount.set(0)
      return OuterAgentDecision.Execute(
        tool = analysisResult.recommendedTool,
        args = analysisResult.recommendedArgs,
      )
    }

    // Request new analysis with helpful hint
    val hint = buildLowConfidenceHint(analysisResult)
    Console.log("[DefaultOuterStrategy] Low confidence (attempt $count/$maxLowConfidenceRetries), requesting new analysis")
    return OuterAgentDecision.RequestNewAnalysis(hint = hint)
  }

  /**
   * Detects if the agent is stuck by checking for repeated consecutive failures.
   */
  private fun isStuck(history: List<AgentAction>): Boolean {
    if (history.size < stuckThreshold) return false

    val recentActions = history.takeLast(stuckThreshold)

    // Check if all recent actions were failures
    val allFailed = recentActions.all { it.result is ExecutionResult.Failure }
    if (allFailed) return true

    // Check if we're repeating the same action without progress
    val uniqueTools = recentActions.map { it.tool }.toSet()
    val allSameScreen = recentActions.mapNotNull { it.screenSummaryAfter }.toSet().size <= 1

    return uniqueTools.size == 1 && allSameScreen
  }

  /**
   * Builds a helpful hint for the inner agent when confidence is low.
   */
  private fun buildLowConfidenceHint(analysis: ScreenAnalysis): String {
    val hints = mutableListOf(LOW_CONFIDENCE_HINT)

    if (analysis.potentialBlockers.isNotEmpty()) {
      hints.add("Be aware of these potential blockers: ${analysis.potentialBlockers.joinToString(", ")}")
    }

    if (analysis.alternativeApproaches.isNotEmpty()) {
      hints.add("Consider these alternatives: ${analysis.alternativeApproaches.joinToString(", ")}")
    }

    if (analysis.progressIndicators.isNotEmpty()) {
      hints.add("Progress so far: ${analysis.progressIndicators.joinToString(", ")}")
    }

    return hints.joinToString(" ")
  }
}
