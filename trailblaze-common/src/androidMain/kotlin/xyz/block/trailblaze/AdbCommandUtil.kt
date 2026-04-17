package xyz.block.trailblaze

import android.content.pm.PackageManager
import maestro.KeyCode
import maestro.Point
import xyz.block.trailblaze.InstrumentationUtil.withInstrumentation
import xyz.block.trailblaze.InstrumentationUtil.withUiDevice
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.PollingUtils

/**
 * Utility for executing ADB shell commands via UiAutomation.
 * This works when running as an instrumentation test on an Android device.
 */
object AdbCommandUtil {

  fun execShellCommand(shellCommand: String): String {
    Console.log("adb shell $shellCommand")
    return withUiDevice {
      executeShellCommand(shellCommand)
    }
  }

  fun grantPermission(targetAppPackageName: String, permission: String) {
    if (!isPermissionGranted(
        permission = permission,
        packageName = targetAppPackageName,
      )
    ) {
      execShellCommand("pm grant $targetAppPackageName $permission")
    }
  }

  fun getSerialNumber(): String {
    return execShellCommand("getprop ro.boot.serialno")
  }

  fun grantPermissions(targetAppPackageName: String, permissions: List<String>) {
    permissions.forEach { permission ->
      grantPermission(targetAppPackageName, permission)
    }
  }

  private fun isPermissionGranted(permission: String, packageName: String): Boolean {
    val isGranted = withInstrumentation {
      context
        .packageManager.checkPermission(permission, packageName) == PackageManager.PERMISSION_GRANTED
    }
    return isGranted
  }

  fun clearPackageData(targetAppPackageName: String) {
    execShellCommand("pm clear $targetAppPackageName")
  }

  fun listInstalledApps(): List<String> = withInstrumentation {
    val packageManager = context.packageManager
    val installedPackages = packageManager.getInstalledApplications(0)
    installedPackages.map { it.packageName }
  }

  fun isAppRunning(appId: String): Boolean {
    val output = execShellCommand("pidof $appId")
    Console.log("pidof $appId: $output")
    val isRunning = output.trim().isNotEmpty()
    return isRunning
  }

  fun forceStopApp(
    appId: String,
  ) {
    if (isAppRunning(appId)) {
      execShellCommand("am force-stop $appId")
      PollingUtils.tryUntilSuccessOrThrowException(
        maxWaitMs = 30_000,
        intervalMs = 200,
        "App $appId should be force stopped",
      ) {
        execShellCommand("dumpsys package $appId | grep stopped=true").contains("stopped=true")
      }
    } else {
      Console.log("App $appId does not have an active process, no need to force stop")
    }
  }

  fun grantAppOpsPermission(
    targetAppPackageName: String,
    permission: String,
  ): String {
    val shellCommand = "appops set $targetAppPackageName $permission allow"
    return execShellCommand(shellCommand)
  }

  /**
   * Wait for app to come to foreground
   */
  fun waitUntilAppInForeground(
    appId: String,
    maxWaitMs: Long = 30_000,
    checkIntervalMs: Long = 200,
  ): Boolean = PollingUtils.tryUntilSuccessOrTimeout(
    maxWaitMs = maxWaitMs,
    intervalMs = checkIntervalMs,
    conditionDescription = "App $appId should be in foreground",
  ) {
    withUiDevice { currentPackageName == appId }
  }

  /**
   * Disable Assistant from UiAutomator
   *
   * This is helpful on API 28 devices.  Clicking on items at the bottom of the screen
   * can sometimes trigger the Google Assistant which we don't want in our tests.
   */
  fun disableAssistant() {
    execShellCommand("settings put secure assistant null")
    execShellCommand("settings put secure voice_interaction_service null")
  }

  /**
   * Enables the Google Assistant
   *
   * Note: This assumes the Google App is installed,
   * which is true on most emulators with Play Store or "Google APIs" images.
   */
  fun enableAssistant() {
    execShellCommand("settings put secure assistant com.google.android.googlequicksearchbox/com.google.android.voiceinteraction.GsaVoiceInteractionService")
    execShellCommand("settings put secure voice_interaction_service com.google.android.googlequicksearchbox/com.google.android.voiceinteraction.GsaVoiceInteractionService")
  }

  enum class StatusAndNavBarMode(val value: String) {
    FULLSCREEN("full"),
    TOP_STATUS_BAR("status"),
    BOTTOM_NAV_BAR("navigation"),
  }

  /** Enable Immersive Mode (Hide Nav Bar) */
  fun hideStatusAndNavBar(
    mode: StatusAndNavBarMode,
    /** If '*' then it'll do it for ALL apps */
    appId: String = "*",
  ) {
    execShellCommand("settings put global policy_control immersive.${mode.value}=$appId")
  }

  /** Disable Immersive Mode (Show Nav Bar) */
  fun showVirtualBottomNavBar() {
    execShellCommand("settings put global policy_control null")
  }

  /**
   * Disable gesture navigation and enable 3-button navigation.
   * This prevents swipe gestures from accidentally navigating back to the home screen.
   *
   * Navigation modes:
   * - 0 = 3-button navigation (traditional)
   * - 1 = 2-button navigation (deprecated)
   * - 2 = Gesture navigation (fully gestural)
   */
  fun enableThreeButtonNavigation() {
    execShellCommand("settings put secure navigation_mode 0")
    execShellCommand("settings put global policy_control immersive.navigation=*")
  }

  /**
   * Re-enable gesture navigation (default on modern Android versions).
   */
  fun enableGestureNavigation() {
    execShellCommand("cmd overlay enable com.android.internal.systemui.navbar.gestural")
    execShellCommand("settings put secure navigation_mode 2")
  }

  /**
   * Wait for app to not be in the foreground
   */
  fun waitUntilAppNotInForeground(
    appId: String,
    maxWaitMs: Long = 30_000,
    checkIntervalMs: Long = 200,
  ) = PollingUtils.tryUntilSuccessOrThrowException(
    maxWaitMs = maxWaitMs,
    intervalMs = checkIntervalMs,
    "App $appId should not be in foreground",
  ) {
    withUiDevice { currentPackageName != appId }
  }

  /**
   * Sends a key event via `input keyevent`. Shared by both the instrumentation driver
   * ([InstrumentationUtil.pressKey]) and the accessibility driver so the KeyCode-to-Android
   * keycode mapping lives in one place.
   *
   * Does NOT add any post-press delay — callers that need one (e.g. Maestro's 300ms convention)
   * should sleep after calling this.
   */
  fun pressKey(code: KeyCode) {
    val androidKeyCode: Int = when (code) {
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
    execShellCommand("input keyevent $androidKeyCode")
  }

  /**
   * Matches Maestro's Implementation
   * https://github.com/mobile-dev-inc/Maestro/blob/0a38a9468cb769ecbc1edc76974fd2f8a8b0b64e/maestro-client/src/main/java/maestro/drivers/AndroidDriver.kt#L508-L512
   */
  fun directionalSwipe(durationMs: Long, start: Point, end: Point) {
    execShellCommand("input swipe ${start.x} ${start.y} ${end.x} ${end.y} $durationMs")
  }

  /**
   * Matches Maestro's Implementation with a 3 second long press
   * https://github.com/mobile-dev-inc/Maestro/blob/0a38a9468cb769ecbc1edc76974fd2f8a8b0b64e/maestro-client/src/main/java/maestro/drivers/AndroidDriver.kt#L284
   */
  fun longPress(x: Int, y: Int) {
    execShellCommand("input swipe $x $y $x $y 3000")
  }

  /**
   * Returns the short class name of the current foreground Activity (e.g. "HomeActivity"),
   * or null if it cannot be determined.
   *
   * Parses the output of `dumpsys window | grep mCurrentFocus`, which looks like:
   * `  mCurrentFocus=Window{abc u0 com.example.app/com.example.app.HomeActivity}`
   *
   * The command is fast — the window manager state is in-memory — so the overhead is
   * negligible relative to a view hierarchy dump.
   */
  fun getForegroundActivity(): String? = try {
    val output = execShellCommand("dumpsys window | grep mCurrentFocus")
    val line = output.lines().firstOrNull { it.contains("mCurrentFocus=") } ?: return null
    val braceContent = line.substringAfter("{", "").substringBefore("}", "")
    val component = braceContent.trim().split(" ").lastOrNull()?.takeIf { it.contains("/") }
      ?: return null
    val activityClass = component.substringAfter("/").trimStart('.')
    activityClass.substringAfterLast('.').takeIf { it.isNotBlank() && it != "null" }
  } catch (_: Exception) {
    null
  }
}
