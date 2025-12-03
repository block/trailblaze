package xyz.block.trailblaze

import android.content.pm.PackageManager
import kotlinx.datetime.Clock
import maestro.Point
import xyz.block.trailblaze.InstrumentationUtil.withInstrumentation
import xyz.block.trailblaze.InstrumentationUtil.withUiDevice

object AdbCommandUtil {

  fun execShellCommand(shellCommand: String): String {
    println("adb shell $shellCommand")
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
    println("pidof $appId: $output")
    val isRunning = output.trim().isNotEmpty()
    return isRunning
  }

  fun forceStopApp(
    appId: String,
  ) {
    if (isAppRunning(appId)) {
      execShellCommand("am force-stop $appId")
      tryUntilSuccessOrThrowException(
        maxWaitMs = 30_000,
        intervalMs = 200,
        "App $appId should be force stopped",
      ) {
        execShellCommand("dumpsys package $appId | grep stopped=true").contains("stopped=true")
      }
    } else {
      println("App $appId does not have an active process, no need to force stop")
    }
  }

  /**
   * @return true if the condition was met within the timeout, false otherwise
   */
  fun tryUntilSuccessOrThrowException(
    maxWaitMs: Long,
    intervalMs: Long,
    conditionDescription: String,
    condition: () -> Boolean,
  ) {
    val successful = tryUntilSuccessOrTimeout(
      maxWaitMs = maxWaitMs,
      intervalMs = intervalMs,
      conditionDescription = conditionDescription,
      condition = condition,
    )
    if (successful == false) {
      error("Timed out (${maxWaitMs}ms limit) met [$conditionDescription]")
    }
  }

  /**
   * @return true if the condition was met within the timeout, false otherwise
   */
  fun tryUntilSuccessOrTimeout(
    maxWaitMs: Long,
    intervalMs: Long,
    conditionDescription: String,
    condition: () -> Boolean,
  ): Boolean {
    val startTime = Clock.System.now()
    var elapsedTime = 0L
    while (elapsedTime < maxWaitMs) {
      val conditionResult: Boolean = try {
        condition()
      } catch (e: Exception) {
        println("Ignored Exception while computing Condition [$conditionDescription], Exception [${e.message}]")
        false
      }
      if (conditionResult) {
        println("Condition [$conditionDescription] met after ${elapsedTime}ms")
        return true
      } else {
        println("Condition [$conditionDescription] not yet met after ${elapsedTime}ms with timeout of ${maxWaitMs}ms")
        Thread.sleep(intervalMs)
        elapsedTime = Clock.System.now().toEpochMilliseconds() - startTime.toEpochMilliseconds()
      }
    }
    println("Timed out (${maxWaitMs}ms limit) met [$conditionDescription] after ${elapsedTime}ms")
    return false
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
  ): Boolean = tryUntilSuccessOrTimeout(
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
   * which is true on most emulators with Play Store or “Google APIs” images.
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
  ) = tryUntilSuccessOrThrowException(
    maxWaitMs = maxWaitMs,
    intervalMs = checkIntervalMs,
    "App $appId should not be in foreground",
  ) {
    withUiDevice { currentPackageName != appId }
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
}
