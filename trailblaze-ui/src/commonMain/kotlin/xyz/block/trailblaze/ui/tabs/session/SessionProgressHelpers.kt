package xyz.block.trailblaze.ui.tabs.session

import kotlinx.datetime.Instant
import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.api.AgentActionType
import xyz.block.trailblaze.api.HasClickCoordinates
import xyz.block.trailblaze.api.AgentDriverAction
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.logs.model.isInProgress
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.ui.utils.FormattingUtils.formatDuration
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.TrailblazeYaml

internal fun buildObjectiveProgress(logs: List<TrailblazeLog>): List<ObjectiveProgress> {
  val objectives = mutableListOf<ObjectiveProgress>()
  val objectiveLogs =
    logs
      .filter { it is TrailblazeLog.ObjectiveStartLog || it is TrailblazeLog.ObjectiveCompleteLog }
      .sortedBy { it.timestamp }

  objectiveLogs.forEach { log ->
    when (log) {
      is TrailblazeLog.ObjectiveStartLog -> {
        objectives.add(
          ObjectiveProgress(
            prompt = log.promptStep.prompt,
            startedAt = log.timestamp,
            completedAt = null,
            status = ObjectiveStatus.InProgress,
          ),
        )
      }
      is TrailblazeLog.ObjectiveCompleteLog -> {
        val llmExplanation =
          when (val result = log.objectiveResult) {
            is AgentTaskStatus.Success.ObjectiveComplete -> result.llmExplanation
            is AgentTaskStatus.Failure.ObjectiveFailed -> result.llmExplanation
            else -> null
          }
        val status =
          when (log.objectiveResult) {
            is AgentTaskStatus.Success.ObjectiveComplete -> ObjectiveStatus.Succeeded
            is AgentTaskStatus.Failure.ObjectiveFailed,
            is AgentTaskStatus.Failure.MaxCallsLimitReached -> ObjectiveStatus.Failed
            is AgentTaskStatus.InProgress,
            is AgentTaskStatus.McpScreenAnalysis -> ObjectiveStatus.InProgress
          }
        val indexToUpdate =
          objectives.indexOfLast {
            it.prompt == log.promptStep.prompt && it.status == ObjectiveStatus.InProgress
          }
        if (indexToUpdate >= 0) {
          objectives[indexToUpdate] =
            objectives[indexToUpdate].copy(
              completedAt = log.timestamp,
              status = status,
              llmExplanation = llmExplanation,
            )
        } else {
          objectives.add(
            ObjectiveProgress(
              prompt = log.promptStep.prompt,
              startedAt = null,
              completedAt = log.timestamp,
              status = status,
              llmExplanation = llmExplanation,
            ),
          )
        }
      }
      else -> Unit
    }
  }

  // Count tool calls per objective
  return objectives.map { objective ->
    val startMs = objective.startedAt?.toEpochMilliseconds()
    val endMs = objective.completedAt?.toEpochMilliseconds()
    val toolCallCount =
      logs.count { log ->
        isInTimeWindow(log.timestamp.toEpochMilliseconds(), startMs, endMs) &&
          (log is TrailblazeLog.TrailblazeToolLog ||
            log is TrailblazeLog.DelegatingTrailblazeToolLog)
      }
    objective.copy(toolCallCount = toolCallCount)
  }
}

internal fun buildProgressItems(logs: List<TrailblazeLog>): List<ProgressItem> {
  val objectives = buildObjectiveProgress(logs)
  if (objectives.isEmpty()) {
    // No objectives at all — treat all recordable tool logs as a single tool block
    val toolLogs = logs.filterIsInstance<TrailblazeLog.TrailblazeToolLog>()
      .filter { it.isRecordable }
    if (toolLogs.isEmpty()) return emptyList()
    return listOf(
      ProgressItem.ToolBlockItem(
        toolLogs = toolLogs,
        startedAt = toolLogs.first().timestamp,
        completedAt = toolLogs.last().timestamp,
      ),
    )
  }

  val items = mutableListOf<ProgressItem>()
  var stepNumber = 1

  // Collect tool logs before the first objective
  val firstObjStart = objectives.first().startedAt
  if (firstObjStart != null) {
    val toolsBefore = logs.filterIsInstance<TrailblazeLog.TrailblazeToolLog>()
      .filter { it.isRecordable && it.timestamp < firstObjStart }
    if (toolsBefore.isNotEmpty()) {
      items.add(
        ProgressItem.ToolBlockItem(
          toolLogs = toolsBefore,
          startedAt = toolsBefore.first().timestamp,
          completedAt = toolsBefore.last().timestamp,
        ),
      )
    }
  }

  objectives.forEachIndexed { index, objective ->
    items.add(ProgressItem.ObjectiveItem(objective = objective, stepNumber = stepNumber))
    stepNumber++

    // Collect tool logs between this objective and the next
    val thisEnd = objective.completedAt
    val nextStart = objectives.getOrNull(index + 1)?.startedAt
    if (thisEnd != null) {
      val toolsBetween = logs.filterIsInstance<TrailblazeLog.TrailblazeToolLog>()
        .filter { it.isRecordable }
        .filter { log ->
          val logMs = log.timestamp.toEpochMilliseconds()
          val afterThis = logMs > thisEnd.toEpochMilliseconds()
          val beforeNext = nextStart == null ||
            logMs < nextStart.toEpochMilliseconds()
          afterThis && beforeNext
        }
      if (toolsBetween.isNotEmpty()) {
        items.add(
          ProgressItem.ToolBlockItem(
            toolLogs = toolsBetween,
            startedAt = toolsBetween.first().timestamp,
            completedAt = toolsBetween.last().timestamp,
          ),
        )
      }
    }
  }

  return items
}

/**
 * When a session has ended, marks any still-InProgress objectives as Failed and
 * populates their [ObjectiveProgress.llmExplanation] from the session failure reason.
 */
internal fun patchProgressItemsForSessionEnd(
  items: List<ProgressItem>,
  overallStatus: SessionStatus?,
): List<ProgressItem> {
  if (overallStatus?.isInProgress != false) return items
  val sessionFailureReason = extractSessionFailureReason(overallStatus)
  return items.map { item ->
    when (item) {
      is ProgressItem.ObjectiveItem -> {
        if (item.objective.status == ObjectiveStatus.InProgress) {
          item.copy(
            objective =
              item.objective.copy(
                status = ObjectiveStatus.Failed,
                llmExplanation = item.objective.llmExplanation ?: sessionFailureReason,
              ),
          )
        } else {
          item
        }
      }
      is ProgressItem.ToolBlockItem -> item
    }
  }
}

/** Extracts a human-readable failure message from a terminal session status. */
internal fun extractSessionFailureReason(status: SessionStatus): String? {
  return when (status) {
    is SessionStatus.Ended.Failed ->
      status.exceptionMessage?.let { cleanExceptionMessage(it) }
    is SessionStatus.Ended.FailedWithFallback ->
      status.exceptionMessage?.let { cleanExceptionMessage(it) }
    is SessionStatus.Ended.TimeoutReached -> status.message
    is SessionStatus.Ended.MaxCallsLimitReached ->
      "Max calls limit reached (${status.maxCalls}) for: ${status.objectivePrompt}"
    is SessionStatus.Ended.Cancelled -> status.cancellationMessage
    else -> null
  }
}

/** Check whether a timestamp falls within an optional start/end window. */
private fun isInTimeWindow(timestampMs: Long, startMs: Long?, endMs: Long?): Boolean {
  return when {
    startMs == null && endMs == null -> false
    startMs == null -> endMs?.let { timestampMs <= it } ?: false
    endMs == null -> timestampMs >= startMs
    else -> timestampMs in startMs..endMs
  }
}

private fun buildScreenshotTimelineItems(
  logs: List<TrailblazeLog>,
): List<ScreenshotTimelineItem> {
  // Build traceId → (toolName, trailblazeTool) lookup from tool logs
  data class ToolCallInfo(val toolName: String, val trailblazeTool: TrailblazeTool?)
  val toolCallByTraceId = mutableMapOf<String, ToolCallInfo>()
  for (log in logs) {
    val traceId =
      when (log) {
        is TrailblazeLog.TrailblazeToolLog -> log.traceId?.traceId
        is TrailblazeLog.DelegatingTrailblazeToolLog -> log.traceId?.traceId
        else -> null
      }
    val toolName =
      when (log) {
        is TrailblazeLog.TrailblazeToolLog -> log.toolName
        is TrailblazeLog.DelegatingTrailblazeToolLog -> log.toolName
        else -> null
      }
    val tool =
      when (log) {
        is TrailblazeLog.TrailblazeToolLog -> log.trailblazeTool
        is TrailblazeLog.DelegatingTrailblazeToolLog -> log.trailblazeTool
        else -> null
      }
    if (traceId != null && toolName != null) {
      toolCallByTraceId[traceId] = ToolCallInfo(toolName, tool)
    }
  }

  return logs
    .mapNotNull { log ->
      when (log) {
        is TrailblazeLog.TrailblazeLlmRequestLog -> {
          val screenshotFile = log.screenshotFile ?: return@mapNotNull null
          ScreenshotTimelineItem(
            timestamp = log.timestamp,
            screenshotFile = screenshotFile,
            deviceWidth = log.deviceWidth,
            deviceHeight = log.deviceHeight,
            label = "LLM request",
            action = null,
            clickX = null,
            clickY = null,
            sourceLog = log,
            toolCallName = log.traceId?.traceId?.let { toolCallByTraceId[it]?.toolName },
            trailblazeTool = log.traceId?.traceId?.let { toolCallByTraceId[it]?.trailblazeTool },
          )
        }
        is TrailblazeLog.AgentDriverLog -> {
          val screenshotFile = log.screenshotFile
          val action = log.action
          val (clickX, clickY) =
            if (action is HasClickCoordinates) {
              action.x to action.y
            } else {
              null to null
            }
          ScreenshotTimelineItem(
            timestamp = log.timestamp,
            screenshotFile = screenshotFile,
            deviceWidth = log.deviceWidth,
            deviceHeight = log.deviceHeight,
            label = "Driver: ${log.action.type.name}",
            action = action,
            clickX = clickX,
            clickY = clickY,
            sourceLog = log,
            toolCallName = log.traceId?.traceId?.let { toolCallByTraceId[it]?.toolName },
            trailblazeTool = log.traceId?.traceId?.let { toolCallByTraceId[it]?.trailblazeTool },
          )
        }
        is TrailblazeLog.TrailblazeSnapshotLog -> {
          ScreenshotTimelineItem(
            timestamp = log.timestamp,
            screenshotFile = log.screenshotFile,
            deviceWidth = log.deviceWidth,
            deviceHeight = log.deviceHeight,
            label = log.displayName ?: "Snapshot",
            action = null,
            clickX = null,
            clickY = null,
            sourceLog = log,
          )
        }
        is TrailblazeLog.TrailblazeToolLog -> {
          ScreenshotTimelineItem(
            timestamp = log.timestamp,
            screenshotFile = null,
            deviceWidth = 0,
            deviceHeight = 0,
            label = "Tool: ${log.toolName}",
            action = null,
            clickX = null,
            clickY = null,
            sourceLog = log,
            toolCallName = log.toolName,
            trailblazeTool = log.trailblazeTool,
          )
        }
        is TrailblazeLog.DelegatingTrailblazeToolLog -> {
          ScreenshotTimelineItem(
            timestamp = log.timestamp,
            screenshotFile = null,
            deviceWidth = 0,
            deviceHeight = 0,
            label = "Delegating: ${log.toolName}",
            action = null,
            clickX = null,
            clickY = null,
            sourceLog = log,
            toolCallName = log.toolName,
            trailblazeTool = log.trailblazeTool,
          )
        }
        else -> null
      }
    }
    .sortedBy { it.timestamp }
}

internal fun buildObjectiveScreenshotItems(
  logs: List<TrailblazeLog>,
  objective: ObjectiveProgress,
): List<ScreenshotTimelineItem> {
  val startMs = objective.startedAt?.toEpochMilliseconds()
  val endMs = objective.completedAt?.toEpochMilliseconds()
  return buildScreenshotTimelineItems(logs).filter { item ->
    isInTimeWindow(item.timestamp.toEpochMilliseconds(), startMs, endMs)
  }
}

internal fun buildProgressItemScreenshotItems(
  logs: List<TrailblazeLog>,
  item: ProgressItem,
): List<ScreenshotTimelineItem> {
  val startMs = item.startedAt?.toEpochMilliseconds()
  val endMs = item.completedAt?.toEpochMilliseconds()
  return buildScreenshotTimelineItems(logs).filter { screenshotItem ->
    isInTimeWindow(screenshotItem.timestamp.toEpochMilliseconds(), startMs, endMs)
  }
}

internal fun buildTimelineTicks(
  logs: List<TrailblazeLog>,
  sessionStartMs: Long,
  sessionEndMs: Long,
): List<TimelineTick> {
  val range = (sessionEndMs - sessionStartMs).coerceAtLeast(1L).toFloat()
  return logs.mapNotNull { log ->
    val fraction = (log.timestamp.toEpochMilliseconds() - sessionStartMs) / range
    if (fraction !in 0f..1f) return@mapNotNull null
    val type =
      when (log) {
        is TrailblazeLog.TrailblazeLlmRequestLog -> TickType.LlmRequest
        is TrailblazeLog.AgentDriverLog -> TickType.DriverAction
        is TrailblazeLog.TrailblazeSnapshotLog -> TickType.Screenshot
        is TrailblazeLog.TrailblazeToolLog,
        is TrailblazeLog.DelegatingTrailblazeToolLog -> TickType.ToolCall
        else -> return@mapNotNull null
      }
    TimelineTick(offsetFraction = fraction, type = type)
  }
}

/** Builds clickable event markers for DriverAction and ToolCall events. */
internal fun buildEventMarkers(
  logs: List<TrailblazeLog>,
  sessionStartMs: Long,
  sessionEndMs: Long,
): List<EventMarker> {
  val range = (sessionEndMs - sessionStartMs).coerceAtLeast(1L).toFloat()
  return logs.mapNotNull { log ->
    val ms = log.timestamp.toEpochMilliseconds()
    val fraction = (ms - sessionStartMs) / range
    if (fraction !in 0f..1f) return@mapNotNull null
    when (log) {
      is TrailblazeLog.AgentDriverLog -> {
        val label = log.action.type.displayLabel
        val kind = log.action.type.toActionKind()
        val dur = log.durationMs
        val endFrac = ((ms + dur - sessionStartMs) / range).coerceIn(0f, 1f)
        EventMarker(ms, dur, fraction, endFrac, TickType.DriverAction, label, kind)
      }
      is TrailblazeLog.TrailblazeToolLog -> {
        val dur = log.durationMs ?: 0L
        val endFrac = ((ms + dur - sessionStartMs) / range).coerceIn(0f, 1f)
        EventMarker(ms, dur, fraction, endFrac, TickType.ToolCall, log.toolName, ActionKind.Tool)
      }
      is TrailblazeLog.DelegatingTrailblazeToolLog -> {
        EventMarker(ms, 0L, fraction, fraction, TickType.ToolCall, log.toolName, ActionKind.Tool)
      }
      is TrailblazeLog.TrailblazeSnapshotLog -> {
        EventMarker(
          ms,
          0L,
          fraction,
          fraction,
          TickType.Screenshot,
          log.displayName ?: "Snapshot",
          ActionKind.Screenshot,
        )
      }
      else -> null
    }
  }
}

internal fun latestLogForObjective(
  logs: List<TrailblazeLog>,
  objective: ObjectiveProgress,
): TrailblazeLog? {
  val startMs = objective.startedAt?.toEpochMilliseconds() ?: return null
  val endMs = objective.completedAt?.toEpochMilliseconds()
  return logs.lastOrNull { log ->
    val logMs = log.timestamp.toEpochMilliseconds()
    when {
      endMs == null -> logMs >= startMs
      else -> logMs in startMs..endMs
    }
  }
}

internal fun latestActivityLabel(log: TrailblazeLog): String {
  return when (log) {
    is TrailblazeLog.TrailblazeLlmRequestLog -> "Thinking\u2026"
    is TrailblazeLog.TrailblazeToolLog -> "Running tool: ${log.toolName}"
    is TrailblazeLog.DelegatingTrailblazeToolLog -> "Delegating tool: ${log.toolName}"
    is TrailblazeLog.AgentDriverLog -> "Driver: ${log.action.type.name}"
    is TrailblazeLog.MaestroCommandLog -> "Executing Maestro command"
    is TrailblazeLog.ObjectiveStartLog ->
      "Starting: ${promptSummary(log.promptStep.prompt, 80)}"
    is TrailblazeLog.ObjectiveCompleteLog ->
      "Completed: ${promptSummary(log.promptStep.prompt, 80)}"
    is TrailblazeLog.TrailblazeSessionStatusChangeLog ->
      "Session: ${sessionStatusLabel(log.sessionStatus)}"
    is TrailblazeLog.TrailblazeAgentTaskStatusChangeLog -> "Agent task status update"
    is TrailblazeLog.AttemptAiFallbackLog -> "Attempting AI fallback"
    is TrailblazeLog.TrailblazeSnapshotLog -> "Captured snapshot"
    is TrailblazeLog.AccessibilityActionLog -> "Accessibility: ${log.actionDescription}"
    is TrailblazeLog.McpAgentRunLog -> "MCP agent run"
    is TrailblazeLog.McpAgentIterationLog -> "MCP agent iteration"
    is TrailblazeLog.McpAgentToolLog -> "MCP tool: ${log.toolName}"
    is TrailblazeLog.McpSamplingLog -> "MCP sampling request"
    is TrailblazeLog.McpToolCallRequestLog -> "MCP tool call: ${log.toolName}"
    is TrailblazeLog.McpToolCallResponseLog -> "MCP tool response: ${log.toolName}"
    is TrailblazeLog.TrailblazeProgressLog -> log.description
  }
}

internal fun buildStepStatusLine(
  objective: ObjectiveProgress,
  sessionStartTime: Instant,
): String {
  val statusText =
    when (objective.status) {
      ObjectiveStatus.Succeeded -> null
      else -> objective.status.label
    }
  val startElapsed =
    objective.startedAt?.let {
      formatDuration(it.toEpochMilliseconds() - sessionStartTime.toEpochMilliseconds())
    }
  val endElapsed =
    objective.completedAt?.let {
      formatDuration(it.toEpochMilliseconds() - sessionStartTime.toEpochMilliseconds())
    }
  val timePart =
    when {
      startElapsed != null && endElapsed != null -> "$startElapsed \u2192 $endElapsed"
      startElapsed != null -> "Started at $startElapsed"
      endElapsed != null -> "Completed at $endElapsed"
      else -> null
    }
  return listOfNotNull(statusText, timePart).joinToString(" \u2022 ")
}

internal fun buildStepSubtitle(
  objective: ObjectiveProgress,
  sessionStartTime: Instant,
): String {
  val statusLine = buildStepStatusLine(objective, sessionStartTime)
  val toolCount = objective.toolCallCount
  return if (toolCount > 0) {
    "$statusLine \u2022 $toolCount tool${if (toolCount != 1) "s" else ""}"
  } else {
    statusLine
  }
}

internal fun objectiveDurationMs(objective: ObjectiveProgress): Long? {
  val start = objective.startedAt?.toEpochMilliseconds() ?: return null
  val end = objective.completedAt?.toEpochMilliseconds() ?: return null
  return end - start
}

/**
 * Strips Java stack traces from an exception message, keeping only the human-readable error.
 */
internal fun cleanExceptionMessage(message: String): String {
  val lines = message.lines()
  // Find the first fully-qualified exception class line (e.g. "xyz.block...Exception: ...")
  // which is where the Java toString() repetition starts, and strip everything from there.
  val exceptionClassRegex = Regex("^[a-z][\\w.]*\\.[A-Z]\\w*(Exception|Error).*")
  val firstExceptionClassLine = lines.indexOfFirst { it.matches(exceptionClassRegex) }
  val meaningful =
    if (firstExceptionClassLine > 0) {
      lines.subList(0, firstExceptionClassLine)
    } else {
      // Fallback: strip from the first stack trace line
      val firstStackLine =
        lines.indexOfFirst { line ->
          val trimmed = line.trimStart()
          trimmed.startsWith("at ") || trimmed.startsWith("... ")
        }
      if (firstStackLine > 0) lines.subList(0, firstStackLine) else lines
    }
  return meaningful
    .dropLastWhile { it.isBlank() }
    .joinToString("\n")
    .trim()
    .ifEmpty { message.trim() }
}

internal fun buildFailureSuggestion(objective: ObjectiveProgress): String? {
  val explanation = objective.llmExplanation ?: return null
  val lower = explanation.lowercase()
  return when {
    "not found" in lower || "could not find" in lower || "no element" in lower ->
      "Suggestion: The target element may not be visible. Check if the screen state is correct before this step, or if the element identifier has changed."
    "timeout" in lower || "timed out" in lower ->
      "Suggestion: The operation timed out. The app may be slow to load or the element may appear with a delay. Consider increasing wait times."
    "unexpected" in lower || "wrong screen" in lower || "different screen" in lower ->
      "Suggestion: The app navigated to an unexpected screen. A prior step may have left the app in a different state than expected."
    else ->
      "Suggestion: Review the LLM explanation above and screenshots for clues about what went wrong."
  }
}

internal fun sessionStatusLabel(status: SessionStatus): String {
  return when (status) {
    is SessionStatus.Started -> "Started"
    is SessionStatus.Ended.Succeeded -> "Succeeded"
    is SessionStatus.Ended.Failed -> "Failed"
    is SessionStatus.Ended.Cancelled -> "Cancelled"
    is SessionStatus.Ended.SucceededWithFallback -> "Succeeded with fallback"
    is SessionStatus.Ended.FailedWithFallback -> "Failed with fallback"
    is SessionStatus.Ended.TimeoutReached -> "Timed out"
    is SessionStatus.Ended.MaxCallsLimitReached -> "Max calls limit reached"
    is SessionStatus.Unknown -> "Unknown"
  }
}

/**
 * Extracts the full ordered list of planned prompt strings from the session's raw YAML.
 * Returns empty if no raw YAML is available or parsing fails.
 */
internal fun extractPlannedPrompts(logs: List<TrailblazeLog>): List<String> {
  val startedStatus =
    logs
      .filterIsInstance<TrailblazeLog.TrailblazeSessionStatusChangeLog>()
      .map { it.sessionStatus }
      .filterIsInstance<SessionStatus.Started>()
      .firstOrNull()
      ?: return emptyList()
  val rawYaml = startedStatus.rawYaml ?: return emptyList()
  return try {
    TrailblazeYaml.Default
      .decodeTrail(rawYaml)
      .filterIsInstance<TrailYamlItem.PromptsTrailItem>()
      .flatMap { it.promptSteps }
      .map { it.prompt }
  } catch (_: Exception) {
    emptyList()
  }
}

/**
 * Appends grayed-out "Pending" objectives for planned prompts that haven't started yet.
 * Matches positionally: the first N planned prompts correspond to already-started objectives.
 */
internal fun appendPendingObjectives(
  items: List<ProgressItem>,
  plannedPrompts: List<String>,
): List<ProgressItem> {
  if (plannedPrompts.isEmpty()) return items
  val startedCount =
    items.filterIsInstance<ProgressItem.ObjectiveItem>().count()
  if (startedCount >= plannedPrompts.size) return items
  val lastStepNumber =
    items.filterIsInstance<ProgressItem.ObjectiveItem>().maxOfOrNull { it.stepNumber } ?: 0
  val pendingItems =
    plannedPrompts.drop(startedCount).mapIndexed { index, prompt ->
      ProgressItem.ObjectiveItem(
        objective =
          ObjectiveProgress(
            prompt = prompt,
            startedAt = null,
            completedAt = null,
            status = ObjectiveStatus.Pending,
          ),
        stepNumber = lastStepNumber + index + 1,
      )
    }
  return items + pendingItems
}

internal fun promptSummary(prompt: String, maxLength: Int = 120): String {
  val firstLine = prompt.lineSequence().firstOrNull().orEmpty().trim()
  return if (firstLine.length <= maxLength) firstLine else "${firstLine.take(maxLength - 3)}..."
}

/** Short caption for thumbnail labels (e.g. "TAP", "SCROLL ↓", "LLM think"). */
internal fun screenshotCaption(item: ScreenshotTimelineItem): String {
  val action = item.action
  return when (action) {
    is AgentDriverAction.TapPoint -> "TAP"
    is AgentDriverAction.LongPressPoint -> "LONG PRESS"
    is AgentDriverAction.Swipe -> "SWIPE ${swipeArrow(action.direction)}"
    is AgentDriverAction.EnterText -> "TYPE"
    is AgentDriverAction.BackPress -> "BACK"
    is AgentDriverAction.AssertCondition -> if (action.succeeded) "ASSERT ✓" else "ASSERT ✗"
    is AgentDriverAction.LaunchApp -> "LAUNCH"
    is AgentDriverAction.StopApp -> "STOP"
    is AgentDriverAction.KillApp -> "KILL"
    is AgentDriverAction.ClearAppState -> "CLEAR"
    is AgentDriverAction.AddMedia -> "MEDIA"
    is AgentDriverAction.AirplaneMode ->
      if (action.enable) "AIRPLANE ON" else "AIRPLANE OFF"
    is AgentDriverAction.GrantPermissions -> "PERMISSIONS"
    is AgentDriverAction.PressHome -> "HOME"
    is AgentDriverAction.HideKeyboard -> "KEYBOARD"
    is AgentDriverAction.EraseText -> "ERASE"
    is AgentDriverAction.Scroll -> if (action.forward) "SCROLL ↑" else "SCROLL ↓"
    is AgentDriverAction.WaitForSettle -> "WAIT"
    is AgentDriverAction.OtherAction -> {
      val name = action.type.name
      val short =
        name
          .substringAfterLast("_")
          .substringAfterLast(".")
          .replaceFirstChar { it.uppercase() }
      short.take(10)
    }
    null -> {
      when {
        item.label.startsWith("LLM") -> "LLM"
        item.toolCallName != null -> item.toolCallName
        else -> item.label.take(10)
      }
    }
  }
}

/** Detail lines for the preview info card. */
internal fun screenshotDetailLines(
  item: ScreenshotTimelineItem,
  sessionStartTime: Instant,
): List<Pair<String, String>> {
  val lines = mutableListOf<Pair<String, String>>()
  val elapsed =
    formatDuration(item.timestamp.toEpochMilliseconds() - sessionStartTime.toEpochMilliseconds())
  lines.add("Time" to elapsed)

  val action = item.action
  when (action) {
    is AgentDriverAction.TapPoint -> {
      lines.add("Action" to "Tap")
      lines.add("Coordinates" to "(${action.x}, ${action.y})")
    }
    is AgentDriverAction.LongPressPoint -> {
      lines.add("Action" to "Long Press")
      lines.add("Coordinates" to "(${action.x}, ${action.y})")
    }
    is AgentDriverAction.Swipe -> {
      lines.add("Action" to "Swipe ${action.direction}")
      if (action.startX != null && action.startY != null) {
        lines.add("From" to "(${action.startX}, ${action.startY})")
      }
      if (action.endX != null && action.endY != null) {
        lines.add("To" to "(${action.endX}, ${action.endY})")
      }
    }
    is AgentDriverAction.EnterText -> {
      lines.add("Action" to "Enter Text")
      lines.add("Text" to "\"${action.text}\"")
    }
    is AgentDriverAction.AssertCondition -> {
      lines.add("Action" to "Assert")
      lines.add("Condition" to action.conditionDescription)
      lines.add("Result" to if (action.succeeded) "Passed" else "Failed")
      action.textToDisplay?.let { lines.add("Element" to it) }
    }
    is AgentDriverAction.BackPress -> lines.add("Action" to "Back Press")
    is AgentDriverAction.LaunchApp -> {
      lines.add("Action" to "Launch App")
      lines.add("App" to action.appId)
    }
    is AgentDriverAction.StopApp -> {
      lines.add("Action" to "Stop App")
      lines.add("App" to action.appId)
    }
    is AgentDriverAction.KillApp -> {
      lines.add("Action" to "Kill App")
      lines.add("App" to action.appId)
    }
    is AgentDriverAction.ClearAppState -> {
      lines.add("Action" to "Clear App State")
      lines.add("App" to action.appId)
    }
    is AgentDriverAction.AddMedia -> {
      lines.add("Action" to "Add Media")
      lines.add("Files" to action.mediaFiles.joinToString(", "))
    }
    is AgentDriverAction.AirplaneMode ->
      lines.add("Action" to "Airplane Mode ${if (action.enable) "On" else "Off"}")
    is AgentDriverAction.GrantPermissions -> {
      lines.add("Action" to "Grant Permissions")
      lines.add("App" to action.appId)
    }
    is AgentDriverAction.PressHome -> lines.add("Action" to "Press Home")
    is AgentDriverAction.HideKeyboard -> lines.add("Action" to "Hide Keyboard")
    is AgentDriverAction.EraseText -> {
      lines.add("Action" to "Erase Text")
      lines.add("Characters" to "${action.characters}")
    }
    is AgentDriverAction.Scroll ->
      lines.add("Action" to "Scroll ${if (action.forward) "Forward" else "Backward"}")
    is AgentDriverAction.WaitForSettle -> {
      lines.add("Action" to "Wait for Settle")
      lines.add("Timeout" to "${action.timeoutMs}ms")
    }
    is AgentDriverAction.OtherAction -> lines.add("Action" to action.type.name)
    null -> lines.add("Type" to item.label)
  }
  return lines
}

private fun swipeArrow(direction: String): String {
  return when (direction.lowercase()) {
    "up" -> "↑"
    "down" -> "↓"
    "left" -> "←"
    "right" -> "→"
    else -> direction
  }
}
