package xyz.block.trailblaze.android.maestro

import android.util.Log
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.uiautomator.UiDeviceExt.clickExt
import maestro.Capability
import maestro.DeviceInfo
import maestro.Driver
import maestro.KeyCode
import maestro.Platform
import maestro.Point
import maestro.ScreenRecording
import maestro.SwipeDirection
import maestro.TreeNode
import maestro.ViewHierarchy
import maestro.utils.ScreenshotUtils
import okio.Sink
import okio.buffer
import okio.gzip
import xyz.block.trailblaze.AdbCommandUtil
import xyz.block.trailblaze.InstrumentationUtil
import xyz.block.trailblaze.InstrumentationUtil.withInstrumentation
import xyz.block.trailblaze.InstrumentationUtil.withUiAutomation
import xyz.block.trailblaze.InstrumentationUtil.withUiDevice
import xyz.block.trailblaze.android.MaestroUiAutomatorXmlParser
import xyz.block.trailblaze.android.uiautomator.AndroidOnDeviceUiAutomatorScreenState
import xyz.block.trailblaze.setofmark.android.AndroidBitmapUtils.toByteArray
import java.io.File
import java.util.UUID
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * This is Trailblaze's Maestro on-device driver implementation for Android using UiAutomator.
 */
internal class MaestroAndroidUiAutomatorDriver : Driver {

  override fun addMedia(mediaFiles: List<File>) {
    error("Unsupported Maestro Driver Call to ${this::class.simpleName}::addMedia $mediaFiles")
  }

  override fun backPress() {
    withUiDevice { pressBack() }
  }

  override fun capabilities(): List<Capability> {
    // These are default for Android
    return listOf(Capability.FAST_HIERARCHY)
  }

  override fun clearAppState(appId: String) {
    AdbCommandUtil.clearPackageData(appId)
  }

  override fun clearKeychain() {
    error("Unsupported Maestro Driver Call to ${this::class.simpleName}::clearKeychain")
  }

  override fun close() {
    error("Unsupported Maestro Driver Call to ${this::class.simpleName}::close")
  }

  override fun contentDescriptor(excludeKeyboardElements: Boolean): TreeNode = MaestroUiAutomatorXmlParser.getUiAutomatorViewHierarchyFromViewHierarchyAsMaestroTreeNodes(
    viewHiearchyXml = AndroidOnDeviceUiAutomatorScreenState.dumpViewHierarchy(),
    excludeKeyboardElements = false,
  )

  /**
   * I was going to compute this a single time, but then I realized the device could be resized or rotated which
   * would invalidate the cached value.
   */
  override fun deviceInfo(): DeviceInfo = withUiDevice {
    DeviceInfo(
      platform = Platform.ANDROID,
      widthPixels = displayWidth,
      heightPixels = displayHeight,
      widthGrid = displayWidth,
      heightGrid = displayHeight,
    )
  }

  override fun eraseText(charactersToErase: Int) {
    (0..charactersToErase).forEach { i ->
      withUiDevice { pressDelete() }
    }
  }

  override fun hideKeyboard() {
    if (isKeyboardVisible()) {
      backPress()
    }
  }

  override fun inputText(text: String) {
    InstrumentationUtil.inputTextFast(text)
    if (isKeyboardVisible()) {
      hideKeyboard()
    }
  }

  override fun isAirplaneModeEnabled(): Boolean {
    error("Unsupported Maestro Driver Call to ${this::class.simpleName}::isAirplaneModeEnabled")
  }

  override fun isKeyboardVisible(): Boolean = withUiAutomation {
    windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
  }

  override fun isShutdown(): Boolean {
    error("Unsupported Maestro Driver Call to ${this::class.simpleName}::isShutdown")
  }

  override fun isUnicodeInputSupported(): Boolean = true

  override fun killApp(appId: String) {
    AdbCommandUtil.forceStopApp(appId)
  }

  override fun launchApp(
    appId: String,
    launchArguments: Map<String, Any>,
    sessionId: UUID?,
  ) {
    val context = withInstrumentation { context }
    val intent = context.packageManager.getLaunchIntentForPackage(appId)

    if (intent == null) {
      Log.e("Maestro", "No launcher intent found for package $appId")
      return
    }

    launchArguments.mapValues { it.value as String }.forEach { (key, value) ->
      when (value::class.java.name) {
        String::class.java.name -> intent.putExtra(key, value)
        Boolean::class.java.name -> intent.putExtra(key, value.toBoolean())
        Int::class.java.name -> intent.putExtra(key, value.toInt())
        Double::class.java.name -> intent.putExtra(key, value.toDouble())
        Long::class.java.name -> intent.putExtra(key, value.toLong())
        else -> intent.putExtra(key, value)
      }
    }

    context.startActivity(intent)
    AdbCommandUtil.waitUntilAppInForeground(appId)
  }

  override fun longPress(point: Point) {
    // Use the swipe gesture to fake a long press
    // Same starting and ending points should resolve as a tap
    // 100 steps is ~0.5 seconds
    withUiDevice {
      swipe(
        point.x,
        point.y,
        point.x,
        point.y,
        100,
      )
    }
  }

  override fun name(): String {
    error("Unsupported Maestro Driver Call to ${this::class.simpleName}::name")
  }

  override fun open() {
    error("Unsupported Maestro Driver Call to ${this::class.simpleName}::open")
  }

  override fun openLink(
    link: String,
    appId: String?,
    autoVerify: Boolean,
    browser: Boolean,
  ) {
    error("Unsupported Maestro Driver Call to ${this::class.simpleName}::openLink $link, $appId, $autoVerify, $browser")
  }

  override fun pressKey(code: KeyCode) {
    val intCode: Int = when (code) {
      KeyCode.ENTER -> 66
      KeyCode.BACKSPACE -> 67
      KeyCode.BACK -> 4
      KeyCode.VOLUME_UP -> 24
      KeyCode.VOLUME_DOWN -> 25
      KeyCode.HOME -> 3
      KeyCode.LOCK -> 276
      KeyCode.REMOTE_UP -> 19
      KeyCode.REMOTE_DOWN -> 20
      KeyCode.REMOTE_LEFT -> 21
      KeyCode.REMOTE_RIGHT -> 22
      KeyCode.REMOTE_CENTER -> 23
      KeyCode.REMOTE_PLAY_PAUSE -> 85
      KeyCode.REMOTE_STOP -> 86
      KeyCode.REMOTE_NEXT -> 87
      KeyCode.REMOTE_PREVIOUS -> 88
      KeyCode.REMOTE_REWIND -> 89
      KeyCode.REMOTE_FAST_FORWARD -> 90
      KeyCode.POWER -> 26
      KeyCode.ESCAPE -> 111
      KeyCode.TAB -> 62
      KeyCode.REMOTE_SYSTEM_NAVIGATION_UP -> 280
      KeyCode.REMOTE_SYSTEM_NAVIGATION_DOWN -> 281
      KeyCode.REMOTE_BUTTON_A -> 96
      KeyCode.REMOTE_BUTTON_B -> 97
      KeyCode.REMOTE_MENU -> 82
      KeyCode.TV_INPUT -> 178
      KeyCode.TV_INPUT_HDMI_1 -> 243
      KeyCode.TV_INPUT_HDMI_2 -> 244
      KeyCode.TV_INPUT_HDMI_3 -> 245
    }

    withUiDevice { pressKeyCode(intCode) }
    Thread.sleep(300)
  }

  override fun resetProxy() {
    error("Unsupported Maestro Driver Call to ${this::class.simpleName}::resetProxy")
  }

  override fun scrollVertical() {
    withUiDevice {
      val xPos = displayWidth / 2
      val startY = (displayHeight * 0.9).toInt()
      val endY = (displayHeight * 0.1).toInt()
      swipe(
        xPos,
        startY,
        xPos,
        endY,
        50,
      )
    }
  }

  override fun setAirplaneMode(enabled: Boolean) {
    error("Unsupported Maestro Driver Call to ${this::class.simpleName}::setAirplaneMode $enabled")
  }

  override fun setLocation(latitude: Double, longitude: Double) {
    error("Unsupported Maestro Driver Call to ${this::class.simpleName}::setLocation $latitude, $longitude")
  }

  override fun setPermissions(appId: String, permissions: Map<String, String>) {
    val permissionsToGrant = permissions.filterValues { it == "allow" }.keys.toSet()
    if (permissionsToGrant.isNotEmpty()) {
      permissionsToGrant.forEach { permission ->
        AdbCommandUtil.grantPermission(appId, permission)
      }
    }
  }

  override fun setProxy(host: String, port: Int) {
    error("Unsupported Maestro Driver Call to ${this::class.simpleName}::setProxy $host, $port")
  }

  override fun startScreenRecording(out: Sink): ScreenRecording {
    error("Unsupported Maestro Driver Call to ${this::class.simpleName}::startScreenRecording $out")
  }

  override fun stopApp(appId: String) = AdbCommandUtil.forceStopApp(appId)

  override fun swipe(start: Point, end: Point, durationMs: Long) = directionalSwipe(durationMs, start, end)

  private fun directionalSwipe(durationMs: Long, start: Point, end: Point) {
    withUiDevice {
      executeShellCommand("input swipe ${start.x} ${start.y} ${end.x} ${end.y} $durationMs")
    }
  }

  override fun swipe(elementPoint: Point, direction: SwipeDirection, durationMs: Long) {
    println("swipe $elementPoint, $direction, $durationMs")
    val deviceInfo = deviceInfo()
    when (direction) {
      SwipeDirection.UP -> {
        val endY = (deviceInfo.heightGrid * 0.1f).toInt()
        directionalSwipe(durationMs, elementPoint, Point(elementPoint.x, endY))
      }

      SwipeDirection.DOWN -> {
        val endY = (deviceInfo.heightGrid * 0.9f).toInt()
        directionalSwipe(durationMs, elementPoint, Point(elementPoint.x, endY))
      }

      SwipeDirection.RIGHT -> {
        val endX = (deviceInfo.widthGrid * 0.9f).toInt()
        directionalSwipe(durationMs, elementPoint, Point(endX, elementPoint.y))
      }

      SwipeDirection.LEFT -> {
        val endX = (deviceInfo.widthGrid * 0.1f).toInt()
        directionalSwipe(durationMs, elementPoint, Point(endX, elementPoint.y))
      }
    }
  }

  override fun swipe(swipeDirection: SwipeDirection, durationMs: Long) {
    val deviceInfo = deviceInfo()
    when (swipeDirection) {
      SwipeDirection.UP -> {
        val startX = (deviceInfo.widthGrid * 0.5f).toInt()
        val startY = (deviceInfo.heightGrid * 0.5f).toInt()
        val endX = (deviceInfo.widthGrid * 0.5f).toInt()
        val endY = (deviceInfo.heightGrid * 0.1f).toInt()
        directionalSwipe(
          durationMs,
          Point(startX, startY),
          Point(endX, endY),
        )
      }

      SwipeDirection.DOWN -> {
        val startX = (deviceInfo.widthGrid * 0.5f).toInt()
        val startY = (deviceInfo.heightGrid * 0.2f).toInt()
        val endX = (deviceInfo.widthGrid * 0.5f).toInt()
        val endY = (deviceInfo.heightGrid * 0.9f).toInt()
        directionalSwipe(
          durationMs,
          Point(startX, startY),
          Point(endX, endY),
        )
      }

      SwipeDirection.RIGHT -> {
        val startX = (deviceInfo.widthGrid * 0.1f).toInt()
        val startY = (deviceInfo.heightGrid * 0.5f).toInt()
        val endX = (deviceInfo.widthGrid * 0.9f).toInt()
        val endY = (deviceInfo.heightGrid * 0.5f).toInt()
        directionalSwipe(
          durationMs,
          Point(startX, startY),
          Point(endX, endY),
        )
      }

      SwipeDirection.LEFT -> {
        val startX = (deviceInfo.widthGrid * 0.9f).toInt()
        val startY = (deviceInfo.heightGrid * 0.5f).toInt()
        val endX = (deviceInfo.widthGrid * 0.1f).toInt()
        val endY = (deviceInfo.heightGrid * 0.5f).toInt()
        directionalSwipe(
          durationMs,
          Point(startX, startY),
          Point(endX, endY),
        )
      }
    }
  }

  @OptIn(ExperimentalEncodingApi::class)
  override fun takeScreenshot(out: Sink, compressed: Boolean) {
    val screenshot = AndroidOnDeviceUiAutomatorScreenState.takeScreenshot(null)
    val finalSink = if (compressed) out.gzip() else out
    finalSink.buffer().use { sink ->
      screenshot?.let { sink.write(screenshot.toByteArray()) }
      screenshot?.recycle()
      sink.flush()
    }
  }

  override fun tap(point: Point) {
    withUiDevice {
      clickExt(
        point.x,
        point.y,
      )
    }
  }

  override fun waitForAppToSettle(
    initialHierarchy: ViewHierarchy?,
    appId: String?,
    timeoutMs: Int?,
  ): ViewHierarchy? {
    withInstrumentation { waitForIdleSync() }
    return ScreenshotUtils.waitForAppToSettle(initialHierarchy, this, timeoutMs)
  }

  override fun waitUntilScreenIsStatic(timeoutMs: Long): Boolean {
    /** From AndroidDriver.kt */
    val screenshotDiffThreshold = 0.005
    return ScreenshotUtils.waitUntilScreenIsStatic(
      timeoutMs = timeoutMs,
      threshold = screenshotDiffThreshold,
      driver = this,
    )
  }
}
