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
}
