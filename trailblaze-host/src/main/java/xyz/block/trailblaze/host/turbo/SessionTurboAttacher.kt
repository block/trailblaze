package xyz.block.trailblaze.host.turbo

import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.inprocessidle.InProcessIdle
import xyz.block.trailblaze.inprocessidle.TurboMessages
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.InProcessIdleApkInstaller
import java.util.concurrent.ConcurrentHashMap

/**
 * **EXPERIMENTAL, opt-in** ([TurboGate]). Puts the in-process idle helper into the Android app a
 * session is about to drive, so the accessibility driver can stop waiting as soon as the app says
 * it is idle instead of watching for its screen to go quiet.
 *
 * Runs at session start, next to [xyz.block.trailblaze.host.animations.SessionAnimationDisabler],
 * because that is the first point that knows BOTH the device and which app the session will drive
 * — a device connect does not, since the on-device instrumentation target names only the test APK.
 *
 * Nothing is restored at session end, unlike its animation-disabling neighbor. Detaching the
 * helper means killing the app it lives inside, which is a worse thing to do to a developer's
 * device than leaving an accelerator switched on; the switch is device state that dies on reboot
 * anyway. `trailblaze config turbo false` stops future sessions from using it.
 */
object SessionTurboAttacher {

  /**
   * Shared with the on-device attacher ([TurboMessages]), so a host run and a device-farm run are
   * greppable with the same string.
   */
  private const val LOG_TAG = TurboMessages.LOG_TAG

  /** Sessions already set up, so a second `startForSession` for the same session is free. */
  private val sessions = ConcurrentHashMap.newKeySet<String>()

  /**
   * Sessions an explicit per-run `--turbo` / `--no-turbo` has already been applied for.
   *
   * Two call sites fire for one session — session resolution (which serves MCP, the Trail Runner
   * and the recording screen, and has no CLI flag to pass) and the YAML runner (which does). Plain
   * "first call wins" deduping would let whichever ran first decide, silently dropping the run
   * flag when resolution happened to go first. An explicit override therefore gets one chance to
   * apply even on a session that was already set up, which is what makes `--no-turbo` a reliable
   * way to get a comparison run on a host with turbo switched on globally.
   */
  private val explicitOverridesApplied = ConcurrentHashMap.newKeySet<String>()

  /**
   * Sessions a decline has already been applied for, for the same reason [explicitOverridesApplied]
   * exists.
   *
   * Session resolution runs first for the Trail Runner, MCP and recording-replay paths, and it
   * knows only the workspace-default target — so it is the call site that would attach to the
   * fallback app. The runner's later call is the only one that knows the trail's declared target
   * did not resolve. Under plain "first call wins" that decline would be dropped whenever no
   * explicit `--turbo` / `--no-turbo` was passed, leaving `TRAILBLAZE_TURBO` and `config turbo
   * true` runs attached to an app the trail never drives — exactly what declining exists to stop.
   * A decline therefore gets its own one chance to apply on an already-set-up session, and
   * [clearStaleRace] turns off the switch the earlier attach left on.
   */
  private val declinesApplied = ConcurrentHashMap.newKeySet<String>()

  /**
   * Attaches the helper for the session's device, choosing among [candidateAppIds] the first app
   * this build of Trailblaze carries a signature-matched helper for AND that is installed on the
   * device.
   *
   * No-ops when the gate is off, on non-Android platforms, when no candidate app can be attached
   * to, and when the session is already set up (except for an explicit [runOverride] — see
   * [shouldRun]).
   *
   * [unresolvedDeclaredTarget] non-null is the trail's `config.target` when it named no loaded
   * target, which means the run fell back to the workspace default and [candidateAppIds] are NOT
   * the app the trail was written for. Turbo declines rather than attaching to the fallback's app:
   * a `turbo on` line for an app the trail never drives reads as a successful turbo run while doing
   * nothing for the app under test. The off-switch handling still runs first, so `--no-turbo` /
   * `config turbo false` clear the device exactly as they would otherwise, and a decline overrules
   * an attach an earlier call site already made — see [declinesApplied].
   *
   * **Never throws, including on a device that has gone away** — every adb call in here can fail,
   * and a caller doing session setup must not have its session start (or its device reservation)
   * broken by an accelerator it does not wait on. Turbo is one: every settle gate races the helper
   * against the standard heuristic, so a session that fails to attach runs exactly as it does
   * today. A session that asked for turbo and could not get it leaves the device switched OFF
   * rather than trusting a switch an earlier session set — see [clearStaleRace].
   */
  fun startForSession(
    sessionId: String,
    deviceId: TrailblazeDeviceId,
    candidateAppIds: List<String>,
    runOverride: Boolean? = null,
    unresolvedDeclaredTarget: String? = null,
    log: (String) -> Unit = { Console.log(it) },
  ) {
    try {
      attachForSession(sessionId, deviceId, candidateAppIds, runOverride, unresolvedDeclaredTarget, log)
    } catch (t: Throwable) {
      // Fail open, and mean it. Every adb call below can throw — a device that dropped off between
      // reservation and session start makes even the opening `getprop` throw — and turbo is an
      // accelerator that no caller waits on a result from. Letting one escape turned an absent
      // device into a failed session start that never released the device reservation, so the
      // NEXT run on that device was rejected as busy. An accelerator must not be able to fail a
      // run, let alone the run after it.
      //
      // Deliberately does NOT claim the run is at normal speed: if this threw while reading or
      // clearing the switch, whether turbo is on is exactly what we failed to establish. The
      // paths that DO know say so themselves.
      log("$LOG_TAG $sessionId: turbo setup skipped (${t::class.java.simpleName}: ${t.message})")
    }
  }

  private fun attachForSession(
    sessionId: String,
    deviceId: TrailblazeDeviceId,
    candidateAppIds: List<String>,
    runOverride: Boolean?,
    unresolvedDeclaredTarget: String?,
    log: (String) -> Unit,
  ) {
    if (deviceId.trailblazeDevicePlatform != TrailblazeDevicePlatform.ANDROID) return
    if (!shouldRun(sessionId, runOverride, declining = unresolvedDeclaredTarget != null)) return

    val action = decide(
      gateEnabled = TurboGate.enabled(runOverride),
      raceCurrentlyOn = { InProcessIdleApkInstaller.isSettleRaceEnabled(deviceId) },
    )
    if (action == Action.NOTHING) return
    if (action == Action.CLEAR) {
      log(TurboMessages.clearing(sessionId, deviceId.instanceId))
      clearTurbo(deviceId, sessionId, log)
      return
    }

    if (unresolvedDeclaredTarget != null) {
      // Built here rather than handed down pre-formatted, so this file owns every `[turbo]` line
      // it emits — and so a test of the decline asserts on the real message instead of on a string
      // the caller supplied. Names the apps it is NOT attaching to, so the line cannot be mistaken
      // for a missing-helper skip: those apps may well have helpers; they are simply not the
      // trail's app.
      log(
        "$LOG_TAG $sessionId: not attaching turbo — this trail declares target " +
          "'$unresolvedDeclaredTarget', which this installation does not carry. Would have " +
          "attached to ${candidateAppIds.joinToString(", ").ifEmpty { "no app" }}, which this " +
          "trail does not drive.",
      )
      clearStaleRace(deviceId, sessionId, log)
      return
    }

    val appId = chooseAppId(candidateAppIds) {
      InProcessIdleApkInstaller.hasBundledApkFor(it) && InProcessIdleApkInstaller.isAppInstalled(deviceId, it)
    }
    if (appId == null) {
      // Deliberately says which apps were considered AND which helpers exist: "turbo did nothing"
      // with neither list is the least useful thing to tell someone who just switched it on.
      log(
        TurboMessages.normalSpeed(
          sessionId,
          "no signature-matched helper installed for " +
            candidateAppIds.joinToString(", ").ifEmpty { "this session's app" },
        ) + ". This build carries helpers for: " +
          InProcessIdleApkInstaller.bundledLabels().joinToString(", ").ifEmpty { "none" },
      )
      clearStaleRace(deviceId, sessionId, log)
      return
    }

    when (val outcome = InProcessIdleApkInstaller.ensureAttached(deviceId, appId, log = log)) {
      is InProcessIdleApkInstaller.Outcome.Attached ->
        log(TurboMessages.turboOn(sessionId, appId, deviceId.instanceId))

      is InProcessIdleApkInstaller.Outcome.PortHeldByOtherApp -> {
        log(
          "$LOG_TAG $sessionId: ${outcome.otherAppId} already has the helper on " +
            "${deviceId.instanceId}, so $appId runs at normal speed. Only one app per device can " +
            "be turbo — stop ${outcome.otherAppId} first to switch.",
        )
        clearStaleRace(deviceId, sessionId, log)
      }

      is InProcessIdleApkInstaller.Outcome.NotBundled -> {
        log(TurboMessages.normalSpeed(sessionId, "no helper for ${outcome.appId}"))
        clearStaleRace(deviceId, sessionId, log)
      }

      is InProcessIdleApkInstaller.Outcome.Failed -> {
        log(TurboMessages.normalSpeed(sessionId, "could not turn turbo on for $appId (${outcome.reason})"))
        clearStaleRace(deviceId, sessionId, log)
      }
    }
  }

  /**
   * Whether this call should act, given [sessions] may already contain [sessionId].
   *
   * A repeat call is free, EXCEPT two kinds that must be allowed to overrule a decision a
   * different call site made from env + config alone: the first carrying an explicit per-run
   * override (see [explicitOverridesApplied]), and the first [declining] one (see
   * [declinesApplied]). Each gets exactly one chance, so a third call still costs nothing.
   */
  internal fun shouldRun(sessionId: String, runOverride: Boolean?, declining: Boolean = false): Boolean {
    val firstForSession = sessions.add(sessionId)
    val firstExplicitOverride = runOverride != null && explicitOverridesApplied.add(sessionId)
    val firstDecline = declining && declinesApplied.add(sessionId)
    return firstForSession || firstExplicitOverride || firstDecline
  }

  /**
   * Turns the switch off when turbo was asked for but this session could not attach.
   *
   * The switch and the helper both outlive the session that set them, and the driver's idle
   * request is not app-scoped — it goes to whichever process still holds the detector port. So a
   * device left switched on after a failed or declined attach does not merely miss the speedup: it
   * can end a wait early on a *previous* app's idea of idle. Only touches a device that is
   * actually switched on, so the ordinary no-helper case still costs one `getprop` and no write.
   */
  private fun clearStaleRace(deviceId: TrailblazeDeviceId, sessionId: String, log: (String) -> Unit) {
    val currentlyOn = try {
      InProcessIdleApkInstaller.isSettleRaceEnabled(deviceId)
    } catch (t: Throwable) {
      // We could not even read the switch, so we cannot report the run as unturboed. Warn instead
      // of falling through to the blanket handler, which knows less about what was being attempted.
      warnMayStillBeOn(deviceId, sessionId, "could not read the switch (${t.message})", log)
      return
    }
    if (!currentlyOn) return
    log(
      "$LOG_TAG $sessionId: clearing turbo left on ${deviceId.instanceId} by an earlier session — " +
        "this one cannot attach, and the idle request is not app-scoped",
    )
    clearTurbo(deviceId, sessionId, log)
  }

  /**
   * Turns the switch off and says plainly whether that worked.
   *
   * Never throws, for the same reason [startForSession] does not: an adb failure in an accelerator
   * must not fail the run or strand the device reservation. But it also must not be reported as a
   * clean normal-speed run — a switch left on can end a wait early on whatever process still holds
   * the detector port. So the failure is loud and names the one-line manual fix, and the run
   * continues. Failing the run instead would trade a rare, self-announcing hazard for the
   * reservation-stranding bug that made this method non-throwing in the first place.
   */
  private fun clearTurbo(deviceId: TrailblazeDeviceId, sessionId: String, log: (String) -> Unit) {
    val cleared = try {
      InProcessIdleApkInstaller.disableSettleRace(deviceId, log)
    } catch (t: Throwable) {
      warnMayStillBeOn(deviceId, sessionId, "${t::class.java.simpleName}: ${t.message}", log)
      return
    }
    if (!cleared) warnMayStillBeOn(deviceId, sessionId, "the property did not change", log)
  }

  private fun warnMayStillBeOn(
    deviceId: TrailblazeDeviceId,
    sessionId: String,
    reason: String,
    log: (String) -> Unit,
  ) = log(
    TurboMessages.mayStillBeOn(
      scope = sessionId,
      deviceLabel = deviceId.instanceId,
      reason = reason,
      clearCommand = "adb -s ${deviceId.instanceId} shell setprop ${InProcessIdle.SETTLE_SYSPROP} 0",
    ),
  )

  /** What a session start does about turbo. */
  internal enum class Action { ATTACH, CLEAR, NOTHING }

  /**
   * The turbo decision for one session start.
   *
   * The `CLEAR` case is the one worth having: the switch the driver reads is device state that
   * outlives the session that set it and survives until reboot, and the helper stays inside the
   * app. A gate that merely skipped the attach would leave a previously-turboed device still
   * running turbo, so `trailblaze config turbo false` would silently mean "no change" — a switch
   * that cannot switch off.
   *
   * [raceCurrentlyOn] is a lambda, not a value, so the common path (turbo off, device never
   * turboed) costs nothing: it is only consulted when the gate is off, and a device that was
   * never turboed is left completely untouched rather than written to on every session.
   */
  internal fun decide(gateEnabled: Boolean, raceCurrentlyOn: () -> Boolean): Action = when {
    gateEnabled -> Action.ATTACH
    raceCurrentlyOn() -> Action.CLEAR
    else -> Action.NOTHING
  }

  /**
   * Which of a session's candidate app ids to attach to: the first [isAttachable] one.
   *
   * First rather than "any" on purpose — the candidates arrive in the target's declared priority
   * order, so a target listing both a debug and a release application id attaches to the debug one
   * it can actually help rather than to whichever happened to sort first.
   *
   * "Attachable" is deliberately more than "a helper is bundled": a target can declare several
   * applicationIds and this build can carry helpers for several of them, while a given device has
   * only one installed. Skipping the ids that are absent is what stops a device carrying only the
   * internal build from picking the debug id and failing the attach.
   *
   * [isAttachable] is injected so the choice is testable without a CLI JAR or a device.
   */
  internal fun chooseAppId(candidateAppIds: List<String>, isAttachable: (String) -> Boolean): String? =
    candidateAppIds.firstOrNull { it.isNotBlank() && isAttachable(it) }

  /** Test-only reset so a suite that mutates the singleton can restore it in `@After`. */
  fun clearForTests() {
    sessions.clear()
    explicitOverridesApplied.clear()
    declinesApplied.clear()
  }
}
