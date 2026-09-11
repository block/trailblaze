package xyz.block.trailblaze.android.maestro

import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.uiautomator.UiDeviceExt.clickExt
import maestro.Capability
import maestro.DeviceInfo
import maestro.device.DeviceOrientation
import maestro.Driver
import maestro.KeyCode
import maestro.device.Platform
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
import xyz.block.trailblaze.android.accessibility.TrailblazeAccessibilityService
import xyz.block.trailblaze.android.uiautomator.AndroidOnDeviceUiAutomatorScreenState
import xyz.block.trailblaze.android.uiautomator.ComposeSemanticsCollapseDetector
import xyz.block.trailblaze.setofmark.android.AndroidBitmapUtils.toByteArray
import xyz.block.trailblaze.toolcalls.commands.NetworkConnectionTrailblazeTool
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.utils.Ext.toViewHierarchyTreeNode
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

  // Resolve selectors against the standard bulk dump first — it's fast and produces the
  // UiAutomator node shape every instrumentation selector was recorded against. Only when that
  // dump shows a *collapsed* Compose surface (a content pane reduced to a childless host — e.g. a
  // ComposeView inside a permanently-invisible ViewFactoryHolder that dumpWindowHierarchy() skips)
  // do we pay the full refresh()ing accessibility-tree walk to recover it. Healthy captures (the
  // overwhelming majority) keep the bulk dump and its shape, so the broad set of instrumentation
  // trails is untouched; only the broken pane triggers the heavier recovery. Mirrors the
  // collapse-recovery the screen-capture path already applies via recoverCollapsedComposeTree().
  override fun contentDescriptor(excludeKeyboardElements: Boolean): TreeNode {
    val standard = MaestroUiAutomatorXmlParser.getUiAutomatorViewHierarchyFromViewHierarchyAsMaestroTreeNodes(
      viewHiearchyXml = AndroidOnDeviceUiAutomatorScreenState.dumpViewHierarchy(),
      excludeKeyboardElements = excludeKeyboardElements,
    )
    val (deviceWidth, deviceHeight) = withUiDevice { displayWidth to displayHeight }
    val standardVh = standard.toViewHierarchyTreeNode()
    val collapsed = standardVh != null &&
      ComposeSemanticsCollapseDetector.detectCollapsedComposeSurface(standardVh, deviceWidth, deviceHeight) != null
    if (!collapsed) return standard

    // Collapsed: recover through invisible host containers via the refresh()ing accessibility walk.
    val recoveredXml = AndroidOnDeviceUiAutomatorScreenState.dumpViewHierarchyFromAccessibilityTree(visibleOnly = false)
      ?: return standard
    val recovered = MaestroUiAutomatorXmlParser.getUiAutomatorViewHierarchyFromViewHierarchyAsMaestroTreeNodes(
      viewHiearchyXml = recoveredXml,
      excludeKeyboardElements = excludeKeyboardElements,
    )
    // Adopt only if the re-dump actually resolved the collapse and isn't smaller — otherwise the
    // standard dump is no worse than a degenerate walk.
    val recoveredVh = recovered.toViewHierarchyTreeNode()
    val recoveredResolved = recoveredVh != null &&
      ComposeSemanticsCollapseDetector.detectCollapsedComposeSurface(recoveredVh, deviceWidth, deviceHeight) == null &&
      recoveredVh.aggregate().size >= (standardVh?.aggregate()?.size ?: 0)
    return if (recoveredResolved) recovered else standard
  }

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
    repeat(charactersToErase) {
      withUiDevice { pressDelete() }
    }
  }

  override fun hideKeyboard() {
    // Gate backPress() on the looser visibility predicate. The strict windows-only
    // isKeyboardVisible() can miss the IME under FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES,
    // producing silent no-ops (backPress never fires, Orchestra's post-check also reports
    // "not visible", success is wrongly reported). isKeyboardVisible() is left strict
    // because the post-check needs the conservative semantics.
    if (isKeyboardLikelyVisible()) {
      backPress()
    }
  }

  /**
   * Stacked keyboard-visibility check used only by [hideKeyboard], from cheapest to most
   * authoritative:
   *   1. UiAutomation windows / focused-editable
   *   2. [TrailblazeAccessibilityService.isKeyboardVisible] when the service is bound
   *   3. `dumpsys input_method` (`mInputShown=true`) — authoritative shell fallback for
   *      instrumentation runs under `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`, where
   *      UiAutomation can return `windows=[]` / `root=null`.
   *
   * Not exposed as a driver override because the strict windows-only [isKeyboardVisible]
   * is what Maestro's post-check at `Orchestra.hideKeyboardCommand` relies on — the
   * looser signals (focused-editable, IME service state) linger after IME dismissal and
   * would produce false-positive post-check failures there.
   */
  private fun isKeyboardLikelyVisible(): Boolean {
    val viaUiAutomation = withUiAutomation {
      val ws = windows
      if (!ws.isNullOrEmpty() && ws.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }) {
        return@withUiAutomation true
      }
      val root = rootInActiveWindow ?: return@withUiAutomation false
      try {
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val editable = focused?.isEditable == true
        focused?.recycle()
        editable
      } finally {
        root.recycle()
      }
    }
    if (viaUiAutomation) return true
    try {
      if (TrailblazeAccessibilityService.isKeyboardVisible()) return true
    } catch (_: IllegalStateException) {
      // Service not running — fall through to the shell fallback.
    }
    return try {
      AdbCommandUtil
        .execShellCommand("dumpsys input_method")
        .lineSequence()
        .any { it.trim().startsWith("mInputShown=true") }
    } catch (e: Exception) {
      Console.log("[hideKeyboard] dumpsys input_method failed: ${e.message}")
      false
    }
  }

  override fun inputText(text: String) {
    InstrumentationUtil.inputTextByTyping(text)
  }

  /**
   * Real airplane mode, read through [AdbCommandUtil.isAirplaneModeEnabled] — the one Android
   * definition of that read, shared with the accessibility driver. See it for why the flag is read
   * directly rather than inferred from the radios, what an unreadable flag answers, and the
   * asymmetry with [setAirplaneMode] that this leaves on `toggleAirplaneMode`.
   */
  override fun isAirplaneModeEnabled(): Boolean = AdbCommandUtil.isAirplaneModeEnabled()

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

  /**
   * Maestro's one-knob `setAirplaneMode`, stood in for by switching the radios
   * [NetworkConnectionTrailblazeTool.androidMaestroAirplaneModeRadioCommands] names — the same set
   * every other Android driver switches.
   *
   * Real airplane mode IS settable from the shell (`cmd connectivity airplane-mode`), and the
   * `networkConnection` tool now does exactly that. It is deliberately not done here: this
   * override is what a raw `mobile_maestro: setAirplaneMode` lowers to, its existing meaning is
   * "take the radios down", and real airplane mode leaves wifi up — so swapping it would leave
   * those trails online.
   *
   * Whether a radio actually switched is not checked, for the reason given on
   * `AccessibilityDeviceManager.executeSetAirplaneMode`.
   */
  override fun setAirplaneMode(enabled: Boolean) {
    NetworkConnectionTrailblazeTool.androidMaestroAirplaneModeRadioCommands(enabled).forEach { (_, command) ->
      AdbCommandUtil.execShellCommand(command)
    }
  }

  override fun setLocation(latitude: Double, longitude: Double) {
    error("Unsupported Maestro Driver Call to ${this::class.simpleName}::setLocation $latitude, $longitude")
  }

  override fun setPermissions(appId: String, permissions: Map<String, String>) {
    val permissionsToGrant = permissions.filterValues { it == "allow" }.keys
    permissionsToGrant.forEach { shortName ->
      MaestroPermissionTranslator.translate(shortName).forEach { fqPermission ->
        AdbCommandUtil.grantPermission(appId, fqPermission)
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
    val screenshot = AndroidOnDeviceUiAutomatorScreenState.takeScreenshot()
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
