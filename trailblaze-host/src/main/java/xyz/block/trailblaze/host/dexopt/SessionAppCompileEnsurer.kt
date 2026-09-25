package xyz.block.trailblaze.host.dexopt

import kotlinx.coroutines.runBlocking
import xyz.block.trailblaze.device.EnsureAppCompiled
import xyz.block.trailblaze.device.EnsureAppCompiled.Outcome
import xyz.block.trailblaze.device.androidPackageNameViolation
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.util.AndroidHostAdbUtils
import xyz.block.trailblaze.util.Console
import java.util.concurrent.ConcurrentHashMap

/**
 * Makes sure the Android app a host-driven session is about to drive has compiled ART artifacts,
 * so its cold starts do not re-verify the whole APK every time — see [EnsureAppCompiled] for the
 * state this repairs and the numbers behind it.
 *
 * Runs at session start, next to the turbo attacher, because that is the first point that knows
 * BOTH the device and which app the session will drive. A healthy install costs one `dumpsys`
 * read per session (~20–100 ms) and no write; a broken one is compiled once, and the artifacts then
 * outlive the session, the daemon and every later run on that device until the app is reinstalled.
 * That is what makes a compile that pays for itself after about four cold starts worth doing at
 * session start rather than inside a trail: host-driven runs put many sessions on one install.
 *
 * Only host-driven sessions. A run whose tools execute on the device (the instrumentation runner)
 * never comes through here; it can list `android_ensureAppCompiled` as a step instead.
 *
 * **Never throws, including on a device that has gone away.** Every adb call in here can fail, and
 * a caller doing session setup must not have its session start — or its device reservation — broken
 * by a repair it does not wait on. A device that cannot be repaired runs exactly as it does today,
 * and the log line says so and names the one-line manual fix.
 */
object SessionAppCompileEnsurer {

  const val LOG_TAG = "[dexopt]"

  /**
   * `sessionId:deviceId` pairs already checked, so a second `startForSession` for the same
   * session AND device is free. Keyed by the pair, not the session id alone: a multi-device
   * session reuses one session id across every device it drives, and keying by session id alone
   * would let the first device's check claim the session and silently skip every other device.
   */
  private val sessions = ConcurrentHashMap.newKeySet<String>()

  private fun sessionDeviceKey(sessionId: String, deviceId: TrailblazeDeviceId) = "$sessionId:${deviceId.instanceId}"

  /**
   * How long a device stays skipped after its check timed out or threw, before a later session is
   * willing to pay the wait again. There is no signal here for "the device actually recovered" —
   * a reboot, reconnect or reinstall under the same serial looks identical to this object — so a
   * bounded cooldown stands in for one: short enough that a device fixed mid-daemon-lifetime is
   * retried without restarting the daemon, long enough that a device still wedged is not re-paying
   * the up-to-300s timeout on every session in between.
   */
  internal const val UNREACHABLE_COOLDOWN_MS = 5 * 60_000L

  /**
   * Devices whose check already timed out or threw once, keyed to the time it happened. A wedged
   * device does not un-wedge for the next session that asks, so without this a broken device pays
   * the full read-and-compile timeout again on every subsequent session start, rather than once —
   * the difference between a one-time cost and a standing tax on a device nobody has fixed yet.
   * Expires after [UNREACHABLE_COOLDOWN_MS]; also cleared by [clearForTests] or a process restart.
   */
  private val unreachableDevices = ConcurrentHashMap<String, Long>()

  /**
   * Checks — and if needed compiles — the first of [candidateAppIds] that is installed on
   * [deviceId]. The candidates arrive in the target's declared priority order, so a target listing
   * a debug and a release application id repairs the one the device actually carries. A
   * multi-device session calls this once per device, all under the same [sessionId] — the launch
   * device and every companion each get their own call and their own dedup entry.
   *
   * No-ops on non-Android platforms, when [EnsureAppCompiledGate] is off, when this session+device
   * pair was already checked, and when [unresolvedDeclaredTarget] is non-null: that is the trail's
   * `config.target` when it named no loaded target, meaning the run fell back to the workspace
   * default and [candidateAppIds] are not the app the trail was written for. Compiling that app
   * would spend up to half a minute on something the trail never launches.
   *
   * Two call sites fire for one session (session resolution and the YAML runner). The session is
   * claimed only once real candidates are about to be checked, not on entry — so a call that
   * declines (an unresolved declared target, no candidates) leaves the session+device pair
   * unclaimed for the other call site to try instead, rather than silently costing it its only
   * check.
   *
   * [ensure] is the seam a test replaces; the default runs the shared sequence over adb.
   */
  fun startForSession(
    sessionId: String,
    deviceId: TrailblazeDeviceId,
    candidateAppIds: List<String>,
    unresolvedDeclaredTarget: String? = null,
    nowMs: () -> Long = { System.currentTimeMillis() },
    log: (String) -> Unit = { Console.log(it) },
    ensure: (appId: String) -> Outcome = { ensureOverAdb(deviceId, it) },
  ) {
    try {
      ensureForSession(
        sessionId = sessionId,
        deviceId = deviceId,
        candidateAppIds = candidateAppIds,
        unresolvedDeclaredTarget = unresolvedDeclaredTarget,
        gateEnabled = EnsureAppCompiledGate.enabled(),
        nowMs = nowMs,
        log = log,
        ensure = ensure,
      )
    } catch (t: Throwable) {
      // Fail open, and mean it: a device that dropped off between reservation and session start
      // makes the opening `dumpsys` throw, and a repair must not be able to fail a run, let alone
      // strand the reservation so the NEXT run on the device is rejected as busy.
      unreachableDevices[deviceId.instanceId] = nowMs()
      log("$LOG_TAG $sessionId: dexopt check skipped (${t::class.java.simpleName}: ${t.message})")
    }
  }

  internal fun ensureForSession(
    sessionId: String,
    deviceId: TrailblazeDeviceId,
    candidateAppIds: List<String>,
    unresolvedDeclaredTarget: String?,
    gateEnabled: Boolean,
    /**
     * Read twice on purpose, and never cached across the device call: the cooldown gate asks how
     * long ago the last failure was, and a fresh failure asks what time it is NOW. Those are up to
     * five minutes apart, because the failure being recorded is a timeout — stamping it with the
     * entry time would start the cooldown already most of the way expired, and the next session
     * would re-pay the full wait the cooldown exists to prevent.
     */
    nowMs: () -> Long = { System.currentTimeMillis() },
    log: (String) -> Unit,
    ensure: (appId: String) -> Outcome,
  ) {
    if (deviceId.trailblazeDevicePlatform != TrailblazeDevicePlatform.ANDROID) return
    if (!gateEnabled) return

    if (unresolvedDeclaredTarget != null) {
      // Deliberately does not claim the session: the OTHER call site for this session id may
      // still arrive with a resolved target and real candidates, and it must get its own chance.
      log(
        "$LOG_TAG $sessionId: not checking compiled artifacts — this trail declares target " +
          "'$unresolvedDeclaredTarget', which this installation does not carry, so " +
          "${candidateAppIds.joinToString(", ").ifEmpty { "the fallback app" }} is not the app it drives.",
      )
      return
    }

    // Validated before anything reaches a device shell: candidateAppIds comes from a target
    // manifest, which is untrusted input by the time it gets here, and every downstream shell
    // call joins its argv into a single command string — an app id like `com.safe; id` would
    // otherwise run as a second command.
    val candidates = candidateAppIds
      .filter { it.isNotBlank() }
      .filter { candidate ->
        val violation = androidPackageNameViolation(candidate)
        if (violation != null) log("$LOG_TAG $sessionId: refusing to check '$candidate' — $violation")
        violation == null
      }
    if (candidates.isEmpty()) return

    val unreachableSince = unreachableDevices[deviceId.instanceId]
    if (unreachableSince != null) {
      val sinceMs = nowMs() - unreachableSince
      if (sinceMs < UNREACHABLE_COOLDOWN_MS) {
        log(
          "$LOG_TAG $sessionId: skipping — a previous session's dexopt check on " +
            "${deviceId.instanceId} did not come back, so this session is not paying the same " +
            "wait again. Retrying in ${(UNREACHABLE_COOLDOWN_MS - sinceMs) / 1000}s, " +
            "or restart the daemon to retry now.",
        )
        return
      }
      // The cooldown expired: worth paying the wait once more, since nothing here can tell a
      // device that is still wedged from one that was rebooted, reconnected or reinstalled under
      // the same serial while the daemon kept running.
      unreachableDevices.remove(deviceId.instanceId)
    }

    // Claimed only once real work is about to happen: the two call sites for one session both
    // reach here with the same resolved candidates, and whichever arrives first should be the one
    // that runs, not whichever happened to be unresolved or empty.
    if (!sessions.add(sessionDeviceKey(sessionId, deviceId))) return

    for (appId in candidates) {
      val outcome = ensure(appId)
      if (outcome is Outcome.NotInstalled) continue
      if (outcome is Outcome.DumpTimedOut || outcome is Outcome.CompileTimedOut) {
        // After `ensure`, not before it: see the `nowMs` doc.
        unreachableDevices[deviceId.instanceId] = nowMs()
      }
      log(describe(sessionId, deviceId, appId, outcome))
      return
    }
    log(
      "$LOG_TAG $sessionId: none of ${candidates.joinToString(", ")} is installed on " +
        "${deviceId.instanceId}, so there is nothing to compile",
    )
  }

  /** One line per outcome, written for whoever reads the session log after the fact. */
  internal fun describe(sessionId: String, deviceId: TrailblazeDeviceId, appId: String, outcome: Outcome): String {
    val device = deviceId.instanceId
    fun manualFix(filter: String) = "adb -s $device shell pm compile -m $filter -f $appId"
    return when (outcome) {
      is Outcome.AlreadyCompiled ->
        "$LOG_TAG $sessionId: $appId already has compiled artifacts on $device (${outcome.state.summary()})"

      is Outcome.Reported ->
        "$LOG_TAG $sessionId: $appId dexopt state on $device: ${outcome.state.summary()}"

      is Outcome.StatusUnreadable ->
        "$LOG_TAG $sessionId: $device reports no dexopt status for $appId in a shape this build " +
          "reads (Android 8.x prints an older one), so whether it has compiled artifacts is " +
          "unknown. Nothing compiled — a compile here would run every session and could never be " +
          "confirmed."

      is Outcome.DumpTimedOut ->
        "$LOG_TAG $sessionId: `dumpsys package $appId` on $device did not come back within " +
          "${outcome.timeoutMs / 1000}s — whether $appId is compiled is unknown, not checked. Not " +
          "retrying $device for ${UNREACHABLE_COOLDOWN_MS / 1000}s unless the daemon restarts."

      is Outcome.Compiled ->
        "$LOG_TAG $sessionId: $appId had no compiled artifacts on $device (${outcome.before.summary()}), so " +
          "every cold start was re-verifying the APK. Compiled with '${outcome.compilerFilter}' in " +
          "${outcome.elapsedMs}ms; now ${outcome.after.summary()}."

      is Outcome.CompileTimedOut ->
        "$LOG_TAG $sessionId: $appId has no compiled artifacts on $device (${outcome.before.summary()}) and " +
          "`pm compile` did not finish within ${outcome.timeoutMs / 1000}s. Cold starts stay slow until it " +
          "is compiled: ${manualFix(outcome.compilerFilter)}"

      is Outcome.StillUncompiled ->
        "$LOG_TAG $sessionId: $appId has no compiled artifacts on $device (${outcome.before.summary()}); " +
          "`pm compile -m ${outcome.compilerFilter} -f $appId` ran (${outcome.elapsedMs}ms) but the device " +
          "still reports ${outcome.after?.summary() ?: "an unreadable state"}. pm compile said: " +
          "${outcome.compileOutput.trim().take(200).ifEmpty { "(nothing)" }}. Cold starts stay slow until " +
          "it is compiled: ${manualFix(outcome.compilerFilter)}"

      // Filtered out before this is reached; stated so a new outcome is a compile error here.
      is Outcome.NotInstalled ->
        "$LOG_TAG $sessionId: $appId is not installed on $device"
    }
  }

  /**
   * The shared sequence over adb. Not a coroutine caller — the session-start hooks are plain
   * functions, like turbo's — so the suspend core runs to completion right here; both of its
   * commands are already bounded by their own timeouts.
   */
  private fun ensureOverAdb(deviceId: TrailblazeDeviceId, appId: String): Outcome = runBlocking {
    EnsureAppCompiled.ensure(appId = appId) { args, timeoutMs ->
      if (args.take(2) == listOf("pm", "compile")) {
        // `pm compile` is already bounded at minutes and this cooldown accounting relies on that
        // bound: the retrying transport would let a second attempt start alongside a first one
        // still running past its own timeout, silently doubling the wait. Matched on the whole
        // subcommand rather than `pm` alone, so a short `pm` read added later keeps the
        // reconnect-and-retry below, which is the right transport for it. `dumpsys` is such a
        // read today.
        AndroidHostAdbUtils.execAdbShellCommandBoundedOnce(deviceId, args, timeoutMs)
      } else {
        AndroidHostAdbUtils.execAdbShellCommandWithTimeout(deviceId, args, timeoutMs)
      }
    }
  }

  /** Test-only reset so a suite that mutates the singleton can restore it in `@After`. */
  fun clearForTests() {
    sessions.clear()
    unreachableDevices.clear()
  }
}
