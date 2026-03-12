package xyz.block.trailblaze.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Strategy interface for outer agent decision making.
 *
 * The outer agent strategy determines what to do after receiving analysis
 * from the inner agent ([ScreenAnalyzer]). This follows the Strategy pattern
 * to allow different decision-making approaches:
 *
 * - Default strategy: Trust high-confidence recommendations
 * - Conservative strategy: Always verify before executing
 * - Learning strategy: Adjust based on past outcomes
 *
 * ## Design
 *
 * The strategy receives:
 * - The original objective
 * - Analysis result from the inner agent
 * - History of actions taken so far
 *
 * And returns a decision about what to do next.
 *
 * ## Usage
 *
 * ```kotlin
 * val strategy: OuterAgentStrategy = DefaultOuterStrategy()
 *
 * val decision = strategy.decide(
 *   objective = "Login to the app",
 *   analysisResult = screenAnalysis,
 *   history = actionsSoFar,
 * )
 *
 * when (decision) {
 *   is OuterAgentDecision.Execute -> executor.execute(decision.tool, decision.args)
 *   is OuterAgentDecision.RequestNewAnalysis -> analyzer.analyze(contextWithHint)
 *   is OuterAgentDecision.Complete -> return success(decision.summary)
 *   is OuterAgentDecision.Fail -> return failure(decision.reason)
 * }
 * ```
 *
 * @see OuterAgentDecision The possible decisions
 * @see ScreenAnalysis The analysis input from inner agent
 */
interface OuterAgentStrategy {

  /**
   * Decides what to do given the current state.
   *
   * @param objective The original objective to accomplish
   * @param analysisResult Analysis from the inner agent ([ScreenAnalyzer])
   * @param history List of actions taken so far in this session
   * @return Decision about what to do next
   */
  suspend fun decide(
    objective: String,
    analysisResult: ScreenAnalysis,
    history: List<AgentAction>,
  ): OuterAgentDecision
}

/**
 * Decision made by the outer agent strategy.
 *
 * Represents the four possible outcomes from strategy evaluation:
 * - Execute the recommended action
 * - Request a new analysis with additional hints
 * - Complete the objective (success)
 * - Fail the objective (cannot continue)
 */
@Serializable
sealed class OuterAgentDecision {

  /**
   * Execute a UI action.
   *
   * The outer agent has decided to proceed with an action, either the
   * recommended one from the inner agent or a modified version.
   *
   * @property tool The tool name to execute
   * @property args The tool arguments
   */
  @Serializable
  data class Execute(
    val tool: String,
    val args: JsonObject,
  ) : OuterAgentDecision()

  /**
   * Request a new analysis from the inner agent.
   *
   * Used when the current analysis has low confidence or the outer agent
   * wants to provide additional context for a better recommendation.
   *
   * @property hint Additional guidance for the inner agent
   */
  @Serializable
  data class RequestNewAnalysis(
    val hint: String,
  ) : OuterAgentDecision()

  /**
   * Complete the objective successfully.
   *
   * The outer agent has determined that the objective is accomplished.
   *
   * @property summary Human-readable summary of what was done
   */
  @Serializable
  data class Complete(
    val summary: String,
  ) : OuterAgentDecision()

  /**
   * Fail the objective.
   *
   * The outer agent has determined that the objective cannot be completed.
   *
   * @property reason Human-readable explanation of why it failed
   */
  @Serializable
  data class Fail(
    val reason: String,
  ) : OuterAgentDecision()
}

/**
 * Record of an action taken by the agent.
 *
 * Used to track history for the outer agent strategy, enabling
 * loop detection and context-aware decisions.
 *
 * @property tool The tool that was executed
 * @property args The arguments passed to the tool
 * @property result The result of execution (success/failure)
 * @property screenSummaryBefore Summary of screen state before action
 * @property screenSummaryAfter Summary of screen state after action (if successful)
 */
@Serializable
data class AgentAction(
  val tool: String,
  val args: JsonObject,
  val result: ExecutionResult,
  val screenSummaryBefore: String,
  val screenSummaryAfter: String?,
)
