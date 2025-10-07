package xyz.block.trailblaze.android.maestro

import kotlinx.datetime.Clock
import maestro.Capability
import maestro.DeviceInfo
import maestro.Driver
import maestro.KeyCode
import maestro.Point
import maestro.ScreenRecording
import maestro.SwipeDirection
import maestro.TreeNode
import maestro.ViewHierarchy
import okio.Sink
import xyz.block.trailblaze.api.MaestroDriverActionType
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.tracing.TrailblazeTracer.traceRecorder
import java.io.File
import kotlin.system.measureTimeMillis

/**
 * This is a delegate Maestro [Driver] that logs all actions to the [TrailblazeLogger].
 */
class LoggingDriver(
  private val delegate: Driver,
  private val screenStateProvider: () -> ScreenState,
) : Driver {

  private inline fun <T> traceMaestroDriver(name: String, block: () -> T): T = traceRecorder.trace(name, "MaestroDriver", emptyMap(), block = {
    println("Maestro-${delegate::class.java.simpleName}-$name()")
    block()
  })

  /**
   * Wraps the Screen State Provider so that we can use a cached value when requested.
   */
  fun getCurrentScreenState(): ScreenState = temporaryStaticScreenStateData?.let { screenStateTemporarilyDisabledData ->
    println("Temporarily using cached  is currently preloaded because: ${screenStateTemporarilyDisabledData.reason}.")
    screenStateTemporarilyDisabledData.screenState
  } ?: screenStateProvider()

  private inline fun <T> traceMaestroDriverAction(action: MaestroDriverActionType, block: () -> T): T = traceMaestroDriver(action::class.simpleName!!, block)

  private fun logActionWithScreenshot(action: MaestroDriverActionType, block: () -> Unit = {}) {
    val screenState = getCurrentScreenState()
    val startTime = Clock.System.now()

    val executionTimeMs = measureTimeMillis {
      traceMaestroDriverAction(action) {
        block()
      }
    }
    val screenshotFilename = screenState.screenshotBytes?.let { TrailblazeLogger.logScreenshot(it) }
    TrailblazeLogger.log(
      TrailblazeLog.MaestroDriverLog(
        viewHierarchy = screenState.viewHierarchy,
        screenshotFile = screenshotFilename,
        action = action,
        durationMs = executionTimeMs,
        timestamp = startTime,
        session = TrailblazeLogger.getCurrentSessionId(),
        deviceWidth = screenState.deviceWidth,
        deviceHeight = screenState.deviceHeight,
      ),
    )
  }

  private fun logActionWithoutScreenshot(action: MaestroDriverActionType, block: () -> Unit = {}) {
    val deviceInfo = delegate.deviceInfo()
    val startTime = Clock.System.now()
    val executionTimeMs = measureTimeMillis {
      traceMaestroDriverAction(action) {
        block()
      }
    }
    TrailblazeLogger.log(
      TrailblazeLog.MaestroDriverLog(
        viewHierarchy = null,
        screenshotFile = null,
        action = action,
        durationMs = executionTimeMs,
        timestamp = startTime,
        session = TrailblazeLogger.getCurrentSessionId(),
        deviceWidth = deviceInfo.widthPixels,
        deviceHeight = deviceInfo.heightPixels,
      ),
    )
  }

  override fun addMedia(mediaFiles: List<File>) = logActionWithScreenshot(MaestroDriverActionType.AddMedia(mediaFiles.map { it.canonicalPath })) {
    delegate.addMedia(mediaFiles)
  }

  override fun backPress() = logActionWithScreenshot(MaestroDriverActionType.BackPress) {
    delegate.backPress()
  }

  override fun capabilities(): List<Capability> = traceMaestroDriver("capabilities") {
    delegate.capabilities()
  }

  override fun clearAppState(appId: String) = logActionWithoutScreenshot(MaestroDriverActionType.ClearAppState(appId)) {
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

  override fun inputText(text: String) = logActionWithScreenshot(MaestroDriverActionType.EnterText(text)) {
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

  override fun isUnicodeInputSupported(): Boolean = traceMaestroDriver("isUnicodeInputSupported") {
    delegate.isUnicodeInputSupported()
  }

  override fun killApp(appId: String) = logActionWithoutScreenshot(MaestroDriverActionType.KillApp(appId)) {
    delegate.killApp(appId)
  }

  override fun launchApp(
    appId: String,
    launchArguments: Map<String, Any>,
  ) = logActionWithoutScreenshot(MaestroDriverActionType.LaunchApp(appId)) {
    delegate.launchApp(appId, launchArguments)
  }

  override fun setOrientation(orientation: maestro.DeviceOrientation) {
    delegate.setOrientation(orientation)
  }

  override fun setAirplaneMode(enabled: Boolean) = logActionWithoutScreenshot(MaestroDriverActionType.AirplaneMode(enabled)) {
    delegate.setAirplaneMode(enabled)
  }

  override fun setLocation(latitude: Double, longitude: Double) = traceMaestroDriver("setLocation") {
    delegate.setLocation(
      latitude = latitude,
      longitude = longitude,
    )
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

  override fun setProxy(host: String, port: Int) = traceMaestroDriver("startScreenRecording") {
    delegate.setProxy(
      host = host,
      port = port,
    )
  }

  override fun startScreenRecording(out: Sink): ScreenRecording = traceMaestroDriver("startScreenRecording") {
    delegate.startScreenRecording(out)
  }

  override fun stopApp(appId: String) = logActionWithoutScreenshot(MaestroDriverActionType.StopApp(appId)) {
    delegate.stopApp(appId)
  }

  override fun swipe(start: Point, end: Point, durationMs: Long) = traceMaestroDriver("swipe") {
    delegate.swipe(
      start = start,
      end = end,
      durationMs = durationMs,
    )
  }

  override fun swipe(elementPoint: Point, direction: SwipeDirection, durationMs: Long) = logActionWithScreenshot(MaestroDriverActionType.Swipe(direction.name, durationMs)) {
    delegate.swipe(
      elementPoint = elementPoint,
      direction = direction,
      durationMs = durationMs,
    )
  }

  override fun swipe(swipeDirection: SwipeDirection, durationMs: Long) = logActionWithScreenshot(MaestroDriverActionType.Swipe(swipeDirection.name, durationMs)) {
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

  override fun tap(point: Point) = logActionWithScreenshot(MaestroDriverActionType.TapPoint(point.x, point.y)) {
    delegate.tap(point)
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

  override fun longPress(point: Point) = logActionWithScreenshot(MaestroDriverActionType.LongPressPoint(point.x, point.y)) {
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

    /**
     * Allows the work to be done inside this block to be done without screenshots logged
     */
    fun logWithStaticScreenStateTemporarily(
      reason: String,
      screenState: ScreenState?,
      work: () -> Unit,
    ) {
      this.temporaryStaticScreenStateData = TemporaryStaticScreenStateData(
        reason = reason,
        screenState = screenState,
      )
      work()
      this.temporaryStaticScreenStateData = null
    }
  }
}
