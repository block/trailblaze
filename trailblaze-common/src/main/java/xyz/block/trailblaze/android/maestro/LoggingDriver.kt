package xyz.block.trailblaze.android.maestro

import maestro.Driver
import maestro.Point
import maestro.SwipeDirection
import xyz.block.trailblaze.api.MaestroDriverActionType
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import java.io.File
import java.util.UUID
import kotlin.system.measureTimeMillis

/**
 * This is a delegate Maestro [Driver] that logs all actions to the [TrailblazeLogger].
 */
class LoggingDriver(
  private val delegate: Driver,
  private val screenStateProvider: () -> ScreenState,
) : Driver by delegate {

  private fun logActionWithScreenshot(action: MaestroDriverActionType, block: () -> Unit = {}) {
    val screenState = screenStateProvider()
    val startTime = System.currentTimeMillis()
    val executionTimeMs = measureTimeMillis {
      block()
    }
    val screenshotFilename = screenState.screenshotBytes?.let { TrailblazeLogger.logScreenshot(it) }
    TrailblazeLogger.log(
      TrailblazeLog.MaestroDriverLog(
        viewHierarchy = screenState.viewHierarchy,
        screenshotFile = screenshotFilename,
        action = action,
        duration = executionTimeMs,
        timestamp = startTime,
        session = TrailblazeLogger.getCurrentSessionId(),
        deviceWidth = screenState.deviceWidth,
        deviceHeight = screenState.deviceHeight,
      ),
    )
  }

  private fun logActionWithoutScreenshot(action: MaestroDriverActionType, block: () -> Unit = {}) {
    val screenState = screenStateProvider()
    val startTime = System.currentTimeMillis()
    val executionTimeMs = measureTimeMillis {
      block()
    }
    TrailblazeLogger.log(
      TrailblazeLog.MaestroDriverLog(
        viewHierarchy = null,
        screenshotFile = null,
        action = action,
        duration = executionTimeMs,
        timestamp = startTime,
        session = TrailblazeLogger.getCurrentSessionId(),
        deviceWidth = screenState.deviceWidth,
        deviceHeight = screenState.deviceHeight,
      ),
    )
  }

  override fun addMedia(mediaFiles: List<File>) = logActionWithScreenshot(MaestroDriverActionType.AddMedia(mediaFiles.map { it.canonicalPath })) {
    delegate.addMedia(mediaFiles)
  }

  override fun backPress() = logActionWithScreenshot(MaestroDriverActionType.BackPress) {
    delegate.backPress()
  }

  override fun clearAppState(appId: String) = logActionWithoutScreenshot(MaestroDriverActionType.ClearAppState(appId)) {
    delegate.clearAppState(appId)
  }

  override fun inputText(text: String) = logActionWithScreenshot(MaestroDriverActionType.EnterText(text)) {
    delegate.inputText(text)
  }

  override fun killApp(appId: String) = logActionWithoutScreenshot(MaestroDriverActionType.KillApp(appId)) {
    delegate.killApp(appId)
  }

  override fun launchApp(
    appId: String,
    launchArguments: Map<String, Any>,
    sessionId: UUID?,
  ) = logActionWithoutScreenshot(MaestroDriverActionType.LaunchApp(appId)) {
    delegate.launchApp(appId, launchArguments, sessionId)
  }

  override fun setPermissions(appId: String, permissions: Map<String, String>) {
    val filteredPermissions = permissions.filter { it.key != "all" }
    if (filteredPermissions.isNotEmpty()) {
      logActionWithoutScreenshot(
        MaestroDriverActionType.GrantPermissions(
          appId = appId,
          permissions = permissions,
        ),
      ) {
        delegate.setPermissions(appId, permissions)
      }
    }
  }

  override fun stopApp(appId: String) = logActionWithoutScreenshot(MaestroDriverActionType.StopApp(appId)) {
    delegate.stopApp(appId)
  }

  override fun swipe(elementPoint: Point, direction: SwipeDirection, durationMs: Long) = logActionWithScreenshot(MaestroDriverActionType.Swipe(direction.name, durationMs)) {
    delegate.swipe(elementPoint, direction, durationMs)
  }

  override fun swipe(swipeDirection: SwipeDirection, durationMs: Long) = logActionWithScreenshot(MaestroDriverActionType.Swipe(swipeDirection.name, durationMs)) {
    delegate.swipe(swipeDirection, durationMs)
  }

  override fun tap(point: Point) = logActionWithScreenshot(MaestroDriverActionType.TapPoint(point.x, point.y)) {
    delegate.tap(point)
  }

  override fun longPress(point: Point) = logActionWithScreenshot(MaestroDriverActionType.LongPressPoint(point.x, point.y)) {
    delegate.tap(point)
  }
}
