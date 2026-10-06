package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.device.AndroidDeviceCommandExecutor
import xyz.block.trailblaze.device.androidPackageNameViolation
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.IosHostSimctlUtils

/**
 * Opens an app the way tapping its launcher icon does, and returns once it is on screen.
 *
 * It changes nothing about the app: no data clear, no force-stop, no permission grants. If the app
 * is already running, its existing task comes to the front as-is. Anything that should reset or
 * prepare the app belongs in a trailhead that composes the explicit primitives first
 * (`mobile_clearAppData`, `android_forceStop`, `android_grantPermissions`, `ios_terminate`,
 * `ios_grantPrivacy`) and ends with this tool — so what a trail's launch does is written down, not
 * implied by a mode.
 *
 * "On screen" means different things per platform because the platforms expose different signals:
 * - **Android:** `am start -W` blocks in system_server until the app draws its first frame without
 *   touching the app, and the app must then be in the foreground — both hard requirements. The
 *   app's activity record is then polled for `idle=true` (its main thread first drained after
 *   resume), for up to [AndroidOpenAppReadiness.IDLE_TIMEOUT_MS]. See [AndroidOpenAppReadiness].
 * - **iOS:** `simctl launch` has no drawn signal, so the screen is captured before the launch and
 *   polled after it until it has changed and then held still, for up to a few seconds. See
 *   [awaitIosScreenReady].
 *
 * The settle waits are bounded and do not fail the tool: an app whose main thread never goes idle
 * (a constant animation) or whose screen never holds still (a spinner, a clock) is still open and
 * in front, and failing it would make `openApp` unusable on exactly those apps. The result says
 * when a settle wait ran out, and the next step's own wait for its element takes over.
 */
@Serializable
@TrailblazeToolClass("openApp")
@LLMDescription(
  "Open an app, or bring it to the front if running, and wait for it to appear. Never clears " +
    "data, restarts the app or changes permissions. To start from a known state, use the " +
    "target's trailhead tool instead.",
)
data class OpenAppTrailblazeTool(
  @param:LLMDescription("Android package name or iOS bundle id, e.g. 'com.android.settings'.")
  val appId: String,
) : ExecutableTrailblazeTool {

  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult = try {
    // Every step below blocks (adb / simctl round trips, polling sleeps), so none of it may run on
    // the caller's bounded dispatcher.
    withContext(Dispatchers.IO) {
      when (val platform = toolExecutionContext.trailblazeDeviceInfo.platform) {
        TrailblazeDevicePlatform.ANDROID -> openOnAndroid(toolExecutionContext)
        TrailblazeDevicePlatform.IOS -> openOnIos(toolExecutionContext)
        TrailblazeDevicePlatform.WEB,
        TrailblazeDevicePlatform.DESKTOP,
        -> TrailblazeToolResult.Error.ExceptionThrown(
          errorMessage = "openApp is not supported on $platform devices. On web, use web_navigate.",
          command = this@OpenAppTrailblazeTool,
        )
      }
    }
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    TrailblazeToolResult.Error.ExceptionThrown(
      errorMessage = "Failed to open '$appId': ${e.message}",
      command = this,
      stackTrace = e.stackTraceToString(),
    )
  }

  private fun openOnAndroid(context: TrailblazeToolExecutionContext): TrailblazeToolResult {
    // The id reaches a device shell, and the host transport joins arguments without quoting.
    androidPackageNameViolation(appId)?.let { violation ->
      return TrailblazeToolResult.Error.ExceptionThrown(errorMessage = violation, command = this)
    }
    val executor = context.androidDeviceCommandExecutor
      ?: return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "AndroidDeviceCommandExecutor is not provided",
        command = this,
      )
    val component = resolveLauncherComponent(executor)
      ?: return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "'$appId' has no launcher activity on this device. Check that it is " +
          "installed (mobile_listInstalledApps lists what is).",
        command = this,
      )
    val start = AndroidOpenAppReadiness.parseAmStartWait(
      executor.executeShellCommandArgs(*AndroidOpenAppReadiness.amStartArgs(component).toTypedArray()),
    )
    if (!start.succeeded) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "am start did not open $component: ${start.output.trim()}",
        command = this,
      )
    }
    val idle = AndroidOpenAppReadiness.awaitActivityIdle(appId) {
      executor.executeShellCommand("dumpsys activity activities")
    }
    // `am start` reporting ok is the platform's answer for the start itself, but what the next step
    // acts on is whatever is in front — confirm that is this app before claiming it is on screen.
    if (!executor.waitUntilAppInForeground(appId = appId, maxWaitMs = FOREGROUND_CONFIRM_MS)) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "am start opened $component, but $appId is not in the foreground — " +
          "something else is covering it.",
        command = this,
      )
    }
    return TrailblazeToolResult.Success(message = AndroidOpenAppReadiness.describe(appId, start, idle))
  }

  /**
   * The app's launcher component. `resolve-activity` answers with the system chooser
   * (`android/…ResolverActivity`) when the app has more than one launcher activity of equal
   * priority, so an answer outside [appId]'s package falls back to the first launcher activity
   * `query-activities` lists for it.
   */
  private fun resolveLauncherComponent(executor: AndroidDeviceCommandExecutor): String? =
    AndroidOpenAppReadiness.parseLauncherComponent(
      executor.executeShellCommandArgs(*AndroidOpenAppReadiness.resolveLauncherArgs(appId).toTypedArray()),
      appId,
    ) ?: AndroidOpenAppReadiness.parseLauncherComponent(
      executor.executeShellCommandArgs(*AndroidOpenAppReadiness.queryLauncherArgs(appId).toTypedArray()),
      appId,
    )

  private suspend fun openOnIos(context: TrailblazeToolExecutionContext): TrailblazeToolResult {
    val deviceId = context.trailblazeDeviceInfo.trailblazeDeviceId.instanceId
    if (deviceId !in IosHostSimctlUtils.listBootedDeviceIds()) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "$deviceId is not a booted iOS simulator, so openApp cannot open '$appId' " +
          "there. Only simulators are supported on iOS.",
        command = this,
      )
    }
    val capture = context.screenStateProvider?.let { provider -> { provider().viewHierarchy } }
    val baseline = capture?.invoke()
    IosHostSimctlUtils.launchApp(deviceId = deviceId, appId = appId)
    if (capture == null) {
      return TrailblazeToolResult.Success(
        message = "Opened $appId. This session has no screen capture, so readiness was not checked.",
      )
    }
    val readiness = awaitIosScreenReady(baseline = baseline, capture = capture)
    return TrailblazeToolResult.Success(message = "Opened $appId: ${readiness.description}")
  }

  internal companion object {
    /** `am start` already waited for the first frame, so this only has to see a settled answer. */
    const val FOREGROUND_CONFIRM_MS = 5_000L
  }
}

/**
 * The Android half of [OpenAppTrailblazeTool], as pure functions over command output so the parsing
 * and the idle wait are testable without a device.
 */
internal object AndroidOpenAppReadiness {

  /** How long to wait for `idle=true` after the first frame. Idle normally follows within ~1s. */
  const val IDLE_TIMEOUT_MS = 10_000L
  private const val IDLE_POLL_MS = 250L

  /**
   * `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_RESET_TASK_IF_NEEDED` — the flags a launcher icon tap
   * sends. With them and the resolved launcher component, a running app's task comes to the front
   * unchanged instead of gaining a new activity instance.
   */
  private const val LAUNCHER_FLAGS = "0x10200000"
  private const val ACTION_MAIN = "android.intent.action.MAIN"
  private const val CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER"

  fun resolveLauncherArgs(appId: String): List<String> =
    listOf("cmd", "package", "resolve-activity", "--brief", "-a", ACTION_MAIN, "-c", CATEGORY_LAUNCHER, appId)

  fun queryLauncherArgs(appId: String): List<String> =
    listOf("cmd", "package", "query-activities", "--brief", "-a", ACTION_MAIN, "-c", CATEGORY_LAUNCHER, appId)

  /**
   * The first launcher component (`pkg/.Activity`) in [appId]'s own package from
   * `resolve-activity --brief` or `query-activities --brief` output, or null if there is none —
   * including when the only answer is the system chooser, which belongs to another package.
   */
  fun parseLauncherComponent(output: String, appId: String): String? = output.lineSequence()
    .map { it.trim() }
    .firstOrNull { line -> line.startsWith("$appId/") && line.none { it.isWhitespace() } }

  fun amStartArgs(component: String): List<String> =
    listOf("am", "start", "-W", "-a", ACTION_MAIN, "-c", CATEGORY_LAUNCHER, "-f", LAUNCHER_FLAGS, "-n", component)

  data class AmStartResult(
    val succeeded: Boolean,
    /** `COLD`, `WARM` or `HOT`, when the platform reports it. */
    val launchState: String?,
    /** Time to the first frame, in ms, when the platform reports it. */
    val totalTimeMs: Long?,
    /** True when the app was already running and its task was brought to the front. */
    val broughtToFront: Boolean,
    val output: String,
  )

  fun parseAmStartWait(output: String): AmStartResult {
    val fields = output.lineSequence()
      .mapNotNull { line -> line.split(':', limit = 2).takeIf { it.size == 2 } }
      .associate { (key, value) -> key.trim() to value.trim() }
    return AmStartResult(
      succeeded = fields["Status"] == "ok" && !output.contains("Error:"),
      launchState = fields["LaunchState"]?.substringBefore(' ')?.takeIf { it.isNotEmpty() },
      totalTimeMs = fields["TotalTime"]?.toLongOrNull(),
      broughtToFront = output.contains("brought to the front") ||
        output.contains("delivered to currently running top-most instance"),
      output = output,
    )
  }

  /**
   * Whether the top activity record for [appId] in `dumpsys activity activities` is idle: true or
   * false when a record was found, null when there is none (or the dump is in a shape this cannot
   * read). Records are listed top of stack first, so the first one for the package is the one in
   * front.
   */
  fun activityIdleState(dumpsys: String, appId: String): Boolean? {
    val lines = dumpsys.lines()
    val header = HIST_RECORD.let { regex ->
      lines.indexOfFirst { regex.containsMatchIn(it) && it.contains(" $appId/") }
    }
    if (header < 0) return null
    val body = lines.drop(header + 1).takeWhile { !HIST_RECORD.containsMatchIn(it) && !TASK_RECORD.containsMatchIn(it) }
    return body.any { it.contains("idle=true") }
  }

  private val HIST_RECORD = Regex("""Hist +#\d+: ActivityRecord\{""")
  private val TASK_RECORD = Regex("""^\s*\* Task\{""")

  data class IdleWait(val idle: Boolean, val waitedMs: Long)

  /**
   * Polls [dumpActivities] until [appId]'s activity is idle, capped at [timeoutMs]. A dump this
   * cannot read degrades to the capped wait rather than failing: the first frame is already drawn,
   * so the worst case is a capture that shares the main thread with the tail of startup.
   */
  fun awaitActivityIdle(
    appId: String,
    timeoutMs: Long = IDLE_TIMEOUT_MS,
    nowMs: () -> Long = System::currentTimeMillis,
    sleep: (Long) -> Unit = Thread::sleep,
    dumpActivities: () -> String,
  ): IdleWait {
    val start = nowMs()
    while (true) {
      if (activityIdleState(dumpActivities(), appId) == true) return IdleWait(idle = true, waitedMs = nowMs() - start)
      val elapsed = nowMs() - start
      if (elapsed >= timeoutMs) return IdleWait(idle = false, waitedMs = elapsed)
      sleep(IDLE_POLL_MS)
    }
  }

  fun describe(appId: String, start: AmStartResult, idle: IdleWait): String = buildString {
    append("Opened $appId")
    if (start.broughtToFront) append(" (already running; brought to the front)")
    start.launchState?.let { append(" [$it start]") }
    start.totalTimeMs?.let { append(": first frame ${it}ms") }
    if (idle.idle) {
      append(", idle ${idle.waitedMs}ms later")
    } else {
      append(", not idle after ${idle.waitedMs}ms — the app may still be busy starting up")
    }
    append('.')
  }
}

/** What [awaitIosScreenReady] observed. */
internal data class IosScreenReadiness(val description: String)

/**
 * Waits until the iOS screen shows the opened app: the capture must first differ from [baseline]
 * (the pre-launch screen, which is itself a perfectly settled screen and would otherwise pass
 * immediately), then match the capture before it (settled).
 *
 * If the screen never changes within [unchangedGraceMs], the app was already in front — `simctl
 * launch` of a frontmost app is a no-op — so there is nothing to wait for. Capped at [timeoutMs];
 * past that the tool proceeds and says so, and the next step's own selector wait takes over.
 */
internal fun <T> awaitIosScreenReady(
  baseline: T?,
  unchangedGraceMs: Long = 3_000L,
  // Short on purpose: a screen with live content (a spinner, a clock) never holds still, so this
  // cap is what every open of such an app costs.
  timeoutMs: Long = 5_000L,
  pollMs: Long = 250L,
  nowMs: () -> Long = System::currentTimeMillis,
  sleep: (Long) -> Unit = Thread::sleep,
  capture: () -> T,
): IosScreenReadiness {
  val start = nowMs()
  var previous: T? = null
  var changed = baseline == null
  while (true) {
    val current = capture()
    val elapsed = nowMs() - start
    if (current != baseline) changed = true
    if (changed && previous != null && current == previous) {
      return IosScreenReadiness("on screen and settled after ${elapsed}ms.")
    }
    if (!changed && elapsed >= unchangedGraceMs) {
      return IosScreenReadiness("the screen did not change, so it was already in front.")
    }
    if (elapsed >= timeoutMs) {
      return IosScreenReadiness("the screen was still changing after ${elapsed}ms; continuing anyway.")
    }
    previous = current
    sleep(pollMs)
  }
}
