package xyz.block.trailblaze.android.accessibility

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import xyz.block.trailblaze.api.AgentActionType
import xyz.block.trailblaze.api.AgentDriverAction
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.Console

/**
 * Executes trail actions directly through the accessibility service, **without Maestro**.
 *
 * This is the Maestro-free trail runner. Instead of
 * creating a Maestro instance and routing through Orchestra, it dispatches [AccessibilityAction]
 * objects directly through [AccessibilityDeviceManager] with built-in event-based settle detection.
 *
 * Follows the same pattern as the Playwright agent:
 * - Uses framework-native action types ([AccessibilityAction]) instead of MaestroCommand
 * - Logs via [TrailblazeLog.AgentDriverLog] for tracing/debugging with screenshot overlays
 * - Screen state captured via [AccessibilityServiceScreenState], not Maestro's view hierarchy
 *
 * ### Performance
 * Per-action overhead drops from ~500-700ms (Maestro's screenshot sandwich) to ~100-150ms
 * (event-based settle + single-pass tree capture), giving roughly a 4-5x speedup for trail
 * playback. Logging (screenshot capture + file I/O) runs asynchronously on a background thread
 * so it doesn't add to per-action latency.
 */
object AccessibilityTrailRunner {

  /** Single-thread scope for async logging. Preserves log ordering within a session. */
  private val loggingJob = SupervisorJob()
  private val loggingScope = CoroutineScope(loggingJob + Dispatchers.IO.limitedParallelism(1))

  /**
   * Waits for all pending async log writes to complete. Call this at the end of test execution
   * to ensure final logs and screenshots are flushed before the process exits.
   */
  fun flushLogs() {
    runBlocking {
      loggingJob.children.forEach { it.join() }
    }
  }

  /**
   * Runs a list of accessibility actions with standardized error handling and logging.
   *
   * For each action, the pre-action screen state is captured first (so tap coordinates
   * overlay on the correct screenshot), then the action executes. Logging runs
   * asynchronously on a background thread so the next action can start immediately.
   */
  fun runActions(
    actions: List<AccessibilityAction>,
    traceId: TraceId?,
    trailblazeLogger: TrailblazeLogger,
    sessionProvider: TrailblazeSessionProvider,
    deviceManager: AccessibilityDeviceManager = AccessibilityDeviceManager(),
  ): TrailblazeToolResult {
    for (action in actions) {
      // Ensure the UI is settled before capturing. In the RPC flow, actions arrive from the
      // agent without a local settle guarantee — waitForReady() is event-based and returns
      // immediately if already stable, so this is free when the previous action already settled.
      deviceManager.waitForReady()

      // Capture the pre-action screen state so the screenshot shows the UI at the moment
      // the action was decided — tap coordinates overlay correctly on the target element.
      val preScreenState = deviceManager.captureScreenStateForLogging()

      val startTime = Clock.System.now()

      // Execute action (gesture dispatch + event-based settle)
      val (result, executionResult) =
        try {
          val execResult = deviceManager.execute(action)
          TrailblazeToolResult.Success() to execResult
        } catch (e: Exception) {
          Console.log("Action failed: ${action.description}: ${e.message}")
          TrailblazeToolResult.Error.ExceptionThrown(
            errorMessage = "Failed action: ${action.description}. Error: ${e.message}",
            stackTrace = e.stackTraceToString(),
          ) to AccessibilityDeviceManager.ExecutionResult()
        }

      val durationMs =
        Clock.System.now().toEpochMilliseconds() - startTime.toEpochMilliseconds()

      // Map action to driver log format and log asynchronously
      val driverAction = mapToAgentDriverAction(action, executionResult, result)
      val session = sessionProvider.invoke()
      logAsync(trailblazeLogger, session, preScreenState, driverAction, durationMs, startTime, traceId)

      if (result is TrailblazeToolResult.Error) {
        flushLogs()
        return result
      }
    }
    flushLogs()
    return TrailblazeToolResult.Success()
  }

  /**
   * Logs the action result asynchronously on a background thread. Screenshot file I/O and
   * log emission happen off the critical path so the next action can start immediately.
   */
  private fun logAsync(
    trailblazeLogger: TrailblazeLogger,
    session: TrailblazeSession,
    screenState: ScreenState,
    driverAction: AgentDriverAction,
    durationMs: Long,
    timestamp: kotlinx.datetime.Instant,
    traceId: TraceId?,
  ) {
    loggingScope.launch {
      try {
        val screenshotFilename = if (screenState.screenshotBytes?.isNotEmpty() == true) {
          trailblazeLogger.logScreenState(session, screenState)
        } else {
          null
        }

        val log =
          TrailblazeLog.AgentDriverLog(
            viewHierarchy = screenState.viewHierarchy,
            trailblazeNodeTree = screenState.trailblazeNodeTree,
            screenshotFile = screenshotFilename,
            action = driverAction,
            durationMs = durationMs,
            timestamp = timestamp,
            session = session.sessionId,
            deviceWidth = screenState.deviceWidth,
            deviceHeight = screenState.deviceHeight,
            traceId = traceId,
          )
        trailblazeLogger.log(session, log)
      } catch (e: Exception) {
        Console.log("Async logging failed: ${e.message}")
      }
    }
  }

  /**
   * Maps an [AccessibilityAction] and its [AccessibilityDeviceManager.ExecutionResult] to an
   * [AgentDriverAction] for unified logging and screenshot overlay visualization.
   */
  private fun mapToAgentDriverAction(
    action: AccessibilityAction,
    executionResult: AccessibilityDeviceManager.ExecutionResult,
    toolResult: TrailblazeToolResult,
  ): AgentDriverAction {
    return when (action) {
      is AccessibilityAction.Tap ->
        AgentDriverAction.TapPoint(x = action.x, y = action.y)

      is AccessibilityAction.TapRelative ->
        AgentDriverAction.TapPoint(
          x = executionResult.resolvedX ?: 0,
          y = executionResult.resolvedY ?: 0,
        )

      is AccessibilityAction.LongPress ->
        AgentDriverAction.LongPressPoint(x = action.x, y = action.y)

      is AccessibilityAction.Swipe ->
        AgentDriverAction.Swipe(
          direction = inferSwipeDirection(action.startX, action.startY, action.endX, action.endY),
          durationMs = action.durationMs,
          startX = action.startX,
          startY = action.startY,
          endX = action.endX,
          endY = action.endY,
        )

      is AccessibilityAction.SwipeDirection ->
        AgentDriverAction.Swipe(
          direction = action.direction.name,
          durationMs = action.durationMs,
        )

      is AccessibilityAction.ScrollForward ->
        AgentDriverAction.Scroll(forward = true)

      is AccessibilityAction.ScrollBackward ->
        AgentDriverAction.Scroll(forward = false)

      is AccessibilityAction.InputText ->
        AgentDriverAction.EnterText(text = action.text)

      is AccessibilityAction.EraseText ->
        AgentDriverAction.EraseText(characters = action.characters)

      is AccessibilityAction.PressBack ->
        AgentDriverAction.BackPress

      is AccessibilityAction.PressHome ->
        AgentDriverAction.PressHome

      is AccessibilityAction.PressKey ->
        AgentDriverAction.OtherAction(type = AgentActionType.PRESS_KEY)

      is AccessibilityAction.HideKeyboard ->
        AgentDriverAction.HideKeyboard

      is AccessibilityAction.TapOnElement -> {
        val x = executionResult.resolvedX ?: 0
        val y = executionResult.resolvedY ?: 0
        if (action.longPress) {
          AgentDriverAction.LongPressPoint(x = x, y = y)
        } else {
          AgentDriverAction.TapPoint(x = x, y = y)
        }
      }

      is AccessibilityAction.AssertVisible ->
        AgentDriverAction.AssertCondition(
          conditionDescription = action.nodeSelector.description(),
          x = executionResult.resolvedX ?: 0,
          y = executionResult.resolvedY ?: 0,
          isVisible = true,
          succeeded = toolResult is TrailblazeToolResult.Success,
        )

      is AccessibilityAction.AssertNotVisible ->
        AgentDriverAction.AssertCondition(
          conditionDescription = action.nodeSelector.description(),
          x = executionResult.resolvedX ?: 0,
          y = executionResult.resolvedY ?: 0,
          isVisible = false,
          textToDisplay = action.nodeSelector.description(),
          succeeded = toolResult is TrailblazeToolResult.Success,
        )

      is AccessibilityAction.LaunchApp ->
        AgentDriverAction.LaunchApp(appId = action.appId)

      // Clipboard actions have no dedicated AgentActionType; use closest existing types.
      is AccessibilityAction.SetClipboard ->
        AgentDriverAction.EnterText(text = "[clipboard] ${action.text}")

      is AccessibilityAction.PasteText ->
        AgentDriverAction.EnterText(text = "[paste]")

      is AccessibilityAction.CopyTextFrom ->
        AgentDriverAction.OtherAction(type = AgentActionType.ASSERT_CONDITION)

      is AccessibilityAction.StopApp ->
        AgentDriverAction.StopApp(appId = action.appId)

      is AccessibilityAction.KillApp ->
        AgentDriverAction.KillApp(appId = action.appId)

      is AccessibilityAction.ClearState ->
        AgentDriverAction.ClearAppState(appId = action.appId)

      is AccessibilityAction.OpenLink ->
        AgentDriverAction.OtherAction(type = AgentActionType.LAUNCH_APP)

      is AccessibilityAction.SetOrientation ->
        AgentDriverAction.OtherAction(type = AgentActionType.SWIPE)

      is AccessibilityAction.SetAirplaneMode ->
        AgentDriverAction.AirplaneMode(enable = action.enabled)

      is AccessibilityAction.ToggleAirplaneMode ->
        AgentDriverAction.AirplaneMode(enable = true) // Actual direction unknown at log time

      is AccessibilityAction.ScrollUntilVisible ->
        AgentDriverAction.Scroll(forward = action.direction == AccessibilityAction.Direction.DOWN)

      is AccessibilityAction.WaitForSettle ->
        AgentDriverAction.WaitForSettle(timeoutMs = action.timeoutMs)
    }
  }

  private fun inferSwipeDirection(
    startX: Int,
    startY: Int,
    endX: Int,
    endY: Int,
  ): String {
    val deltaX = endX - startX
    val deltaY = endY - startY
    return if (kotlin.math.abs(deltaX) > kotlin.math.abs(deltaY)) {
      if (deltaX > 0) "RIGHT" else "LEFT"
    } else {
      if (deltaY > 0) "DOWN" else "UP"
    }
  }
}
