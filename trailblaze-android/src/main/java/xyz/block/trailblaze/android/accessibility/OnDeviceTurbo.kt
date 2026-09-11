package xyz.block.trailblaze.android.accessibility

import android.os.Build
import xyz.block.trailblaze.AdbCommandUtil
import xyz.block.trailblaze.android.InstrumentationArgUtil
import xyz.block.trailblaze.inprocessidle.InProcessIdle
import xyz.block.trailblaze.inprocessidle.TurboMessages
import xyz.block.trailblaze.util.Console

/**
 * Turbo for a run with no host: the on-device runner attaches the in-process idle detector itself,
 * so the accessibility driver's settle gates can race a true-idle signal instead of waiting out the
 * event-quiet heuristic.
 *
 * Until this existed, only a host-driven run could be turbo — the host attaches the detector over
 * adb before the session starts ([xyz.block.trailblaze.host.turbo.SessionTurboAttacher] in
 * `trailblaze-host`). A device-farm run is an instrumentation test with a UiAutomation and no host
 * at the other end of a cable, so nothing ever set the switch and every farm run replayed at
 * heuristic speed. This is the same feature reached from the other side: same detector, same
 * protocol, same sysprop, same `[turbo]` lines ([TurboMessages]) — a different way of getting the
 * APK onto the device and started.
 *
 * The attach sequence itself is [InProcessIdleAttacher] and is not duplicated here; this is the
 * *policy* around it — is turbo asked for, what happens when it cannot be had, and who turns the
 * switch back off.
 *
 * ### Turbo must never fail a run
 *
 * [InProcessIdleAttacher.ensureAttached] throws on any failure, and correctly so: a shard whose
 * whole purpose is to prove the detector attaches has to fail loudly rather than quietly report a
 * heuristic-speed run as a measurement of the detector. A device-farm run that merely opted into an
 * accelerator has the opposite requirement — every settle gate already races the detector against
 * the heuristic, so a failed attach costs speed and nothing else. So the throw is caught here, the
 * reason is logged, and the trail runs. The one thing that is NOT left to chance is the switch:
 * a failed attach clears it, because the settle gates' idle request is not app-scoped and a switch
 * left on by an earlier run can end a wait early on whatever process still holds the detector port.
 *
 * ### Re-attach
 *
 * Nothing here re-attaches. The detector lives inside the target app's process and dies with it, so
 * a `launchApp` that force-stops or clears the app detaches it — [InProcessIdleLaunchReattacher],
 * which [AccessibilityTrailblazeAgent] already calls around every launch, puts it back. That path
 * is gated on the same sysprop this object writes, so switching turbo on here is what switches
 * re-attach on too.
 */
object OnDeviceTurbo {

  /**
   * Instrumentation arg that opts a run into turbo. Absent reads as off — a device-farm run that never
   * heard of turbo must not pay an attach, and the on-device default has to match the documented
   * default (turbo off).
   */
  const val TURBO_ARG = "trailblaze.turbo"

  /**
   * Instrumentation arg that makes a failed attach FAIL the run instead of quietly dropping it to
   * heuristic speed.
   *
   * Turbo is normally best-effort, and has to be: a lane that opted into an accelerator wants speed
   * when it can have it and a working run when it cannot. But a lane whose purpose is to MEASURE
   * turbo needs the opposite, because the `[turbo]` lines that say what happened go to logcat, and
   * logcat is not among the artifacts a device-farm build collects. Without this, a canary that
   * failed every attach and replayed at heuristic speed reports exactly what a working one does —
   * green — and the comparison it exists for is worthless.
   *
   * So a lane sets this to convert "turbo silently declined" into a red run, using the one signal a
   * farm build always reports: the JUnit result.
   *
   * ### What green does and does not prove
   *
   * It gates THIS attach — the one before the trail — and nothing after it. On a trail whose
   * trailhead force-restarts or clears the app under test, that attach is discarded moments later:
   * the detector lives in the app's process and dies with it, and what the trail actually replays
   * against is [InProcessIdleLaunchReattacher]'s re-attach, which is best-effort by design and not
   * gated here. So on a clearing trailhead, green means "the detector could be attached on this
   * device", not "this trail replayed with turbo".
   *
   * That is still the property a canary needs — a lane that cannot attach at all is exactly what
   * silently produced a meaningless green — but a lane measuring turbo END TO END on a clearing
   * trailhead needs the re-attach gated too, which nothing does yet.
   */
  const val TURBO_REQUIRED_ARG = "trailblaze.turbo.required"

  /** Whether this instrumentation run asked for turbo. */
  fun isRequested(): Boolean = argIsTrue(TURBO_ARG)

  /** Whether this run treats a failed attach as a failure. See [TURBO_REQUIRED_ARG]. */
  fun isRequired(): Boolean = argIsTrue(TURBO_REQUIRED_ARG)

  /**
   * Strict `true`/`false`, matching [InstrumentationArgUtil.shouldCaptureSecondaryTree] and the
   * host-side gates rather than [InProcessIdleAttacher.isArgTrue].
   *
   * The difference is the warning. `TRAILBLAZE_TURBO` accepts `1` as well as `true` (the runner
   * scripts normalise it), so `-e trailblaze.turbo 1` is the obvious thing for a human driving an
   * `am instrument` by hand to type — and under a lenient parse it reads as `false`, silently, and
   * the run they meant to measure is an ordinary one. Anything that isn't `true`/`false` is off AND
   * says so.
   */
  private fun argIsTrue(argName: String): Boolean {
    val raw = InstrumentationArgUtil.getInstrumentationArg(argName) ?: return false
    return raw.lowercase().toBooleanStrictOrNull() ?: run {
      log("${TurboMessages.LOG_TAG} invalid $argName value '$raw' (expected 'true' or 'false'); reading it as false")
      false
    }
  }

  /**
   * What the device is called in the `[turbo]` lines. The host names the adb serial it dialed; from
   * inside the device there is no serial, so name the hardware the same way a farm result does.
   */
  private val deviceLabel: String get() = "${Build.MODEL} (API ${Build.VERSION.SDK_INT})"

  /** Guards the one-time end-of-run cleanup registration; see [clearOnProcessExit]. */
  @Volatile private var cleanupRegistered = false

  /**
   * The app turbo was actually attached to, or null when this run has no turbo.
   *
   * Load-bearing for [InProcessIdleLaunchReattacher]'s strict-mode enforcement, and the reason it
   * exists: `launchApp` re-attaches around whatever app the trail is launching, which is often NOT
   * the turbo target — a trail that opens a second app, a browser, or Settings has no detector
   * staged for it and never will. Only one app per device can be turbo, so enforcement has to be
   * scoped to that one; keying it off the launched app instead would fail a required-turbo trail
   * for launching something that was never meant to be turbo at all.
   */
  @Volatile
  private var attachedAppId: String? = null

  /**
   * Whether a launch of [appId] losing its detector means this run lost turbo. False for every app
   * except the one turbo attached to — see [attachedAppId].
   */
  fun isTurboTarget(appId: String): Boolean = attachedAppId == appId

  /**
   * Attaches the detector for [targetAppId] when the run asked for turbo, and reports the outcome
   * as a `[turbo]` line either way. Never throws.
   *
   * Idempotent across the trails in one instrumentation run: the attach short-circuits on a `PING`
   * that already answers for [targetAppId], so the second and later trails in a shard cost one
   * socket probe.
   *
   * [scope] identifies the run in the log line — the trail's test name, matching what the host puts
   * there (a session id).
   */
  fun start(scope: String, targetAppId: String?) {
    var plan: Plan? = null
    try {
      val requested = isRequested()
      val appId = attachTarget(requested, targetAppId)
      // The attach is the only side effect; everything about what it MEANT is decided by [decide],
      // which is pure and unit-tested.
      val attachError = appId?.let { runCatching { InProcessIdleAttacher.ensureAttached(it) }.exceptionOrNull() }
      plan = decide(requested = requested, appId = appId, attachError = attachError)
      plan.message(scope, deviceLabel)?.let { log(it) }
      // Set only on the ATTACHED outcome, and cleared otherwise: a run whose attach failed has no
      // turbo target, so a later launch of that same app must not be held to the strict contract.
      attachedAppId = if (plan.outcome == Outcome.ATTACHED) appId else null
      if (plan.clearSwitch) {
        clearIfOn(scope, spareLiveDetector = plan.outcome.sparesLiveDetector)
      } else {
        clearOnProcessExit()
      }
    } catch (t: Throwable) {
      // Blanket net for everything above, including the reads that decide whether turbo was even
      // asked for. Deliberately does not claim the run is at normal speed: if this threw, whether
      // the switch is on is exactly what we failed to establish.
      log("${TurboMessages.LOG_TAG} $scope: turbo setup skipped (${t::class.java.simpleName}: ${t.message})")
    }
    // OUTSIDE the catch, and after the switch has been dealt with: a lane that requires turbo has
    // to fail even though turbo itself never fails a run, and it must not fail before the switch it
    // asked for has been cleaned up. Rethrowing here rather than at the attach keeps the logging
    // and the clear identical on both kinds of lane.
    if (plan != null && plan.outcome.isFailure && isRequired()) {
      throw TurboRequiredButUnavailableException(plan.message(scope, deviceLabel) ?: plan.outcome.name)
    }
  }

  /**
   * Thrown only when a run set [TURBO_REQUIRED_ARG] and turbo could not be had. Its own type so a
   * reader of a red farm result can tell "the lane measuring turbo did not get turbo" apart from a
   * trail that genuinely failed.
   */
  class TurboRequiredButUnavailableException(message: String) : IllegalStateException(message)

  /**
   * What turbo did for a run. [isFailure] is what [TURBO_REQUIRED_ARG] escalates;
   * [sparesLiveDetector] is what keeps the clear from stealing another owner's switch.
   */
  internal enum class Outcome(val isFailure: Boolean, val sparesLiveDetector: Boolean) {
    /**
     * The run never asked for turbo. Not a failure on any lane.
     *
     * The one outcome that CAN spare a live detector's switch — [switchOwner] still has to prove
     * this process attached that detector. A bundle that attaches the detector directly rather than
     * through [TURBO_ARG] lands here while genuinely owning the switch: the A/B benchmark bundles
     * opt OUT of the detector rather than in, so the arg is never set even on the arm that has it.
     * Clearing it there would silently drop that run to heuristic speed and still report success.
     */
    NOT_REQUESTED(isFailure = false, sparesLiveDetector = true),

    /**
     * Turbo was asked for, but the runner could not say which app to attach to.
     *
     * Does NOT spare a live detector: a run that wanted turbo for an app it cannot name has no idea
     * whose detector is on the port, and racing waits against an unrelated app's idle is the hazard
     * the clear exists for.
     */
    NO_APP(isFailure = true, sparesLiveDetector = false),

    /** Turbo was asked for and the attach threw. Does not spare a live detector — see [NO_APP]. */
    ATTACH_FAILED(isFailure = true, sparesLiveDetector = false),

    /**
     * The detector is attached and the settle gates are racing it.
     *
     * [sparesLiveDetector] is never read for this outcome — it does not clear at all
     * ([Plan.clearSwitch] is false), and the end-of-run hook that eventually does must clear the
     * switch this run's own live detector is still holding.
     */
    ATTACHED(isFailure = false, sparesLiveDetector = false),
  }

  /**
   * What [start] should do, decided purely so every branch is testable without a device.
   *
   * [clearSwitch] is the load-bearing field: the settle gates' idle request is not app-scoped, so a
   * switch left on by an earlier run can end a wait early on whatever process still holds the
   * detector port. Every outcome except [Outcome.ATTACHED] therefore clears it — including the
   * failure paths, which is exactly when it is easiest to forget.
   *
   * Asking to clear is not the same as clearing: [Outcome.sparesLiveDetector] holds the switch back
   * when a detector is live on the port, because then it is owned rather than left over.
   */
  internal data class Plan(
    val outcome: Outcome,
    val appId: String?,
    val clearSwitch: Boolean,
    private val reason: String?,
  ) {
    /** The `[turbo]` line for this plan, or null when there is nothing worth saying. */
    fun message(scope: String, deviceLabel: String): String? = when (outcome) {
      // clearIfOn says its own piece, and only when there was actually a switch to clear.
      Outcome.NOT_REQUESTED -> null
      Outcome.ATTACHED -> TurboMessages.turboOn(scope, appId ?: "", deviceLabel)
      Outcome.NO_APP, Outcome.ATTACH_FAILED -> TurboMessages.normalSpeed(scope, reason ?: outcome.name)
    }
  }

  /** Pure: the app turbo would attach to, or null when it has nothing to attach to. */
  internal fun attachTarget(requested: Boolean, targetAppId: String?): String? =
    if (!requested) null else targetAppId?.trim()?.takeIf { it.isNotBlank() }

  /** Pure policy over the one side effect's outcome. [attachError] is null when the attach worked. */
  internal fun decide(requested: Boolean, appId: String?, attachError: Throwable?): Plan = when {
    !requested -> Plan(Outcome.NOT_REQUESTED, appId = null, clearSwitch = true, reason = null)

    appId == null -> Plan(
      Outcome.NO_APP,
      appId = null,
      clearSwitch = true,
      // "turbo was requested", never "turbo is on": this reason is rendered into a line that goes
      // on to say the run continues at normal speed, and a reader scanning for "turbo is on" would
      // read the two halves as contradicting each other.
      reason = "turbo was requested but this run resolved no app to attach to",
    )

    attachError != null -> Plan(
      Outcome.ATTACH_FAILED,
      appId = appId,
      clearSwitch = true,
      reason = "could not turn turbo on for $appId " +
        "(${attachError::class.java.simpleName}: ${attachError.message})",
    )

    else -> Plan(Outcome.ATTACHED, appId = appId, clearSwitch = false, reason = null)
  }

  /**
   * Turns the switch off at the end of the instrumentation run.
   *
   * A farm device is handed to the next run in whatever state this one leaves it, and the switch
   * survives until reboot while the detector survives inside the app — so a device left switched on
   * would have the next lane's settle gates racing a detector nobody attached for them. Registered
   * once, on the first successful attach, and fired when the instrumentation process exits rather
   * than per trail: clearing between trails in a shard would force every trail after the first to
   * pay a fresh attach.
   *
   * Not a guarantee. A shutdown hook does not run when the instrumentation process is killed
   * outright (`Process.killProcess` after `finish()`, or the platform reclaiming it), so the switch
   * CAN outlive a run on hardware that isn't wiped between lanes. The backstop is that the next
   * Trailblaze run clears it: [start] clears on every turbo-off run, which is what most lanes are.
   * A non-Trailblaze consumer of the same device inherits it, hence [END_OF_RUN_SCOPE] naming the
   * run rather than a trail — the line has to be findable without knowing which trail ran last.
   */
  private fun clearOnProcessExit() {
    if (cleanupRegistered) return
    synchronized(this) {
      if (cleanupRegistered) return
      cleanupRegistered = true
    }
    Runtime.getRuntime().addShutdownHook(
      Thread {
        // Never spares a live detector: at process exit the live detector is this run's own, and
        // leaving its switch on is precisely what hands the next lane a device racing a detector
        // nobody attached for it.
        runCatching { clearIfOn(END_OF_RUN_SCOPE, spareLiveDetector = false) }
      },
    )
  }

  /**
   * The app whose idle detector is answering on the port right now, or null when nothing is.
   *
   * A `getprop` says the switch is on; only this says whether anything is behind it. Reuses the
   * reply parser the settle gates use, so "what counts as a detector naming itself" has one
   * definition — a reply this cannot read is no detector, which clears rather than spares.
   */
  private fun liveDetectorAppId(): String? = InProcessIdleForegroundGate.helperAppIdFromPong(
    InProcessIdle.ping(connectTimeoutMs = PING_CONNECT_TIMEOUT_MS),
  )

  /**
   * Bound on the ownership probe. Loopback to a port on this device, on a path already doing a
   * `getprop` — but it runs inside test setup, so it is bounded rather than left to the socket
   * default, and a probe that times out reads as "no detector" and clears.
   */
  private const val PING_CONNECT_TIMEOUT_MS = 250

  /**
   * Who owns the switch this run was about to clear, or null when nobody can prove it and the clear
   * should proceed. Pure over its inputs; [liveDetectorAppId] is a lambda so the socket probe is
   * only paid once the cheap checks have not already decided.
   *
   * Ownership takes TWO pieces of evidence, and a live `PONG` on its own is not enough. The sysprop
   * is device-global and outlives the process that set it, so a run killed before its cleanup can
   * leave both the switch on and its detector answering. A later turbo-off run that spared on the
   * `PONG` alone would silently race that detector and report itself as a normal-speed run — which
   * is exactly the reading an A/B control arm must not produce. So the switch is spared only when
   * this same process attached a detector ([attachedInThisProcess]) AND the detector answering now
   * is still that one.
   */
  internal fun switchOwner(
    spareLiveDetector: Boolean,
    attachedInThisProcess: String?,
    liveDetectorAppId: () -> String?,
  ): String? {
    if (!spareLiveDetector) return null
    if (attachedInThisProcess == null) return null
    return attachedInThisProcess.takeIf { it == liveDetectorAppId() }
  }

  /**
   * Scope label on the end-of-run clear. Deliberately not a trail name: the hook is registered on
   * the FIRST successful attach and fires once for the whole instrumentation process, so naming the
   * trail that happened to be first would date-stamp the line to the wrong trail.
   */
  private const val END_OF_RUN_SCOPE = "end of instrumentation run"

  /**
   * Clears the switch, but only when it is actually on, so the ordinary turbo-off run costs one
   * `getprop` and no write. Says plainly when it could not.
   *
   * [spareLiveDetector] lets the switch be left alone when [switchOwner] can prove this process owns
   * it — the switch is then in use, not left over. Only [Outcome.NOT_REQUESTED] passes it, and the
   * end-of-run hook never does.
   */
  private fun clearIfOn(scope: String, spareLiveDetector: Boolean) {
    if (!InProcessIdleSettleClient.isEnabled()) return
    // Probed only on the branch that would honour it, so an end-of-run clear never pays the socket.
    val switchOwner = switchOwner(
      spareLiveDetector = spareLiveDetector,
      attachedInThisProcess = InProcessIdleAttacher.attachedAppId,
      liveDetectorAppId = { liveDetectorAppId() },
    )
    if (switchOwner != null) {
      log(TurboMessages.leavingSwitchToItsOwner(scope, switchOwner, deviceLabel))
      return
    }
    log(TurboMessages.clearing(scope, deviceLabel))
    AdbCommandUtil.execShellCommand("setprop ${InProcessIdle.SETTLE_SYSPROP} 0")
    if (InProcessIdleSettleClient.isEnabled()) {
      log(
        TurboMessages.mayStillBeOn(
          scope,
          deviceLabel,
          "the property did not change",
          "adb shell setprop ${InProcessIdle.SETTLE_SYSPROP} 0",
        ),
      )
    }
  }

  private fun log(message: String) = Console.log(message)
}
