package xyz.block.trailblaze.ui.tabs.session

import kotlinx.datetime.Instant
import xyz.block.trailblaze.api.AgentActionType
import xyz.block.trailblaze.api.AgentDriverAction
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.toolcalls.TrailblazeTool

internal data class ScreenshotTimelineItem(
  val timestamp: Instant,
  val screenshotFile: String?,
  val deviceWidth: Int,
  val deviceHeight: Int,
  val label: String,
  val action: AgentDriverAction?,
  val clickX: Int?,
  val clickY: Int?,
  val sourceLog: TrailblazeLog? = null,
  val toolCallName: String? = null,
  val trailblazeTool: TrailblazeTool? = null,
)

internal data class ObjectiveProgress(
  val prompt: String,
  val startedAt: Instant?,
  val completedAt: Instant?,
  val status: ObjectiveStatus,
  val llmExplanation: String? = null,
  val toolCallCount: Int = 0,
)

internal enum class ObjectiveStatus(val label: String, val isTerminal: Boolean) {
  Pending("Pending", false),
  InProgress("In progress", false),
  Succeeded("Succeeded", true),
  Failed("Failed", true),
}

internal sealed interface ProgressItem {
  val startedAt: Instant?
  val completedAt: Instant?

  data class ObjectiveItem(
    val objective: ObjectiveProgress,
    val stepNumber: Int,
  ) : ProgressItem {
    override val startedAt get() = objective.startedAt
    override val completedAt get() = objective.completedAt
  }

  data class ToolBlockItem(
    val toolLogs: List<TrailblazeLog.TrailblazeToolLog>,
    override val startedAt: Instant?,
    override val completedAt: Instant?,
  ) : ProgressItem
}

internal enum class TickType {
  Screenshot,
  ToolCall,
  LlmRequest,
  DriverAction,
}

internal data class TimelineTick(val offsetFraction: Float, val type: TickType)

/** A prominent clickable marker on the timeline bar. Clicking jumps to the event. */
internal data class EventMarker(
  val timestampMs: Long,
  val durationMs: Long,
  val offsetFraction: Float,
  val endFraction: Float,
  val type: TickType,
  val label: String,
  val actionKind: ActionKind,
)

/** Visual category for marker color coding. */
internal enum class ActionKind {
  Tap,
  Swipe,
  Assert,
  Input,
  Navigation,
  Tool,
  Screenshot,
}

/** Maps an [AgentActionType] to its visual [ActionKind] category. */
internal fun AgentActionType.toActionKind(): ActionKind = when (this) {
  AgentActionType.TAP_POINT, AgentActionType.LONG_PRESS_POINT -> ActionKind.Tap
  AgentActionType.SWIPE, AgentActionType.SCROLL -> ActionKind.Swipe
  AgentActionType.ENTER_TEXT, AgentActionType.ERASE_TEXT -> ActionKind.Input
  AgentActionType.ASSERT_CONDITION -> ActionKind.Assert
  else -> ActionKind.Navigation
}
