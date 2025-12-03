package xyz.block.trailblaze.android.maestro

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.telephony.TelephonyManager
import android.util.Log
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.uiautomator.UiDeviceExt.clickExt
import maestro.Capability
import maestro.DeviceInfo
import maestro.DeviceOrientation
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

/**
 * This is Trailblaze's Maestro on-device driver implementation for Android using UiAutomator.
 */
object MaestroAndroidUiAutomatorDriver : Driver {

  override fun addMedia(mediaFiles: List<File>) {
    error("Unsupported Maestro Driver Call to ${this::class.simpleName}::addMedia $mediaFiles")
  }

  override fun backPress() {
    // Calling our util ensures we get the correct delay after pressing the key
    InstrumentationUtil.pressKey(KeyCode.BACK)
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
    // No delay required for each tap so just directly deleting the number of characters
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
    // Simulate typing
    InstrumentationUtil.inputTextByTyping(text)

    if (isKeyboardVisible()) {
      hideKeyboard()
    }
  }

  /**
   * We need to simulate airplane mode on-device because we can't toggle it programmatically on non rooted devices.
   */
  private fun isSimulatedAirplaneModeEnabled(): Boolean = withInstrumentation {
    val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
    val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    val isWifiOn = wifiManager.isWifiEnabled
    val isDataOn = telephonyManager.isDataEnabled
    val isBluetoothOn = bluetoothManager.adapter.isEnabled

    !isWifiOn && !isDataOn && !isBluetoothOn
  }

  override fun isAirplaneModeEnabled(): Boolean = isSimulatedAirplaneModeEnabled()

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

  override fun setOrientation(orientation: DeviceOrientation) {
    // Disable accelerometer based rotation before overriding orientation
    AdbCommandUtil.execShellCommand("settings put system accelerometer_rotation 0")

    val orientationStr = when (orientation) {
      DeviceOrientation.PORTRAIT -> 0
      DeviceOrientation.LANDSCAPE_LEFT -> 1
      DeviceOrientation.UPSIDE_DOWN -> 2
      DeviceOrientation.LANDSCAPE_RIGHT -> 3
    }
    AdbCommandUtil.execShellCommand("settings put system user_rotation $orientationStr")
  }

  override fun longPress(point: Point) {
    AdbCommandUtil.longPress(point.x, point.y)
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
    withInstrumentation {
      val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        addCategory(Intent.CATEGORY_BROWSABLE)

        if (browser) {
          // Force browser usage
          addCategory(Intent.CATEGORY_APP_BROWSER)
        }

        // Set specific app if provided
        appId?.let { setPackage(it) }
      }

      try {
        context.startActivity(intent)

        // If a specific app was requested, verify it's in foreground
        appId?.let {
          AdbCommandUtil.waitUntilAppInForeground(it)
        }
      } catch (e: Exception) {
        Log.e("Maestro", "Failed to open link: $link", e)
        throw e
      }
    }
  }

  override fun pressKey(code: KeyCode) {
    InstrumentationUtil.pressKey(code)
  }

  override fun resetProxy() {
    error("Unsupported Maestro Driver Call to ${this::class.simpleName}::resetProxy")
  }

  override fun scrollVertical() {
    InstrumentationUtil.scrollVertical(deviceInfo())
  }

  override fun setAirplaneMode(enabled: Boolean) {
    val enableOrDisable = if (enabled) "disable" else "enable"
    val command = listOf(
      "svc wifi $enableOrDisable",
      "svc data $enableOrDisable",
      "svc bluetooth $enableOrDisable",
    )

    for (cmd in command) {
      AdbCommandUtil.execShellCommand(cmd)
    }
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

  override fun swipe(start: Point, end: Point, durationMs: Long) = AdbCommandUtil.directionalSwipe(
    durationMs = durationMs,
    start = start,
    end = end,
  )

  /**
   * Swipes on a specific element in the given direction.
   *
   * Note: SwipeTrailblazeTool does not use this method - it uses relative coordinates instead.
   * This is still required by the Driver interface and may be used by other callers.
   */
  override fun swipe(elementPoint: Point, direction: SwipeDirection, durationMs: Long) {
    InstrumentationUtil.swipe(
      deviceInfo = deviceInfo(),
      elementPoint = elementPoint,
      direction = direction,
      durationMs = durationMs,
    )
  }

  /**
   * Swipes the screen in the given direction from center.
   *
   * Note: SwipeTrailblazeTool does not use this method - it uses relative coordinates instead.
   * This is still required by the Driver interface and may be used by other callers.
   */
  override fun swipe(swipeDirection: SwipeDirection, durationMs: Long) {
    InstrumentationUtil.swipeDirectionAndDuration(
      deviceInfo = deviceInfo(),
      swipeDirection = swipeDirection,
      durationMs = durationMs,
    )
  }

  override fun takeScreenshot(out: Sink, compressed: Boolean) {
    val screenshot = AndroidOnDeviceUiAutomatorScreenState.takeScreenshot(
      viewHierarchy = null,
      setOfMarkEnabled = false,
    )
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
