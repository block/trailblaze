package xyz.block.trailblaze.maestro

import kotlinx.datetime.Clock
import maestro.Capability
import maestro.DeviceInfo
import maestro.Driver
import maestro.KeyCode
import maestro.Point
import maestro.orchestra.MaestroCommand
import maestro.orchestra.TapOnElementCommand
import maestro.orchestra.TapOnPointV2Command
import maestro.ScreenRecording
import maestro.SwipeDirection
import maestro.TreeNode
import maestro.ViewHierarchy
import okio.Sink
import xyz.block.trailblaze.api.AgentDriverAction
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.toolcalls.redactSensitiveValuesIn
import xyz.block.trailblaze.utils.Ext.asJsonObject
import xyz.block.trailblaze.tracing.TrailblazeTracer.traceRecorder
import java.io.File
import kotlin.system.measureTimeMillis
import xyz.block.trailblaze.util.Console

/**
 * This is a delegate Maestro [Driver] that logs all actions to the logger.
 * 
 * Uses stateless logger with explicit session management.
 * The sessionProvider must be provided to enable logging.
 */
class LoggingDriver(
  private val delegate: Driver,
  private val screenStateProvider: () -> ScreenState,
  private val trailblazeLogger: TrailblazeLogger,
  private val sessionProvider: TrailblazeSessionProvider,
  private val sensitiveValues: Collection<String> = emptyList(),
  /**
   * False while running a batch that contains a repeated tap (a double-tap): taps are then logged
   * without screen capture, because a capture or screenshot write between the taps can stretch
   * their gap past the platform's double-tap window and turn one double-tap into two single taps.
   */
  private val captureScreenPerTap: Boolean = true,
  /** Resolved before a repeated tap starts so no device round trip lands between its taps. */
  private val prefetchedDeviceInfo: DeviceInfo? = null,
) : Driver {

  /** A batch-local wrapper avoids sharing credentials between concurrent host command batches. */
  fun forMaestroBatch(commands: List<MaestroCommand>): LoggingDriver {
    val values = commands.flatMap { maestroLaunchArgumentSecrets(it.asJsonObject()) }
    val hasRepeatedTap = commands.any { it.hasRepeatedTap() }
    return if (values.isEmpty() && !hasRepeatedTap) this else LoggingDriver(
      delegate, screenStateProvider, trailblazeLogger, sessionProvider, values,
      captureScreenPerTap = !hasRepeatedTap,
      prefetchedDeviceInfo = if (hasRepeatedTap) delegate.deviceInfo() else null,
    )
  }

  private fun MaestroCommand.hasRepeatedTap(): Boolean {
    val repeat = when (val command = asCommand()) {
      is TapOnPointV2Command -> command.repeat
      is TapOnElementCommand -> command.repeat
      else -> null
    }
    return (repeat?.repeat ?: 1) > 1
  }

  private fun AgentDriverAction.safeForLog(): AgentDriverAction = when (this) {
    is AgentDriverAction.EnterText -> copy(text = redactSensitiveValuesIn(text, sensitiveValues))
    else -> this
  }

  private inline fun <T> traceMaestroDriver(name: String, block: () -> T): T = traceRecorder.trace(name, "MaestroDriver", emptyMap(), block = {
    Console.log("Maestro-${delegate::class.java.simpleName}-$name()")
    block()
  })

  /**
   * Wraps the Screen State Provider so that we can use a cached value when requested.
   */
  fun getCurrentScreenState(): ScreenState = temporaryStaticScreenStateData?.let { screenStateTemporarilyDisabledData ->
    Console.log("Temporarily using cached  is currently preloaded because: ${screenStateTemporarilyDisabledData.reason}.")
    screenStateTemporarilyDisabledData.screenState
  } ?: screenStateProvider()

  private inline fun <T> traceMaestroDriverAction(action: AgentDriverAction, block: () -> T): T = traceMaestroDriver(action::class.simpleName!!, block)

  private fun logActionWithScreenshot(action: AgentDriverAction, block: () -> Unit = {}) {
    val screenState = getCurrentScreenState()
    val startTime = Clock.System.now()

    val executionTimeMs = measureTimeMillis {
      traceMaestroDriverAction(action) {
        block()
      }
    }
    
    // Get current session for logging
    val session = sessionProvider.invoke()
    val screenshotFilename = if (screenState.screenshotBytes != null) {
      trailblazeLogger.logScreenState(session, screenState)
    } else {
      null
    }
    
    val log = TrailblazeLog.AgentDriverLog(
      viewHierarchy = screenState.viewHierarchy,
      trailblazeNodeTree = screenState.trailblazeNodeTree,
      // Migration-mode side tree (see MigrationScreenState). Carried on every log type
      // with a `trailblazeNodeTree` so migrate-trail's cursor-scan fallback produces
      // accessibility-shape selectors regardless of which log it lands on.
      driverMigrationTreeNode =
        (screenState as? xyz.block.trailblaze.api.MigrationScreenState)
          ?.driverMigrationTreeNode,
      screenshotFile = screenshotFilename,
      action = action.safeForLog(),
      durationMs = executionTimeMs,
      timestamp = startTime,
      session = session.sessionId,
      deviceWidth = screenState.deviceWidth,
      deviceHeight = screenState.deviceHeight,
    )
    trailblazeLogger.log(session, log)
  }

  /**
   * [gridDimensions] logs Maestro's grid size instead of the pixel size: an action whose
   * coordinates are grid points (a tap) must carry the same coordinate space the screen-captured
   * logs use, or the report places its marker by the wrong scale.
   */
  private fun logActionWithoutScreenshot(
    action: AgentDriverAction,
    gridDimensions: Boolean = false,
    block: () -> Unit = {},
  ) {
    val deviceInfo = prefetchedDeviceInfo ?: delegate.deviceInfo()
    val startTime = Clock.System.now()
    val executionTimeMs = measureTimeMillis {
      traceMaestroDriverAction(action) {
        block()
      }
    }
    
    // Get current session for logging
    val session = sessionProvider.invoke()
    val log = TrailblazeLog.AgentDriverLog(
      viewHierarchy = null,
      screenshotFile = null,
      action = action.safeForLog(),
      durationMs = executionTimeMs,
      timestamp = startTime,
      session = session.sessionId,
      deviceWidth = if (gridDimensions) deviceInfo.widthGrid else deviceInfo.widthPixels,
      deviceHeight = if (gridDimensions) deviceInfo.heightGrid else deviceInfo.heightPixels,
    )
    trailblazeLogger.log(session, log)
  }

  override fun addMedia(mediaFiles: List<File>) = logActionWithScreenshot(AgentDriverAction.AddMedia(mediaFiles.map { it.canonicalPath })) {
    delegate.addMedia(mediaFiles)
  }

  override fun backPress() = logActionWithScreenshot(AgentDriverAction.BackPress) {
    delegate.backPress()
  }

  override fun capabilities(): List<Capability> = traceMaestroDriver("capabilities") {
    delegate.capabilities()
  }

  override fun clearAppState(appId: String) = logActionWithoutScreenshot(AgentDriverAction.ClearAppState(appId)) {
    delegate.clearAppState(appId)
  }

  override fun clearKeychain() = traceMaestroDriver("clearKeychain") {
    delegate.clearKeychain()
  }

  override fun close() = traceMaestroDriver("close") {
    delegate.close()
  }

  override fun contentDescriptor(excludeKeyboardElements: Boolean): TreeNode = traceMaestroDriver("contentDescriptor") {
    delegate.contentDescriptor(excludeKeyboardElements)
  }

  override fun deviceInfo(): DeviceInfo = traceMaestroDriver("deviceInfo") {
    delegate.deviceInfo()
  }

  override fun eraseText(charactersToErase: Int) = traceMaestroDriver("eraseText") { delegate.eraseText(charactersToErase) }

  override fun hideKeyboard() = traceMaestroDriver("hideKeyboard") {
    delegate.hideKeyboard()
  }

  override fun inputText(text: String) = logActionWithScreenshot(AgentDriverAction.EnterText(text)) {
    delegate.inputText(text)
  }

  override fun isAirplaneModeEnabled(): Boolean = traceMaestroDriver("isAirplaneModeEnabled") {
    delegate.isAirplaneModeEnabled()
  }

  override fun isKeyboardVisible(): Boolean = traceMaestroDriver("isKeyboardVisible") {
    delegate.isKeyboardVisible()
  }

  override fun isShutdown(): Boolean = traceMaestroDriver("isShutdown") {
    delegate.isShutdown()
  }

  override fun isDarkModeEnabled(): Boolean = traceMaestroDriver("isDarkModeEnabled") {
    delegate.isDarkModeEnabled()
  }

  override fun setDarkMode(enabled: Boolean) = traceMaestroDriver("setDarkMode") {
    delegate.setDarkMode(enabled)
  }

  override fun killApp(appId: String) = logActionWithoutScreenshot(AgentDriverAction.KillApp(appId)) {
    delegate.killApp(appId)
  }

  override fun launchApp(
    appId: String,
    launchArguments: Map<String, Any>,
  ) = logActionWithoutScreenshot(AgentDriverAction.LaunchApp(appId)) {
    delegate.launchApp(appId, launchArguments)
  }

  override fun setOrientation(orientation: maestro.device.DeviceOrientation) {
    delegate.setOrientation(orientation)
  }

  override fun setAirplaneMode(enabled: Boolean) = logActionWithoutScreenshot(AgentDriverAction.AirplaneMode(enabled)) {
    delegate.setAirplaneMode(enabled)
  }

  override fun setLocation(latitude: Double, longitude: Double) = traceMaestroDriver("setLocation") {
    delegate.setLocation(
      latitude = latitude,
      longitude = longitude,
    )
  }

  override fun setPermissions(appId: String, permissions: Map<String, String>) {
    if (permissions.isNotEmpty()) {
      logActionWithoutScreenshot(
        AgentDriverAction.GrantPermissions(
          appId = appId,
          permissions = permissions,
        ),
      ) {
        delegate.setPermissions(appId, permissions)
      }
    }
  }

  override fun setProxy(host: String, port: Int) = traceMaestroDriver("startScreenRecording") {
    delegate.setProxy(
      host = host,
      port = port,
    )
  }

  override fun startScreenRecording(out: Sink): ScreenRecording = traceMaestroDriver("startScreenRecording") {
    delegate.startScreenRecording(out)
  }

  override fun stopApp(appId: String) = logActionWithoutScreenshot(AgentDriverAction.StopApp(appId)) {
    delegate.stopApp(appId)
  }

  override fun swipe(
    start: Point,
    end: Point,
    durationMs: Long,
  ) = logActionWithScreenshot(
    AgentDriverAction.Swipe(
      direction = inferSwipeDirection(start, end),
      durationMs = durationMs,
      startX = start.x,
      startY = start.y,
      endX = end.x,
      endY = end.y,
    ),
  ) {
    delegate.swipe(
      start = start,
      end = end,
      durationMs = durationMs,
    )
  }

  /**
   * Infers the primary swipe direction from start and end points.
   * Used for logging when direction is not explicitly provided.
   */
  private fun inferSwipeDirection(
    start: Point,
    end: Point,
  ): String {
    val deltaX = end.x - start.x
    val deltaY = end.y - start.y

    // Determine primary direction based on which axis has larger movement
    return if (kotlin.math.abs(deltaX) > kotlin.math.abs(deltaY)) {
      // Horizontal swipe
      if (deltaX > 0) "RIGHT" else "LEFT"
    } else {
      // Vertical swipe
      if (deltaY > 0) "DOWN" else "UP"
    }
  }

  override fun swipe(elementPoint: Point, direction: SwipeDirection, durationMs: Long) = logActionWithScreenshot(AgentDriverAction.Swipe(direction.name, durationMs)) {
    delegate.swipe(
      elementPoint = elementPoint,
      direction = direction,
      durationMs = durationMs,
    )
  }

  override fun swipe(swipeDirection: SwipeDirection, durationMs: Long) = logActionWithScreenshot(AgentDriverAction.Swipe(swipeDirection.name, durationMs)) {
    delegate.swipe(
      swipeDirection = swipeDirection,
      durationMs = durationMs,
    )
  }

  override fun takeScreenshot(out: Sink, compressed: Boolean) = traceMaestroDriver("takeScreenshot") {
    delegate.takeScreenshot(
      out = out,
      compressed = compressed,
    )
  }

  override fun tap(point: Point) {
    val action = AgentDriverAction.TapPoint(point.x, point.y)
    if (captureScreenPerTap) {
      logActionWithScreenshot(action) { delegate.tap(point) }
    } else {
      logActionWithoutScreenshot(action, gridDimensions = true) { delegate.tap(point) }
    }
  }

  override fun waitForAppToSettle(
    initialHierarchy: ViewHierarchy?,
    appId: String?,
    timeoutMs: Int?,
  ): ViewHierarchy? = traceMaestroDriver("waitForAppToSettle") {
    delegate.waitForAppToSettle(
      initialHierarchy = initialHierarchy,
      appId = appId,
      timeoutMs = timeoutMs,
    )
  }

  override fun waitUntilScreenIsStatic(timeoutMs: Long): Boolean = traceMaestroDriver("waitUntilScreenIsStatic") {
    delegate.waitUntilScreenIsStatic(timeoutMs)
  }

  override fun longPress(point: Point) = logActionWithScreenshot(AgentDriverAction.LongPressPoint(point.x, point.y)) {
    delegate.longPress(point)
  }

  override fun name(): String = traceMaestroDriver("name") { delegate.name() }

  override fun open() = traceMaestroDriver("open") { delegate.open() }

  override fun openLink(
    link: String,
    appId: String?,
    autoVerify: Boolean,
    browser: Boolean,
  ) = traceMaestroDriver("openLink") {
    delegate.openLink(
      link = link,
      appId = appId,
      autoVerify = autoVerify,
      browser = browser,
    )
  }

  override fun pressKey(code: KeyCode) = traceMaestroDriver("pressKey") { delegate.pressKey(code) }

  override fun resetProxy() = traceMaestroDriver("resetProxy") { delegate.resetProxy() }

  override fun scrollVertical() = traceMaestroDriver("scrollVertical") { delegate.scrollVertical() }

  companion object {

    /**
     * Only use this if you need to temporarily override what will be included in [ScreenState].
     *
     * Example: This is used to "double-tap" quickly in the iOS Debug Menu because it is too slow otherwise.
     */
    private data class TemporaryStaticScreenStateData(
      val reason: String,
      val screenState: ScreenState?,
    )

    /** Static [ScreenState] to use temporarily. */
    private var temporaryStaticScreenStateData: TemporaryStaticScreenStateData? = null

  }
}
