package xyz.block.trailblaze.logs.client

import ai.koog.prompt.message.Message
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.api.MaestroDriverActionType
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.logs.model.AgentLogEventType
import xyz.block.trailblaze.logs.model.HasAgentTaskStatus
import xyz.block.trailblaze.logs.model.HasDuration
import xyz.block.trailblaze.logs.model.HasLlmResponseId
import xyz.block.trailblaze.logs.model.HasScreenshot
import xyz.block.trailblaze.logs.model.HasTrailblazeTool
import xyz.block.trailblaze.logs.model.LlmMessage
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.yaml.PromptStep

@Serializable
sealed interface TrailblazeLog {
  val session: String
  val timestamp: Instant
  val type: AgentLogEventType

  @Serializable
  data class TrailblazeAgentTaskStatusChangeLog(
    override val agentTaskStatus: AgentTaskStatus,
    override val durationMs: Long = agentTaskStatus.statusData.totalDurationMs,
    override val session: String,
    override val timestamp: Instant,
  ) : TrailblazeLog,
    HasAgentTaskStatus,
    HasDuration {
    override val type: AgentLogEventType = AgentLogEventType.AGENT_TASK_STATUS
  }

  @Serializable
  data class TrailblazeSessionStatusChangeLog(
    val sessionStatus: SessionStatus,
    override val session: String,
    override val timestamp: Instant,
  ) : TrailblazeLog {
    override val type: AgentLogEventType = AgentLogEventType.SESSION_STATUS
  }

  @Serializable
  data class TrailblazeLlmRequestLog(
    override val agentTaskStatus: AgentTaskStatus,
    val viewHierarchy: ViewHierarchyTreeNode,
    val instructions: String,
    val llmModelId: String,
    val llmMessages: List<LlmMessage>,
    val llmResponse: List<Message.Response>,
    val actions: List<Action>,
    override val screenshotFile: String?,
    override val durationMs: Long,
    override val session: String,
    override val timestamp: Instant,
    override val llmResponseId: String,
    override val deviceHeight: Int,
    override val deviceWidth: Int,
  ) : TrailblazeLog,
    HasAgentTaskStatus,
    HasLlmResponseId,
    HasScreenshot,
    HasDuration {
    override val type: AgentLogEventType = AgentLogEventType.LLM_REQUEST

    @Serializable
    data class Action(
      val name: String,
      val args: JsonObject,
    )
  }

  @Serializable
  data class MaestroCommandLog(
    val maestroCommandJsonObj: JsonObject,
    override val llmResponseId: String?,
    val successful: Boolean,
    val trailblazeToolResult: TrailblazeToolResult,
    override val session: String,
    override val timestamp: Instant,
    override val durationMs: Long,
  ) : TrailblazeLog,
    HasLlmResponseId,
    HasDuration {
    override val type: AgentLogEventType = AgentLogEventType.MAESTRO_COMMAND
  }

  @Serializable
  data class MaestroDriverLog(
    val viewHierarchy: ViewHierarchyTreeNode?,
    override val screenshotFile: String?,
    val action: MaestroDriverActionType,
    override val durationMs: Long,
    override val session: String,
    override val timestamp: Instant,
    override val deviceHeight: Int,
    override val deviceWidth: Int,
  ) : TrailblazeLog,
    HasScreenshot,
    HasDuration {
    override val type: AgentLogEventType = AgentLogEventType.MAESTRO_DRIVER
  }

  @Serializable
  data class DelegatingTrailblazeToolLog(
    val toolName: String,
    override val command: TrailblazeTool,
    override val session: String,
    override val timestamp: Instant,
    override val llmResponseId: String?,
    val executableTools: List<TrailblazeTool>,
  ) : TrailblazeLog,
    HasLlmResponseId,
    HasTrailblazeTool {
    override val type: AgentLogEventType = AgentLogEventType.DELEGATING_TRAILBLAZE_TOOL
  }

  @Serializable
  data class TrailblazeToolLog(
    override val command: TrailblazeTool,
    val toolName: String,
    val successful: Boolean,
    override val llmResponseId: String?,
    val exceptionMessage: String? = null,
    override val durationMs: Long,
    override val session: String,
    override val timestamp: Instant,
  ) : TrailblazeLog,
    HasTrailblazeTool,
    HasLlmResponseId,
    HasDuration {
    override val type: AgentLogEventType = AgentLogEventType.TRAILBLAZE_COMMAND
  }

  @Serializable
  data class ObjectiveStartLog(
    val promptStep: PromptStep,
    override val session: String,
    override val timestamp: Instant,
  ) : TrailblazeLog {
    override val type: AgentLogEventType = AgentLogEventType.OBJECTIVE_START
  }

  @Serializable
  data class ObjectiveCompleteLog(
    val promptStep: PromptStep,
    val objectiveResult: AgentTaskStatus,
    override val session: String,
    override val timestamp: Instant,
  ) : TrailblazeLog {
    override val type: AgentLogEventType = AgentLogEventType.OBJECTIVE_COMPLETE
  }
}
