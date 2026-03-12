package xyz.block.trailblaze.agent

import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor

/**
 * Interface for the inner agent that analyzes mobile UI screens.
 *
 * The screen analyzer performs single-shot analysis of the current screen state
 * and returns a recommendation for the next action. This is designed to be a
 * cheap, fast operation using a vision-capable model.
 *
 * ## Design Philosophy
 *
 * The screen analyzer follows a "single iteration" pattern:
 * - One LLM call per [analyze] invocation
 * - No internal loops or retries (that's the outer agent's job)
 * - Focus on what you SEE, not multi-step planning
 *
 * ## Usage
 *
 * ```kotlin
 * val analyzer: ScreenAnalyzer = ScreenAnalyzerImpl(samplingSource, model)
 * val analysis = analyzer.analyze(
 *   context = RecommendationContext(
 *     objective = "Tap the login button",
 *     progressSummary = "Launched app, currently on welcome screen",
 *   ),
 *   screenState = currentScreenState,
 *   availableTools = listOf(tapTool, swipeTool, inputTextTool),
 * )
 *
 * // Use analysis.recommendedTool and analysis.recommendedArgs
 * ```
 *
 * @see ScreenAnalysis The rich feedback returned by analysis
 * @see RecommendationContext The context passed from outer agent
 */
interface ScreenAnalyzer {

  /**
   * Analyzes the current screen and returns a recommendation for the next action.
   *
   * This is a single LLM call with vision. The analysis includes:
   * - Recommended tool and arguments based on current screen state
   * - Screen summary and progress indicators
   * - Confidence level and potential blockers
   * - Whether the objective appears achieved or impossible
   *
   * @param context Context from the outer agent including objective and progress summary
   * @param screenState Current screen state including screenshot and view hierarchy
   * @param traceId Optional trace ID for correlating this analysis with subsequent tool executions
   * @param availableTools Tools the analyzer can recommend. If empty, a default set is used.
   * @return Analysis result with recommendation and rich feedback
   */
  suspend fun analyze(
    context: RecommendationContext,
    screenState: ScreenState,
    traceId: TraceId? = null,
    availableTools: List<TrailblazeToolDescriptor> = emptyList(),
  ): ScreenAnalysis
}
