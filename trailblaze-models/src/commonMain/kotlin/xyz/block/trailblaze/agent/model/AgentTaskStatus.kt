package xyz.block.trailblaze.agent.model

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.logs.model.HasAgentTaskStatusData

@Serializable
sealed interface AgentTaskStatus : HasAgentTaskStatusData {

  @Serializable
  data class InProgress(
    override val statusData: AgentTaskStatusData,
  ) : AgentTaskStatus

  @Serializable
  sealed interface Success : AgentTaskStatus {

    @Serializable
    data class ObjectiveComplete(
      override val statusData: AgentTaskStatusData,
      val llmExplanation: String,
    ) : Success
  }

  @Serializable
  sealed interface Failure : AgentTaskStatus {
    @Serializable
    data class ObjectiveFailed(
      override val statusData: AgentTaskStatusData,
      val llmExplanation: String,
    ) : Failure

    @Serializable
    data class MaxCallsLimitReached(
      override val statusData: AgentTaskStatusData,
    ) : Failure
  }

  /**
   * Status for MCP inner-agent screen analysis.
   *
   * Used when the MCP tools (step, verify, ask) call the inner screen analyzer.
   * This enables emitting TrailblazeLlmRequestLog from the two-tier agent flow.
   */
  @Serializable
  data class McpScreenAnalysis(
    override val statusData: AgentTaskStatusData,
    /** The recommended action from screen analysis */
    val recommendedAction: String? = null,
    /** Confidence level of the analysis */
    val confidence: String? = null,
  ) : AgentTaskStatus
}
