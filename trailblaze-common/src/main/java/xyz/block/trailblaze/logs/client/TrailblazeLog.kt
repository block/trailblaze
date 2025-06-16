package xyz.block.trailblaze.logs.client

import ai.koog.prompt.message.Message
import kotlinx.datetime.Instant
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import maestro.orchestra.MaestroCommand
import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.api.MaestroDriverActionType
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.logs.model.AgentLogEventType
import xyz.block.trailblaze.logs.model.HasAgentTaskStatus
import xyz.block.trailblaze.logs.model.HasDuration
import xyz.block.trailblaze.logs.model.HasScreenshot
import xyz.block.trailblaze.logs.model.HasTrailblazeTool
import xyz.block.trailblaze.logs.model.LlmMessage
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.maestro.MaestroCommandToYamlSerializer
import xyz.block.trailblaze.serializers.TrailblazeToolToCodeSerializer
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

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
    val llmResponseId: String,
    override val deviceHeight: Int,
    override val deviceWidth: Int,
  ) : TrailblazeLog,
    HasAgentTaskStatus,
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
    @Contextual val maestroCommand: MaestroCommand,
    val llmResponseId: String?,
    val successful: Boolean,
    val trailblazeToolResult: TrailblazeToolResult,
    override val session: String,
    override val timestamp: Instant,
    override val durationMs: Long,
  ) : TrailblazeLog,
    HasDuration {
    override val type: AgentLogEventType = AgentLogEventType.MAESTRO_COMMAND
    fun asMaestroYaml(): String = MaestroCommandToYamlSerializer.toYaml(listOf(maestroCommand.asCommand()!!), false)
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

    fun debugString(): String = buildString {
      appendLine(TrailblazeJsonInstance.encodeToString(action))
    }
  }

  @Serializable
  data class DelegatingTrailblazeToolLog(
    override val command: TrailblazeTool,
    override val session: String,
    override val timestamp: Instant,
    val executableTools: List<TrailblazeTool>,
  ) : TrailblazeLog,
    HasTrailblazeTool {
    override val type: AgentLogEventType = AgentLogEventType.DELEGATING_TRAILBLAZE_TOOL

    fun asCommandJson(): String = buildString {
      appendLine(TrailblazeToolToCodeSerializer().serializeTrailblazeToolToCode(command))
      appendLine()
      appendLine("Delegated to:")
      executableTools.forEach { executableTool ->
        appendLine(TrailblazeToolToCodeSerializer().serializeTrailblazeToolToCode(executableTool))
      }
    }
  }

  @Serializable
  data class TrailblazeToolLog(
    override val command: TrailblazeTool,
    val toolName: String,
    val successful: Boolean,
    val llmResponseId: String?,
    val exceptionMessage: String? = null,
    override val durationMs: Long,
    override val session: String,
    override val timestamp: Instant,
  ) : TrailblazeLog,
    HasTrailblazeTool,
    HasDuration {
    override val type: AgentLogEventType = AgentLogEventType.TRAILBLAZE_COMMAND

    fun asCommandJson(): String = buildString {
      appendLine(TrailblazeToolToCodeSerializer().serializeTrailblazeToolToCode(command))
    }
  }

  @Serializable
  data class ObjectiveStartLog(
    val description: String,
    override val session: String,
    override val timestamp: Instant,
  ) : TrailblazeLog {
    override val type: AgentLogEventType = AgentLogEventType.OBJECTIVE_START
  }

  @Serializable
  data class ObjectiveCompleteLog(
    val description: String,
    val objectiveResult: AgentTaskStatus,
    override val session: String,
    override val timestamp: Instant,
  ) : TrailblazeLog {
    override val type: AgentLogEventType = AgentLogEventType.OBJECTIVE_COMPLETE
  }

  @Serializable
  data class TopLevelMaestroCommandLog(
    val command: String,
    override val session: String,
    override val timestamp: Instant,
  ) : TrailblazeLog {
    override val type: AgentLogEventType = AgentLogEventType.TOP_LEVEL_MAESTRO_COMMAND
  }
}
