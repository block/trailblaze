package xyz.block.trailblaze.host.ios

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import xyz.block.trailblaze.api.AgentActionType
import xyz.block.trailblaze.api.AgentDriverAction
import xyz.block.trailblaze.api.MigrationScreenState
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.host.capture.HostSessionFinalizerRegistry
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.Console

/**
 * Executes [IosDriverAction]s sequentially through an [IosDeviceManager], parallel to
 * [xyz.block.trailblaze.android.accessibility.AccessibilityTrailRunner].
 *
 * When [runActions] receives a logger + session provider, every action also emits a
 * [TrailblazeLog.AgentDriverLog] with a persisted screenshot, so IOS_AXE runs render per-action
 * step frames in the report just like IOS_HOST's
 * [xyz.block.trailblaze.android.maestro.LoggingDriver] path does. The screen is captured
 * *before* the action executes (so tap coordinates overlay on the target element), while the
 * screenshot file write + log emission run asynchronously off the action loop — the same
 * split the Android accessibility runner uses. Errors short-circuit the run.
 */
object IosDriverTrailRunner {

  /** Single-thread scope for async logging. Preserves log ordering within a session. */
  private val loggingJob = SupervisorJob()
  private val loggingScope = CoroutineScope(loggingJob + Dispatchers.IO.limitedParallelism(1))

  init {
    // Success-path durability: host sessions that end through the shared finalization barrier
    // (finalizeHostSessionResources → HostSessionFinalizerRegistry) join pending log writes
    // before artifacts are published. Entry points that bypass the barrier (e.g. a runner with
    // sendSessionEndLog=false) get best-effort completion instead — the writes still land on
    // the logging scope, just without a pre-publication join. Error paths flush inline in
    // [runActions].
    HostSessionFinalizerRegistry.register { flushLogs() }
  }

  /** Waits for all pending async log writes to complete. */
  fun flushLogs() {
    runBlocking { loggingJob.children.forEach { it.join() } }
  }

  /**
   * Runs the actions in order. When [trailblazeLogger] and [sessionProvider] are both present,
   * each action (successful or failed) is logged as an [TrailblazeLog.AgentDriverLog] with a
   * pre-action screenshot; when either is absent, behavior degrades to plain inline execution
   * with Console lines only.
   */
  fun runActions(
    actions: List<IosDriverAction>,
    traceId: TraceId?,
    deviceManager: IosDeviceManager,
    trailblazeLogger: TrailblazeLogger? = null,
    sessionProvider: TrailblazeSessionProvider? = null,
  ): TrailblazeToolResult {
    val loggingEnabled = trailblazeLogger != null && sessionProvider != null
    for (action in actions) {
      // Pre-action capture so the screenshot shows the UI at the moment the action was
      // decided — and, on failure, the screen the action failed against.
      val preScreenState = if (loggingEnabled) captureScreenStateForLogging(deviceManager) else null

      val startTime = Clock.System.now()
      val (result, executionResult) = try {
        val execResult = deviceManager.execute(action)
        val elapsed = Clock.System.now().toEpochMilliseconds() - startTime.toEpochMilliseconds()
        Console.log("[IosDriverTrailRunner] ${action.description} — ${elapsed}ms (trace=$traceId)")
        TrailblazeToolResult.Success() as TrailblazeToolResult to execResult
      } catch (e: CancellationException) {
        // Never convert coroutine cancellation into an action failure — a run abort must
        // propagate, not be logged as a failed step.
        throw e
      } catch (e: Exception) {
        Console.log("[IosDriverTrailRunner] ${action.description} FAILED: ${e.message}")
        TrailblazeToolResult.Error.ExceptionThrown(
          errorMessage = "Failed action: ${action.description}. Error: ${e.message}",
          stackTrace = e.stackTraceToString(),
        ) to IosDeviceManager.ExecutionResult()
      }
      val durationMs = Clock.System.now().toEpochMilliseconds() - startTime.toEpochMilliseconds()

      if (trailblazeLogger != null && sessionProvider != null && preScreenState != null) {
        if (wasSkippedOptional(action, executionResult, result)) {
          Console.log(
            "[IosDriverTrailRunner] ${action.description} skipped (optional) — no driver log emitted",
          )
        } else {
          logAsync(
          trailblazeLogger = trailblazeLogger,
          session = sessionProvider.invoke(),
          screenState = preScreenState,
          driverAction = mapToAgentDriverAction(action, executionResult, result),
          durationMs = durationMs,
            timestamp = startTime,
            traceId = traceId,
          )
        }
      }

      if (result is TrailblazeToolResult.Error) {
        flushLogs()
        return result
      }
    }
    // No flush on success — the write of action N's screenshot overlaps the next action's
    // capture and dispatch; the host session-finalization barrier joins before publication.
    return TrailblazeToolResult.Success()
  }

  /**
   * Captures the screen state for logging, forcing the lazily-computed members on the calling
   * thread: [xyz.block.trailblaze.host.screenstate.AxeScreenState] is fully lazy, so reading
   * `screenshotBytes` / `trailblazeNodeTree` later on the logging thread would capture whatever
   * the device shows *after* the action instead of the pre-action screen.
   *
   * Capture failures are non-fatal — the action still runs, just without a driver log.
   */
  private fun captureScreenStateForLogging(deviceManager: IosDeviceManager): ScreenState? = try {
    deviceManager.getScreenState().also { screenState ->
      screenState.screenshotBytes
      screenState.trailblazeNodeTree
    }
  } catch (e: CancellationException) {
    throw e
  } catch (t: Throwable) {
    Console.log("[IosDriverTrailRunner] screen capture for logging failed (continuing without): ${t.message}")
    null
  }

  /**
   * Persists the screenshot and emits the [TrailblazeLog.AgentDriverLog] on a background
   * thread so file I/O stays off the action loop. Failures are logged and swallowed — a
   * broken log write must never kill the run.
   */
  private fun logAsync(
    trailblazeLogger: TrailblazeLogger,
    session: TrailblazeSession,
    screenState: ScreenState,
    driverAction: AgentDriverAction,
    durationMs: Long,
    timestamp: Instant,
    traceId: TraceId?,
  ) {
    loggingScope.launch {
      try {
        val screenshotFilename = if (screenState.screenshotBytes?.isNotEmpty() == true) {
          trailblazeLogger.logScreenState(session, screenState)
        } else {
          null
        }
        val log = TrailblazeLog.AgentDriverLog(
          // AxeScreenState.viewHierarchy throws (rather than returning null) when
          // describe-ui produced no usable tree — degrade to a hierarchy-less log.
          viewHierarchy = runCatching { screenState.viewHierarchy }.getOrNull(),
          trailblazeNodeTree = screenState.trailblazeNodeTree,
          // Migration-mode side tree (see MigrationScreenState). Carried on every log
          // type with `trailblazeNodeTree` so migrate-trail's cursor-scan fallback
          // produces accessibility-shape selectors regardless of which log it lands on.
          driverMigrationTreeNode = (screenState as? MigrationScreenState)?.driverMigrationTreeNode,
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
        Console.log("[IosDriverTrailRunner] async driver logging failed: ${e.message}")
      }
    }
  }

  /**
   * True when an `optional: true` action was skipped by the device manager (wait exhausted,
   * no throw). A skip surfaces as a Success with no resolved coordinates, while a genuine pass
   * of any optional-capable action resolves them (the match center; assertNotVisible returns
   * the screen center). No driver log is emitted for a skip: rendering it would show a passed
   * assert card — or a (0,0) tap marker — for something the driver never observed.
   */
  private fun wasSkippedOptional(
    action: IosDriverAction,
    executionResult: IosDeviceManager.ExecutionResult,
    toolResult: TrailblazeToolResult,
  ): Boolean {
    val optional = when (action) {
      is IosDriverAction.TapOnElement -> action.optional
      is IosDriverAction.AssertVisible -> action.optional
      is IosDriverAction.AssertNotVisible -> action.optional
      else -> false
    }
    return optional &&
      toolResult is TrailblazeToolResult.Success &&
      executionResult.resolvedX == null &&
      executionResult.resolvedY == null
  }

  /**
   * Maps an [IosDriverAction] and its [IosDeviceManager.ExecutionResult] to the closest
   * [AgentDriverAction], so report frame labels and tap markers on IOS_AXE read like the
   * host/Android drivers'. Mirrors AccessibilityTrailRunner.mapToAgentDriverAction.
   */
  private fun mapToAgentDriverAction(
    action: IosDriverAction,
    executionResult: IosDeviceManager.ExecutionResult,
    toolResult: TrailblazeToolResult,
  ): AgentDriverAction = when (action) {
    is IosDriverAction.Tap ->
      AgentDriverAction.TapPoint(x = action.x, y = action.y)

    is IosDriverAction.TapRelative ->
      AgentDriverAction.TapPoint(
        x = executionResult.resolvedX ?: 0,
        y = executionResult.resolvedY ?: 0,
      )

    is IosDriverAction.LongPress ->
      AgentDriverAction.LongPressPoint(x = action.x, y = action.y)

    is IosDriverAction.Swipe ->
      AgentDriverAction.Swipe(
        direction = inferSwipeDirection(
          action.startX.toDouble(),
          action.startY.toDouble(),
          action.endX.toDouble(),
          action.endY.toDouble(),
        ),
        durationMs = action.durationMs,
        startX = action.startX,
        startY = action.startY,
        endX = action.endX,
        endY = action.endY,
      )

    is IosDriverAction.SwipeDirection ->
      AgentDriverAction.Swipe(direction = action.direction.name, durationMs = action.durationMs)

    is IosDriverAction.SwipeRelative ->
      AgentDriverAction.Swipe(
        direction = inferSwipeDirection(
          action.startXPercent,
          action.startYPercent,
          action.endXPercent,
          action.endYPercent,
        ),
        durationMs = action.durationMs,
      )

    IosDriverAction.ScrollDown, IosDriverAction.ScrollRight ->
      AgentDriverAction.Scroll(forward = true)

    IosDriverAction.ScrollUp, IosDriverAction.ScrollLeft ->
      AgentDriverAction.Scroll(forward = false)

    is IosDriverAction.InputText ->
      AgentDriverAction.EnterText(text = action.text)

    is IosDriverAction.EraseText ->
      AgentDriverAction.EraseText(characters = action.characters)

    IosDriverAction.PressHome ->
      AgentDriverAction.PressHome

    IosDriverAction.PressLock, IosDriverAction.PressSiri ->
      AgentDriverAction.OtherAction(type = AgentActionType.PRESS_KEY)

    is IosDriverAction.PressKey ->
      AgentDriverAction.OtherAction(type = AgentActionType.PRESS_KEY)

    is IosDriverAction.TapOnElement -> {
      val x = executionResult.resolvedX ?: 0
      val y = executionResult.resolvedY ?: 0
      if (action.longPress) {
        AgentDriverAction.LongPressPoint(x = x, y = y)
      } else {
        AgentDriverAction.TapPoint(x = x, y = y)
      }
    }

    is IosDriverAction.AssertVisible ->
      AgentDriverAction.AssertCondition(
        conditionDescription = action.nodeSelector.description(),
        x = executionResult.resolvedX ?: 0,
        y = executionResult.resolvedY ?: 0,
        isVisible = true,
        succeeded = toolResult is TrailblazeToolResult.Success,
      )

    is IosDriverAction.AssertNotVisible ->
      AgentDriverAction.AssertCondition(
        conditionDescription = action.nodeSelector.description(),
        x = executionResult.resolvedX ?: 0,
        y = executionResult.resolvedY ?: 0,
        isVisible = false,
        textToDisplay = action.nodeSelector.description(),
        succeeded = toolResult is TrailblazeToolResult.Success,
      )

    is IosDriverAction.LaunchApp ->
      AgentDriverAction.LaunchApp(appId = action.bundleId)

    is IosDriverAction.StopApp ->
      AgentDriverAction.StopApp(appId = action.bundleId)

    is IosDriverAction.ClearState ->
      AgentDriverAction.ClearAppState(appId = action.bundleId)

    IosDriverAction.ClearKeychain ->
      AgentDriverAction.OtherAction(type = AgentActionType.CLEAR_APP_STATE)

    is IosDriverAction.OpenLink ->
      AgentDriverAction.OtherAction(type = AgentActionType.LAUNCH_APP)

    is IosDriverAction.WaitForSettle ->
      AgentDriverAction.WaitForSettle(timeoutMs = action.timeoutMs)

    // The action itself is a no-op — the logged pre-action frame IS the screenshot.
    IosDriverAction.TakeScreenshot ->
      AgentDriverAction.OtherAction(type = AgentActionType.WAIT_FOR_SETTLE)
  }

  private fun inferSwipeDirection(
    startX: Double,
    startY: Double,
    endX: Double,
    endY: Double,
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
