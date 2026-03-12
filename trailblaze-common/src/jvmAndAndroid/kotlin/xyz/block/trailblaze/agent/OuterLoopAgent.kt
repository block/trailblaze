package xyz.block.trailblaze.agent

import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.llm.AgentTierCostTracker
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.logs.model.TraceId.Companion.TraceOrigin

/**
 * Two-tier Koog strategist agent that separates screen analysis from planning.
 *
 * This agent implements the outer layer of the two-tier architecture:
 * - **Inner Agent**: Cheap vision model for screen analysis (via [ScreenAnalyzer])
 * - **Outer Agent (this)**: Expensive reasoning model for planning/coordination
 *
 * ## Architecture
 *
 * ```
 * OuterLoopAgent (Outer - GPT-4o/Claude Sonnet)
 *   │
 *   ├── getScreenRecommendation() → ScreenAnalyzer (Inner - GPT-4o-mini)
 *   │                                   └── Returns ScreenAnalysis
 *   │
 *   ├── executeUiAction() → UiActionExecutor
 *   │                         └── Executes on device
 *   │
 *   ├── completeObjective() → Signals success
 *   │
 *   └── failObjective() → Signals failure
 * ```
 *
 * ## Flow
 *
 * 1. Outer agent receives objective
 * 2. Calls `getScreenRecommendation()` to get inner agent's analysis
 * 3. Reviews recommendation, decides whether to:
 *    - Execute the recommended action
 *    - Request new analysis with a hint
 *    - Complete the objective
 *    - Fail the objective
 * 4. Repeat until complete or max iterations
 *
 * ## Cost Optimization
 *
 * The inner agent (screen analysis) uses a cheap model and handles the
 * repetitive vision work. The outer agent uses an expensive model but
 * makes fewer calls, focusing on high-level decisions.
 *
 * @param innerAgent Screen analyzer (inner agent) for getting recommendations
 * @param executor UI action executor for interacting with the device
 * @param outerModel LLM model for the outer agent (planning/reasoning)
 * @param strategy Decision-making strategy for the outer agent
 * @param maxIterations Maximum iterations before giving up
 * @param costTracker Optional tracker for per-tier cost analysis
 *
 * @see ScreenAnalyzer
 * @see UiActionExecutor
 * @see OuterAgentStrategy
 * @see TwoTierAgentConfig
 */
class OuterLoopAgent(
  private val innerAgent: ScreenAnalyzer,
  private val executor: UiActionExecutor,
  private val outerModel: TrailblazeLlmModel,
  private val strategy: OuterAgentStrategy = DefaultOuterStrategy(),
  private val maxIterations: Int = MAX_ITERATIONS,
  private val costTracker: AgentTierCostTracker? = null,
) {

  companion object {
    /** Maximum number of iterations before giving up */
    const val MAX_ITERATIONS = 50
  }

  /**
   * Runs the agent to accomplish the given objective.
   *
   * Uses the two-tier pattern:
   * 1. Inner agent analyzes screen and recommends action
   * 2. Outer agent decides whether to execute, retry, or complete
   *
   * @param objective The task to accomplish (e.g., "Log in with test credentials")
   * @return Result describing success/failure and actions taken
   */
  suspend fun run(objective: String): StrategistResult {
    val actions = mutableListOf<AgentAction>()
    var iteration = 0
    var progressSummary: String? = null
    var hint: String? = null

    while (iteration < maxIterations) {
      iteration++

      // Generate trace ID for this iteration - correlates LLM analysis with tool execution
      val traceId = TraceId.generate(TraceOrigin.LLM)

      // 1. Capture current screen state
      val screenState = executor.captureScreenState()
        ?: return StrategistResult.Error(
          message = "Failed to capture screen state at iteration $iteration",
          iterations = iteration,
          actionsTaken = actions,
        )

      // 2. Get inner agent's analysis (passing trace ID for correlation)
      val context = RecommendationContext(
        objective = objective,
        progressSummary = progressSummary,
        hint = hint,
        attemptNumber = iteration,
      )

      val screenSummaryBefore = describeScreenState(screenState)
      val analysis = innerAgent.analyze(context, screenState, traceId)

      // 3. Check if objective is already achieved
      if (analysis.objectiveAppearsAchieved) {
        return StrategistResult.Success(
          summary = "Objective achieved: ${analysis.screenSummary}",
          iterations = iteration,
          actionsTaken = actions,
        )
      }

      // 4. Check if objective is impossible
      if (analysis.objectiveAppearsImpossible) {
        return StrategistResult.Failed(
          reason = "Objective impossible: ${analysis.screenSummary}",
          iterations = iteration,
          actionsTaken = actions,
        )
      }

      // 5. Let the strategy decide what to do
      val decision = strategy.decide(
        objective = objective,
        analysisResult = analysis,
        history = actions,
      )

      // 6. Execute the decision
      when (decision) {
        is OuterAgentDecision.Execute -> {
          // Execute the UI action (passing trace ID for correlation with LLM request)
          val result = executor.execute(
            toolName = decision.tool,
            args = decision.args,
            traceId = traceId,
          )

          // Record action with result
          val action = AgentAction(
            tool = decision.tool,
            args = decision.args,
            result = result,
            screenSummaryBefore = screenSummaryBefore,
            screenSummaryAfter = when (result) {
              is ExecutionResult.Success -> result.screenSummaryAfter
              is ExecutionResult.Failure -> null
            },
          )
          actions.add(action)

          when (result) {
            is ExecutionResult.Success -> {
              // Update progress for next iteration
              progressSummary = result.screenSummaryAfter
              hint = null // Clear any previous hint
            }
            is ExecutionResult.Failure -> {
              if (!result.recoverable) {
                return StrategistResult.Failed(
                  reason = "Unrecoverable error: ${result.error}",
                  iterations = iteration,
                  actionsTaken = actions,
                )
              }
              // Recoverable error - continue with hint
              hint = "Previous action failed: ${result.error}"
            }
          }
        }

        is OuterAgentDecision.RequestNewAnalysis -> {
          // Set hint for next inner agent call
          hint = decision.hint
          // Don't record an action - just retry analysis
        }

        is OuterAgentDecision.Complete -> {
          return StrategistResult.Success(
            summary = decision.summary,
            iterations = iteration,
            actionsTaken = actions,
          )
        }

        is OuterAgentDecision.Fail -> {
          return StrategistResult.Failed(
            reason = decision.reason,
            iterations = iteration,
            actionsTaken = actions,
          )
        }
      }
    }

    return StrategistResult.Error(
      message = "Max iterations ($maxIterations) reached without completing objective",
      iterations = maxIterations,
      actionsTaken = actions,
    )
  }

  /**
   * Creates a brief description of the current screen state for logging.
   */
  private fun describeScreenState(screenState: ScreenState): String {
    return "Screen ${screenState.deviceWidth}x${screenState.deviceHeight} on ${screenState.trailblazeDevicePlatform}"
  }
}

/**
 * Result of running the OuterLoopAgent.
 */
sealed interface StrategistResult {
  val iterations: Int
  val actionsTaken: List<AgentAction>

  /**
   * Objective was successfully completed.
   */
  data class Success(
    val summary: String,
    override val iterations: Int,
    override val actionsTaken: List<AgentAction>,
  ) : StrategistResult

  /**
   * Objective could not be completed (but execution was controlled).
   */
  data class Failed(
    val reason: String,
    override val iterations: Int,
    override val actionsTaken: List<AgentAction>,
  ) : StrategistResult

  /**
   * An error occurred during execution.
   */
  data class Error(
    val message: String,
    override val iterations: Int,
    override val actionsTaken: List<AgentAction> = emptyList(),
  ) : StrategistResult
}
