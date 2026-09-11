package xyz.block.trailblaze

import android.app.UiAutomation
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import maestro.KeyCode
import maestro.Point
import xyz.block.trailblaze.InstrumentationUtil.withInstrumentation
import xyz.block.trailblaze.InstrumentationUtil.withUiAutomation
import xyz.block.trailblaze.device.AndroidForegroundParser
import xyz.block.trailblaze.device.AndroidShellBounds
import xyz.block.trailblaze.device.BoundedReadTimeoutException
import xyz.block.trailblaze.device.InstalledApp
import xyz.block.trailblaze.device.redactBulkPayloadsForLog
import xyz.block.trailblaze.device.PM_LIST_PACKAGES_ARGV
import xyz.block.trailblaze.device.PmClearOutcome
import xyz.block.trailblaze.device.readWithDeadline
import xyz.block.trailblaze.device.validateClearAppDataAppId
import xyz.block.trailblaze.device.verifyPmClearSucceeded
import xyz.block.trailblaze.toolcalls.commands.NetworkConnectionTrailblazeTool
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.PollingUtils
import xyz.block.trailblaze.util.UiAutomationHandleErrors

/**
 * Utility for executing ADB shell commands via UiAutomation.
 * This works when running as an instrumentation test on an Android device.
 */
object AdbCommandUtil {

  private const val SHELL_LIVENESS_TOKEN = "trailblaze-shell-liveness"

  /**
   * Hang detection for a single shell command: the point at which the command is treated as never
   * coming back. Shared with the tool-level dispatch bound that has to stay above it — see
   * [AndroidShellBounds].
   */
  private const val SHELL_COMMAND_TIMEOUT_MS = AndroidShellBounds.SHELL_READ_TIMEOUT_MS

  /**
   * The point at which a command is *reported* as slow, without failing it.
   *
   * Almost nothing issued through here should come close: these are `settings` / `input` / `pm` /
   * `dumpsys` one-liners that finish in well under a second on a healthy device. The one known
   * exception is `pm clear` on an Android tablet running turbo, which crosses this routinely — and
   * that is the point. A slow shell call is otherwise charged to whichever Trailblaze tool
   * happened to make it: the tool's duration absorbs the wait, nothing names the command, and the
   * run just looks inexplicably slow. That is exactly how that ~150s `pm clear` stayed unexplained
   * across several builds.
   *
   * Reported, never thrown, so it cannot turn a slow pass into a red build — the only failing
   * bound here is [SHELL_COMMAND_TIMEOUT_MS].
   *
   * Where this line lands depends on the lane. It is a plain logcat write, so a local or
   * Gradle-driven run sees it; the on-device CI path does not collect logcat at all, and the
   * host-driven path filters logcat to the app under test, which this instrumentation process is
   * not. So treat it as a local debugging aid, not as the CI signal. (The nearby note on
   * [runShellCommand] about logcat being captured into session artifacts is about that
   * host-driven, app-filtered capture — the two are not in conflict.)
   */
  private const val SHELL_COMMAND_SLOW_MS = 10_000L

  fun execShellCommand(shellCommand: String): String {
    // Redact once, then pass the redacted copy everywhere it is needed: `writeFileAs` carries a
    // file's bytes base64-encoded inside the command line, and logcat is a captured CI artifact —
    // so an unredacted log of that command line is a log of a seeded auth/session file. Redacting
    // is also not free (three regex passes plus a base64 decode of the whole payload), and this
    // runs on every device action, so it happens once per command rather than once per use.
    val loggableCommand = redactBulkPayloadsForLog(shellCommand)
    Console.log("adb shell $loggableCommand")
    val output = runShellCommand(shellCommand, loggableCommand)
    // A dead UiAutomation connection makes the shell call return "" instead of throwing, so every
    // command looks successful while doing nothing. Empty output is also normal for many commands
    // (`cp`, `input keyevent`), so double-check with a probe that always prints: if even that comes
    // back empty, the connection is wedged — throw so the standard reconnect-and-retry runs.
    val livenessProbe = "echo $SHELL_LIVENESS_TOKEN"
    if (output.isEmpty() && !runShellCommand(livenessProbe, livenessProbe).contains(SHELL_LIVENESS_TOKEN)) {
      throw IllegalStateException(UiAutomationHandleErrors.silentShellWedgeMessage(loggableCommand))
    }
    return output
  }

  /**
   * Runs [shellCommand] through [UiAutomation.executeShellCommand] directly rather than
   * [androidx.test.uiautomator.UiDevice.executeShellCommand].
   *
   * UiDevice's wrapper is the same two lines plus `Log.d(TAG, "Executing shell command: %s")`, and
   * that log is the one place [redactBulkPayloadsForLog] cannot reach — it would put the base64
   * file body of every `writeFileAs` into logcat, which is captured into session artifacts by
   * default. Going one layer down keeps the payload out of the log entirely instead of redacting a
   * copy of it.
   *
   * Reading the whole stream before decoding also fixes a latent UiDevice bug: it decodes each
   * 512-byte chunk separately, so a multi-byte UTF-8 character straddling a chunk boundary comes
   * back mangled.
   *
   * The read is bounded by [SHELL_COMMAND_TIMEOUT_MS]. It has to be bounded here rather than by
   * the caller: this read runs inside `withUiAutomation`, which holds the process-wide UiAutomation
   * monitor, so a caller that gave up on a wedged command would leave that monitor held and every
   * later device action queued behind it. [readWithDeadline] closes the stream instead, which ends
   * the read in place and releases the monitor on the way out.
   *
   * Note that one caller bounds it from outside as well:
   * [xyz.block.trailblaze.mobile.tools.AdbShellTrailblazeTool] wraps the `android_adbShell` tool's
   * dispatch in a `withTimeoutOrNull`. That outer bound abandons the reader rather than ending it,
   * so it is deliberately set ABOVE this one — see
   * [xyz.block.trailblaze.device.AndroidShellBounds.ON_DEVICE_DISPATCH_TIMEOUT_MS] — and this bound
   * is the one that actually lands.
   *
   * A timeout drops the cached UiAutomation handle and rethrows
   * [UiAutomationHandleErrors.wedgedShellReadMessage], which nothing retries. Leaving the dead
   * connection cached would make every later command pay the whole bound again; retrying instead
   * would replay a command that may already have taken effect.
   *
   * @param loggableCommand [shellCommand] already redacted for logging — see [execShellCommand].
   */
  private fun runShellCommand(shellCommand: String, loggableCommand: String): String = withUiAutomation {
    val description = "adb shell $loggableCommand"
    val startedAt = SystemClock.elapsedRealtime()
    val output = try {
      ParcelFileDescriptor.AutoCloseInputStream(executeShellCommand(shellCommand)).use { stream ->
        readWithDeadline(
          description = description,
          timeoutMs = SHELL_COMMAND_TIMEOUT_MS,
          cancel = { stream.close() },
          read = { stream.readBytes().toString(Charsets.UTF_8) },
        )
      }
    } catch (timeout: BoundedReadTimeoutException) {
      // Drop the wedged connection here rather than by raising a stale-handle signature: that
      // signature's recovery replays the command, and this command may already have taken effect.
      // See `UiAutomationHandleErrors.wedgedShellReadMessage`. Safe to do from inside
      // `withUiAutomation` — this thread holds the monitor, so no one else is mid-command.
      val discarded = InstrumentationUtil.clearInstrumentationUiAutomationCache()
      throw IllegalStateException(
        UiAutomationHandleErrors.wedgedShellReadMessage(
          command = description,
          timeoutMs = SHELL_COMMAND_TIMEOUT_MS,
          handleDiscarded = discarded,
        ),
        timeout,
      )
    }
    val elapsedMs = SystemClock.elapsedRealtime() - startedAt
    if (elapsedMs >= SHELL_COMMAND_SLOW_MS) {
      Console.log("slow shell command: ${elapsedMs}ms for $description")
    }
    output
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

  /**
   * Whether real airplane mode is on. The one Android definition of this read, so a trail cannot
   * get a different answer depending on which driver replays it.
   *
   * Deliberately NOT inferred from the radios. `TelephonyManager.isDataEnabled` and the
   * `mobile_data` setting behind it are the USER'S PREFERENCE for mobile data, which Android never
   * rewrites when airplane mode goes on, and wifi routinely stays up through airplane mode — so a
   * radios-based read answers "off" on a device genuinely in airplane mode, which is the failure
   * this replaces.
   *
   * An unreadable VALUE answers `false`, per
   * [NetworkConnectionTrailblazeTool.androidAirplaneModeSettingMeansOnOrOff]; the raw value is
   * logged so a broken read stays diagnosable. A failed READ is different and still throws:
   * [execShellCommand] raises on a wedged UiAutomation connection, which is the signal that drives
   * the standard reconnect-and-retry, and swallowing it here would report a device as not in
   * airplane mode on the strength of a shell that answered nothing at all.
   *
   * Note the asymmetry this creates with the drivers' Maestro `setAirplaneMode`, which switches the
   * radios as a stand-in rather than setting the flag. A `toggleAirplaneMode` therefore reads this
   * flag and writes the radios: from a flag-off device it takes the radios down and leaves the flag
   * off, so a second toggle takes them down again rather than restoring them. Reading the radios
   * back instead would make the toggle reversible by answering the wrong question, and would report
   * a device genuinely in airplane mode as not being in it. A trail that needs the radios back
   * should say so with `networkConnection`, which names each radio and the flag separately.
   */
  fun isAirplaneModeEnabled(): Boolean {
    val raw = execShellCommand(
      NetworkConnectionTrailblazeTool.androidSettingReadCommand(
        NetworkConnectionTrailblazeTool.ANDROID_AIRPLANE_MODE_SETTING,
      ),
    )
    val answer = NetworkConnectionTrailblazeTool.androidAirplaneModeSettingMeansOnOrOff(raw)
    // Logged on every read, not just the unreadable one. `execShellCommand` logs the command but
    // never its output, so without this a wrong-but-readable answer — "0" on a device a trail
    // believes is offline — leaves nothing behind to explain what the driver decided.
    val unreadable = NetworkConnectionTrailblazeTool.androidAirplaneModeSettingMeansOn(raw) == null
    Console.log(
      "[airplaneMode] ${NetworkConnectionTrailblazeTool.ANDROID_AIRPLANE_MODE_SETTING} read " +
        "'${raw.trim()}' -> $answer" +
        if (unreadable) " (says nothing readable — reporting airplane mode off)" else "",
    )
    return answer
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

  fun clearPackageData(targetAppPackageName: String): PmClearOutcome {
    // Before the id is interpolated into a shell command, not after — see
    // `validateClearAppDataAppId`.
    validateClearAppDataAppId(targetAppPackageName)
    // `pm clear`'s stdout token is the only outcome signal this transport has — UiAutomation's
    // executeShellCommand carries no exit status. See `verifyPmClearSucceeded`.
    return verifyPmClearSucceeded(
      appId = targetAppPackageName,
      output = execShellCommand("pm clear $targetAppPackageName"),
      // Through the shell, NOT [listInstalledApps]: that reads `PackageManager` from our own
      // process, where package visibility is filtered on API 30+. In a consuming APK without
      // QUERY_ALL_PACKAGES an installed package would read as absent, and the failed clear this
      // check exists to report would be tolerated instead. The shell runs as uid 2000, which
      // filtering does not apply to.
      pmListPackagesOutput = { execShellCommand(PM_LIST_PACKAGES_ARGV.joinToString(" ")) },
    )
  }

  fun listInstalledApps(): List<String> = withInstrumentation {
    val packageManager = context.packageManager
    val installedPackages = packageManager.getInstalledApplications(0)
    installedPackages.map { it.packageName }
  }

  /**
   * On-device backing for
   * [xyz.block.trailblaze.device.AndroidDeviceCommandExecutor.listInstalledAppsDetailed].
   *
   * Uses a **single** `getInstalledPackages(0)` call: each `PackageInfo` already carries the
   * version (`versionName`), build number (`versionCode`), and its `applicationInfo` (for
   * `FLAG_SYSTEM` → [InstalledApp.isSystemApp] and `sourceDir` → [InstalledApp.installPath]). That
   * avoids an O(N) `getPackageInfo` Binder fan-out (one call for the whole inventory). The display
   * name still resolves per app via `getApplicationLabel`, but that reads resources locally rather
   * than crossing the Binder, and it's only done when [includeLabelsAndVersions] is `true`. All
   * reads are defensive — a single app whose resources fail to resolve leaves that field `null`
   * instead of aborting the whole inventory.
   *
   * `isSystemApp` also covers `FLAG_UPDATED_SYSTEM_APP`, so a system app that received a Play-Store
   * update still classifies as system.
   */
  fun listInstalledAppsDetailed(includeLabelsAndVersions: Boolean): List<InstalledApp> = withInstrumentation {
    val packageManager = context.packageManager
    packageManager.getInstalledPackages(0).map { packageInfo ->
      val appInfo = packageInfo.applicationInfo
      val isSystem = appInfo != null && (
        (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
          (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        )
      val installPath = appInfo?.sourceDir?.takeIf { it.isNotBlank() }
      val label = if (includeLabelsAndVersions && appInfo != null) {
        runCatching { packageManager.getApplicationLabel(appInfo).toString() }
          .getOrNull()
          ?.takeIf { it.isNotBlank() }
      } else {
        null
      }
      InstalledApp(
        appId = packageInfo.packageName,
        isSystemApp = isSystem,
        label = label,
        version = if (includeLabelsAndVersions) packageInfo.versionName else null,
        buildNumber = if (includeLabelsAndVersions) longVersionCode(packageInfo).toString() else null,
        installPath = installPath,
      )
    }
  }

  /**
   * Reads the package's version code as a [Long], using the API 28+ `longVersionCode` accessor where
   * available and falling back to the deprecated `versionCode` on older devices. (Backs
   * [InstalledApp.buildNumber] on the on-device path.)
   */
  @Suppress("DEPRECATION")
  private fun longVersionCode(packageInfo: android.content.pm.PackageInfo): Long =
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
      packageInfo.longVersionCode
    } else {
      packageInfo.versionCode.toLong()
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
        val stopped =
          execShellCommand("dumpsys package $appId | grep stopped=true").contains("stopped=true")
        if (!stopped) {
          // The app can be restarted between the kill and this check (e.g. a JobScheduler
          // reschedule already in flight), which clears the stopped flag — once that happens the
          // flag never becomes true on its own. force-stop is idempotent, so re-issue it instead
          // of polling a condition that can no longer be met.
          execShellCommand("am force-stop $appId")
        }
        stopped
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
    isAppInForeground(appId)
  }

  /**
   * Whether [appId] currently has a resumed activity, matched against EVERY resumed task so
   * split-screen / multi-display can't hide it. Errors read as "not in foreground".
   *
   * Reads the reliable resumed-activity signal (see [getForegroundPackage]) rather than
   * `UiDevice.currentPackageName`, which polls the flaky UiAutomation window list and on some
   * images never reports a launching app during a cold start.
   */
  fun isAppInForeground(appId: String): Boolean =
    getForegroundComponents().any { AndroidForegroundParser.packageFromComponent(it) == appId }

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
    getForegroundComponents().none { AndroidForegroundParser.packageFromComponent(it) == appId }
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
   * Returns the `package/activity` components of every resumed task (one per visible task in
   * split-screen / multi-display, usually exactly one), or empty if they can't be read.
   *
   * Reads the resumed (top) activities from `dumpsys activity activities`. The parsing lives in
   * [AndroidForegroundParser] so it is unit-tested without a device; the rationale for the
   * resumed-activity signal over window focus is documented there.
   */
  private fun getForegroundComponents(): List<String> = try {
    // No shell pipe: [execShellCommand] runs via UiAutomation, which does NOT go through `sh -c`,
    // so a `| grep …` would be passed to `dumpsys` as literal args (a package filter) and return
    // nothing. Fetch the full dump and let [AndroidForegroundParser] filter the lines in Kotlin.
    AndroidForegroundParser.parseResumedActivityComponents(
      execShellCommand("dumpsys activity activities"),
    )
  } catch (e: Exception) {
    // "Not in foreground" is the right answer for a read that failed — every caller here polls, so
    // throwing would turn a single bad dump into a failed trail. But it must not be a SILENT
    // answer: a wedged `dumpsys` costs the full shell bound and then reads as an app that simply
    // is not up, which is the most confusing possible way for this to fail. The message is what
    // distinguishes the two, so log it.
    Console.log("[foreground] could not read resumed activities, reporting none: $e")
    emptyList()
  }

  /** Single-answer view of [getForegroundComponents]: the first resumed component, or null. */
  private fun getForegroundComponent(): String? = getForegroundComponents().firstOrNull()

  /**
   * Returns the package name of the current foreground app (e.g. "com.example.app"), or null if it
   * cannot be determined.
   *
   * Reads the reliable resumed-activity signal (via [getForegroundComponent]) so it stays correct
   * during cold starts — unlike [androidx.test.uiautomator.UiDevice.currentPackageName], which polls the flaky UiAutomation
   * window list and often reports the launcher (or nothing) while an app is still warming up.
   */
  fun getForegroundPackage(): String? =
    AndroidForegroundParser.packageFromComponent(getForegroundComponent())

  /**
   * Returns the short class name of the current foreground Activity (e.g. "HomeActivity"),
   * or null if it cannot be determined.
   */
  fun getForegroundActivity(): String? =
    AndroidForegroundParser.shortActivityFromComponent(getForegroundComponent())
}
