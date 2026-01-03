package xyz.block.trailblaze.logs.client

import ai.koog.prompt.message.Message
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.agent.model.PromptRecordingResult
import xyz.block.trailblaze.api.MaestroDriverActionType
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.llm.LlmRequestUsageAndCost
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor
import xyz.block.trailblaze.logs.model.HasAgentTaskStatus
import xyz.block.trailblaze.logs.model.HasDuration
import xyz.block.trailblaze.logs.model.HasPromptStep
import xyz.block.trailblaze.logs.model.HasScreenshot
import xyz.block.trailblaze.logs.model.HasTraceId
import xyz.block.trailblaze.logs.model.HasTrailblazeTool
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.logs.model.TrailblazeLlmMessage
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.yaml.PromptStep

@Serializable
sealed interface TrailblazeLog {
  val session: SessionId
  val timestamp: Instant

  @Serializable
  data class TrailblazeAgentTaskStatusChangeLog(
    override val agentTaskStatus: AgentTaskStatus,
    override val durationMs: Long = agentTaskStatus.statusData.totalDurationMs,
    override val session: SessionId,
    override val timestamp: Instant,
  ) : TrailblazeLog,
    HasAgentTaskStatus,
    HasDuration

  @Serializable
  data class TrailblazeSessionStatusChangeLog(
    val sessionStatus: SessionStatus,
    override val session: SessionId,
    override val timestamp: Instant,
  ) : TrailblazeLog

  @Serializable
  data class TrailblazeLlmRequestLog(
    override val agentTaskStatus: AgentTaskStatus,
    val viewHierarchy: ViewHierarchyTreeNode,
    val viewHierarchyFiltered: ViewHierarchyTreeNode? = null,
    val instructions: String,
    val trailblazeLlmModel: TrailblazeLlmModel,
    val llmMessages: List<TrailblazeLlmMessage>,
    val llmResponse: List<Message.Response>,
    val actions: List<Action>,
    val toolOptions: List<TrailblazeToolDescriptor>,
    val llmRequestUsageAndCost: LlmRequestUsageAndCost? = null,
    override val screenshotFile: String?,
    override val durationMs: Long,
    override val session: SessionId,
    override val timestamp: Instant,
    override val traceId: TraceId,
    override val deviceHeight: Int,
    override val deviceWidth: Int,
  ) : TrailblazeLog,
    HasAgentTaskStatus,
    HasTraceId,
    HasScreenshot,
    HasDuration {

    @Serializable
    data class Action(
      val name: String,
      val args: JsonObject,
    )
  }

  @Serializable
  data class MaestroCommandLog(
    val maestroCommandJsonObj: JsonObject,
    override val traceId: TraceId?,
    val successful: Boolean,
    val trailblazeToolResult: TrailblazeToolResult,
    override val session: SessionId,
    override val timestamp: Instant,
    override val durationMs: Long,
  ) : TrailblazeLog,
    HasTraceId,
    HasDuration

  @Serializable
  data class MaestroDriverLog(
    val viewHierarchy: ViewHierarchyTreeNode?,
    override val screenshotFile: String?,
    val action: MaestroDriverActionType,
    override val durationMs: Long,
    override val session: SessionId,
    override val timestamp: Instant,
    override val deviceHeight: Int,
    override val deviceWidth: Int,
  ) : TrailblazeLog,
    HasScreenshot,
    HasDuration

  @Serializable
  data class DelegatingTrailblazeToolLog(
    val toolName: String,
    override val trailblazeTool: TrailblazeTool,
    override val session: SessionId,
    override val timestamp: Instant,
    override val traceId: TraceId?,
    val executableTools: List<TrailblazeTool>,
  ) : TrailblazeLog,
    HasTraceId,
    HasTrailblazeTool

  @Serializable
  data class TrailblazeToolLog(
    override val trailblazeTool: TrailblazeTool,
    val toolName: String,
    val successful: Boolean,
    override val traceId: TraceId?,
    val exceptionMessage: String? = null,
    override val durationMs: Long,
    override val session: SessionId,
    override val timestamp: Instant,
  ) : TrailblazeLog,
    HasTrailblazeTool,
    HasTraceId,
    HasDuration

  @Serializable
  data class ObjectiveStartLog(
    override val promptStep: PromptStep,
    override val session: SessionId,
    override val timestamp: Instant,
  ) : TrailblazeLog,
    HasPromptStep

  @Serializable
  data class ObjectiveCompleteLog(
    override val promptStep: PromptStep,
    val objectiveResult: AgentTaskStatus,
    override val session: SessionId,
    override val timestamp: Instant,
  ) : TrailblazeLog,
    HasPromptStep

  @Serializable
  data class AttemptAiFallbackLog(
    override val promptStep: PromptStep,
    override val session: SessionId,
    override val timestamp: Instant,
    val recordingResult: PromptRecordingResult.Failure,
  ) : TrailblazeLog,
    HasPromptStep
}
