package xyz.block.trailblaze.host.animations

import java.util.concurrent.ConcurrentHashMap
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.host.ios.SimctlCli
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.util.AndroidHostAdbUtils
import xyz.block.trailblaze.util.Console

/**
 * **EXPERIMENTAL, opt-in** ([DisableAnimationsGate]). Disables OS-level animations on the device
 * for the duration of a session and restores the previous values when the session ends, so trails
 * stop paying the animation floor on every settle without permanently changing the device.
 *
 * - **Android** (emulators and physical devices): zeroes the three global animation scales
 *   (`window_animation_scale`, `transition_animation_scale`, `animator_duration_scale`) via adb —
 *   the same setup Google's Espresso guidance prescribes and the CI boot scripts already apply to
 *   farm emulators. This makes local/laptop runs match CI.
 * - **iOS simulators**: writes a near-zero `UIAnimationDragCoefficient` (UIKit's global animation
 *   duration multiplier) into the simulator's global preferences domain via `simctl spawn`.
 *   Preferences are read at app-process launch, so this takes effect for apps launched after
 *   session start (the normal trailhead flow); an app already running keeps its animations for
 *   the rest of its process lifetime. On a physical iOS device `simctl spawn` fails and the
 *   session proceeds untouched. Deliberately NOT the Reduce Motion accessibility setting — apps
 *   legitimately branch on that (crossfades instead of pushes), which would mean testing a
 *   different app.
 *
 * ### Lifecycle
 * One entry per session; [startForSession] is idempotent (both the MCP session-resolution path
 * and the CLI runner's capture callback may fire it). Previous values are captured before the
 * mutation and restored in [restoreForSession] — invoked from the shared session-finalization
 * barrier (`finalizeHostSessionResources`), which covers normal end, cancel, and the CLI-owned
 * end — AND via a JVM shutdown hook, so a crashed run never strands a device with animations off.
 * A setting whose current value can't be read is left completely untouched rather than risking a
 * wrong restore.
 *
 * Diagnostic log lines are prefixed `[disable-animations]`.
 */
object SessionAnimationDisabler {

  private const val LOG_TAG = "[disable-animations]"

  internal val ANDROID_ANIMATION_SETTINGS = listOf(
    "window_animation_scale",
    "transition_animation_scale",
    "animator_duration_scale",
  )

  private const val IOS_DRAG_COEFFICIENT_KEY = "UIAnimationDragCoefficient"

  /**
   * Near-zero rather than exactly zero: UIKit multiplies animation durations by the coefficient,
   * and a small positive value keeps every animation a real (instant-looking) animation with its
   * completion callbacks firing through the normal path, instead of relying on undefined
   * zero-duration semantics.
   */
  private const val IOS_DRAG_COEFFICIENT = "0.05"

  private class SessionState(
    val deviceId: TrailblazeDeviceId,
    /** Setting name → previous raw value (`"null"` = unset). Absent = untouched, don't restore. */
    val previousAndroidValues: Map<String, String>,
    val iosApplied: Boolean,
    /** Previous coefficient when [iosApplied]; `null` means the key was unset (restore = delete). */
    val previousIosCoefficient: String?,
  )

  private val sessions = ConcurrentHashMap<SessionId, SessionState>()

  @Volatile private var shutdownHookInstalled = false

  /**
   * Disables animations for the session's device, capturing the previous values for restore.
   * No-ops when the gate is off, on unsupported platforms (web), and when the session is already
   * set up (idempotent).
   */
  fun startForSession(sessionId: SessionId, deviceId: TrailblazeDeviceId) {
    if (!DisableAnimationsGate.enabled()) return
    when (deviceId.trailblazeDevicePlatform) {
      TrailblazeDevicePlatform.ANDROID, TrailblazeDevicePlatform.IOS -> Unit
      else -> return
    }
    installShutdownHookOnce()
    sessions.computeIfAbsent(sessionId) {
      when (deviceId.trailblazeDevicePlatform) {
        TrailblazeDevicePlatform.ANDROID -> {
          val previous = disableAndroidAnimations(deviceId)
          Console.log(
            "$LOG_TAG $sessionId: zeroed Android animation scales on ${deviceId.instanceId} " +
              "(previous: $previous)"
          )
          SessionState(deviceId, previous, iosApplied = false, previousIosCoefficient = null)
        }
        else -> {
          val (applied, previous) = applyIosDragCoefficient(deviceId.instanceId)
          if (applied) {
            Console.log(
              "$LOG_TAG $sessionId: set $IOS_DRAG_COEFFICIENT_KEY=$IOS_DRAG_COEFFICIENT on " +
                "simulator ${deviceId.instanceId} (previous: ${previous ?: "unset"}); applies to " +
                "apps launched from here on"
            )
          }
          SessionState(deviceId, emptyMap(), iosApplied = applied, previousIosCoefficient = previous)
        }
      }
    }
  }

  /**
   * Restores the device's previous animation settings. Safe to call for unknown sessions.
   * A setting whose restore fails (adb timeout, simctl failure) is re-registered under the
   * session so a later finalize call or the JVM shutdown hook can retry — the captured previous
   * values are the only copy, so they must not be dropped before the device is actually back.
   */
  fun restoreForSession(sessionId: SessionId) {
    val state = sessions.remove(sessionId) ?: return
    val remainder = restore(sessionId, state)
    if (remainder != null) {
      sessions.putIfAbsent(sessionId, remainder)
      Console.log(
        "$LOG_TAG $sessionId: could NOT restore all animation settings on " +
          "${state.deviceId.instanceId}; keeping the previous values for retry at the next " +
          "session end or JVM shutdown"
      )
    }
  }

  /** Restores and returns the not-yet-restored remainder, or `null` when everything is back. */
  private fun restore(sessionId: SessionId, state: SessionState): SessionState? {
    val failedAndroid = mutableMapOf<String, String>()
    state.previousAndroidValues.forEach { (setting, previous) ->
      val result = runCatching {
        AndroidHostAdbUtils.execAdbShellCommandWithTimeout(
          state.deviceId,
          androidRestoreCommand(setting, previous).split(" "),
        )
      }.getOrNull()
      if (result == null) failedAndroid[setting] = previous
    }
    var iosStillApplied = false
    if (state.iosApplied) {
      val previous = state.previousIosCoefficient
      val result = if (previous == null) {
        spawnSafely(state.deviceId.instanceId, listOf("defaults", "delete", "-g", IOS_DRAG_COEFFICIENT_KEY))
      } else {
        spawnSafely(
          state.deviceId.instanceId,
          listOf("defaults", "write", "-g", IOS_DRAG_COEFFICIENT_KEY, "-float", previous),
        )
      }
      iosStillApplied = !result.success
    }
    if (failedAndroid.isEmpty() && !iosStillApplied) {
      if (state.previousAndroidValues.isNotEmpty() || state.iosApplied) {
        Console.log("$LOG_TAG $sessionId: restored animation settings on ${state.deviceId.instanceId}")
      }
      return null
    }
    return SessionState(state.deviceId, failedAndroid, iosStillApplied, state.previousIosCoefficient)
  }

  /**
   * The restore command for one Android animation-scale setting, pure for tests: a captured
   * `"null"` (the raw `settings get` output for a never-set setting) restores via `delete` so the
   * device returns to its OS default rather than a literal string `null`.
   */
  internal fun androidRestoreCommand(setting: String, previousValue: String): String =
    if (previousValue == "null" || previousValue.isBlank()) {
      "settings delete global $setting"
    } else {
      "settings put global $setting $previousValue"
    }

  /**
   * Zeroes each animation scale, returning the previous values for the ones actually touched.
   * A setting whose read fails (adb timeout) is skipped entirely — no blind mutation we couldn't
   * undo correctly.
   */
  private fun disableAndroidAnimations(deviceId: TrailblazeDeviceId): Map<String, String> {
    val previous = mutableMapOf<String, String>()
    for (setting in ANDROID_ANIMATION_SETTINGS) {
      val raw = AndroidHostAdbUtils.execAdbShellCommandWithTimeout(
        deviceId,
        listOf("settings", "get", "global", setting),
      )
      if (raw == null) {
        Console.log("$LOG_TAG could not read $setting on ${deviceId.instanceId}; leaving it untouched")
        continue
      }
      AndroidHostAdbUtils.execAdbShellCommandWithTimeout(
        deviceId,
        listOf("settings", "put", "global", setting, "0"),
      )
      previous[setting] = raw.trim()
    }
    return previous
  }

  /**
   * Writes the near-zero drag coefficient into the simulator's global defaults, returning whether
   * it was applied and the previous value (`null` = key was unset). Declines cleanly — leaving the
   * device untouched — when the write fails (not a booted simulator, simctl missing) AND when the
   * current value can't be classified (a transient read failure is NOT the same as "key unset":
   * writing over an unknown previous value would make the later restore delete a coefficient the
   * user had deliberately set).
   */
  private fun applyIosDragCoefficient(udid: String): Pair<Boolean, String?> {
    val read = spawnSafely(udid, listOf("defaults", "read", "-g", IOS_DRAG_COEFFICIENT_KEY))
    val previous = when (val outcome = classifyIosCoefficientRead(read)) {
      is IosReadOutcome.Value -> outcome.value
      IosReadOutcome.Unset -> null
      IosReadOutcome.Unknown -> {
        Console.log(
          "$LOG_TAG could not read the current $IOS_DRAG_COEFFICIENT_KEY on $udid " +
            "(${read.stderr.trim().ifEmpty { "exit ${read.exitCode}" }}); animations left untouched"
        )
        return false to null
      }
    }
    val write = spawnSafely(
      udid,
      listOf("defaults", "write", "-g", IOS_DRAG_COEFFICIENT_KEY, "-float", IOS_DRAG_COEFFICIENT),
    )
    if (!write.success) {
      Console.log(
        "$LOG_TAG could not set $IOS_DRAG_COEFFICIENT_KEY on $udid " +
          "(${write.stderr.trim().ifEmpty { "exit ${write.exitCode}" }}); animations left untouched"
      )
      return false to null
    }
    return true to previous
  }

  /** What a `defaults read -g UIAnimationDragCoefficient` result tells us about the previous value. */
  internal sealed interface IosReadOutcome {
    data class Value(val value: String) : IosReadOutcome

    /** The key is genuinely absent — restore should `delete` it. */
    data object Unset : IosReadOutcome

    /** Read failed for some other reason (timeout, no simctl) — decline, don't guess. */
    data object Unknown : IosReadOutcome
  }

  /**
   * Pure classification of the pre-mutation read, for tests: only the specific "does not exist"
   * failure (verified `defaults` wording for an absent key) means "unset"; any other failure is
   * [IosReadOutcome.Unknown] because treating it as unset would restore-by-delete a value we
   * never actually observed.
   */
  internal fun classifyIosCoefficientRead(read: SimctlCli.Result): IosReadOutcome = when {
    read.success -> IosReadOutcome.Value(read.stdout.trim())
    read.stderr.contains("does not exist") -> IosReadOutcome.Unset
    else -> IosReadOutcome.Unknown
  }

  /** [SimctlCli.spawn] that never throws (e.g. missing `xcrun`) — a thrown start is a failed [SimctlCli.Result]. */
  private fun spawnSafely(udid: String, command: List<String>): SimctlCli.Result =
    runCatching { SimctlCli.spawn(udid, command) }
      .getOrElse { SimctlCli.Result(-1, "", it.message ?: it.javaClass.simpleName) }

  private fun installShutdownHookOnce() {
    if (shutdownHookInstalled) return
    synchronized(this) {
      if (shutdownHookInstalled) return
      Runtime.getRuntime().addShutdownHook(
        Thread {
          // Snapshot + clear so devices are restored even on an abrupt daemon shutdown.
          val remaining = sessions.entries.toList()
          sessions.clear()
          remaining.forEach { (sessionId, state) -> runCatching { restore(sessionId, state) } }
        },
      )
      shutdownHookInstalled = true
    }
  }

  /** Test-only reset (forgets tracked sessions without touching any device). */
  fun clearForTests() {
    sessions.clear()
  }
}
