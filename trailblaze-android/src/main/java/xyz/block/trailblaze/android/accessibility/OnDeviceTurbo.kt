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
 * ### Re-attach, and why the pre-trail attach is lazy
 *
 * Nothing here re-attaches. The detector lives inside the target app's process and dies with it, so
 * a `launchApp` that force-stops or clears the app detaches it — [InProcessIdleLaunchReattacher],
 * which [AccessibilityTrailblazeAgent] already calls around every launch, puts it back. That path
 * is gated on the same sysprop this object writes, so switching turbo on here is what switches
 * re-attach on too.
 *
 * Which is why the attach before the trail only happens when the target is already running
 * ([InProcessIdleAttacher.attachNowOrAtLaunch]). A target that is not running cannot be driven
 * until the trail launches it, and the launch attaches on its way in — so attaching first would
 * cold-start the app twice for a detector the trailhead's clear then kills. On a device farm that
 * hands every trail a fresh device that was every trail: 20–47 s each, charged to turbo, and outside
 * the session's report clock. Deferring costs nothing a trail can see; the switch is on and the
 * detector APK installed either way, and the settle gates race a port nobody answers on (a fast
 * refusal, then the heuristic) until the launch.
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
   * ### What green proves
   *
   * Both attaches are gated: the one before the trail here, and the one around every `launchApp`
   * in [InProcessIdleLaunchReattacher], which throws the same exception when a launch of the turbo
   * target leaves it with no detector. The launch gate is the one that matters on a trail whose
   * trailhead clears the app — the pre-trail detector dies with the process, and the launch
   * re-attach is what the trail replays against — and it is the ONLY gate on a trail whose target
   * was not running when it started, because the pre-trail attach is then deferred to that launch
   * ([Outcome.DEFERRED_TO_LAUNCH]). Either way, green means the trail replayed with turbo, not
   * merely that a detector could be attached once.
   *
   * The launch gate is scoped to the turbo target ([isTurboTarget]), which a deferred attach names
   * as firmly as a completed one — otherwise deferring would quietly turn strict mode off.
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
   * The app turbo attached to — or armed for, when the pre-trail attach was deferred to the app's
   * first launch — or null when this run has no turbo.
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

  /** What [start]'s attach ended up doing, or null when [start] never got that far this trail. */
  @Volatile
  private var lastOutcome: Outcome? = null

  /**
   * The app a detector has been PROVEN to serve since [start], or null when none has.
   *
   * A deferred attach is a promise the trail's first launch has to keep, and until this is set
   * nothing has kept it. Distinct from [attachedAppId], which names the app turbo is *for* — the
   * whole gap this closes is a trail where those two differ for its entire length.
   */
  @Volatile
  private var detectorConfirmedFor: String? = null

  /**
   * Records that a detector is serving [appId] right now. Called from every place that has just
   * seen the evidence: an attach that completed, a launch re-attach whose PONG arrived, and a
   * launch that found one already attached.
   *
   * Scoped to the turbo target, because this runs after EVERY launch. A trail that opens a browser,
   * Settings or a second app confirms nothing about the app turbo attached to, and recording it
   * here would do two kinds of damage: clear a loss the target never recovered from, and overwrite
   * the record that the target's deferred attach was already kept — failing a trail for launching
   * something that was never meant to be turbo.
   */
  fun noteDetectorConfirmed(appId: String) = noteDetectorConfirmed(appId, attachedAppId)

  /** [noteDetectorConfirmed] with the target injected, so the scope is testable without a device. */
  internal fun noteDetectorConfirmed(appId: String, targetAppId: String?) {
    if (targetAppId != appId) return
    detectorConfirmedFor = appId
    // A later launch that got its detector supersedes an earlier one that did not. Without this a
    // trail that recovered on its second launch would still fail at the end for the first.
    detectorLostReason = null
  }

  /**
   * Records that a launch of [appId] was left with no detector, with [reason] naming what happened.
   *
   * The post-launch confirmation runs after the trail has moved on, so it has no call stack to
   * throw into — a strict run's verdict has to be carried to the end of the trail instead. Only the
   * turbo target is recorded: a trail that launches a browser or Settings has not lost anything.
   */
  fun noteDetectorLost(reason: String) {
    detectorLostReason = reason
  }

  /** Why the turbo target was last left with no detector, or null if it never was. */
  @Volatile
  private var detectorLostReason: String? = null

  /** [detectorLostReason], so the confirmation's bookkeeping is checkable without a device. */
  internal val detectorLossReason: String? get() = detectorLostReason

  /** [detectorConfirmedFor], for the same reason. */
  internal val detectorConfirmedApp: String? get() = detectorConfirmedFor

  /**
   * Whether a launch of [appId] is the one carrying a deferred pre-trail attach — the run's FIRST
   * attach of that app, not a re-attach of an app that has already held a detector.
   *
   * The distinction is a budget: a re-attach follows a force-stop of a process the device has
   * already started once this run, and a first attach does not, so the two are not owed the same
   * patience. Pure, so the policy is testable without a device.
   */
  internal fun carriesFirstAttach(
    outcome: Outcome?,
    confirmedAppId: String?,
    targetAppId: String?,
    appId: String,
  ): Boolean = outcome == Outcome.DEFERRED_TO_LAUNCH &&
    targetAppId == appId &&
    confirmedAppId != appId

  /** [carriesFirstAttach] against this run's state. */
  fun carriesFirstAttach(appId: String): Boolean =
    carriesFirstAttach(lastOutcome, detectorConfirmedFor, attachedAppId, appId)

  /**
   * Whether an armed-but-deferred attach was actually kept. Pure, so the policy is testable without
   * a device.
   *
   * False in exactly one case: the run required turbo, the pre-trail attach was deferred to the
   * first launch, and no launch ever proved a detector for the target. [Outcome.ATTACHED] is kept
   * unconditionally because the pre-trail attach already proved its detector, and a run that did not
   * require turbo is never failed for an accelerator it merely asked for.
   */
  internal fun deferredAttachKept(
    outcome: Outcome?,
    required: Boolean,
    confirmedAppId: String?,
    targetAppId: String?,
  ): Boolean = !(required && outcome == Outcome.DEFERRED_TO_LAUNCH && confirmedAppId != targetAppId)

  /**
   * Fails a `turboRequired` run whose deferred attach was never kept.
   *
   * The launch gate in [InProcessIdleLaunchReattacher] only fires on a launch that HAPPENED. A trail
   * whose target was never launched through `launchApp` — it taps its way in from a screen that was
   * already up, or its first action resumes an app the deferral assumed was not running — never
   * reaches that gate, so a deferred attach would otherwise report green having replayed the whole
   * trail at heuristic speed. That is the silent green [TURBO_REQUIRED_ARG] exists to eliminate, so
   * it is checked once at the end of the trail instead.
   */
  /**
   * The reason a `turboRequired` run should fail at the end of its trail, or null when it held
   * turbo — or never asked for it. Pure, so the precedence between the two ways a trail can end up
   * without a detector is testable without a device.
   *
   * [lostReason] wins over the armed-but-never-attached case because it names a launch that
   * actually happened, which is a more useful verdict than the generic one. A launch that later
   * got its detector has already cleared it (see [noteDetectorConfirmed]), so a recovered trail
   * reaches here with nothing recorded.
   */
  internal fun turboFailureAtEndOfTrail(
    outcome: Outcome?,
    required: Boolean,
    confirmedAppId: String?,
    targetAppId: String?,
    lostReason: String?,
  ): String? {
    if (!required) return null
    if (lostReason != null) return "$targetAppId lost turbo at a launch — $lostReason"
    if (deferredAttachKept(outcome, required = true, confirmedAppId, targetAppId)) return null
    return "turbo was armed for $targetAppId but no launch ever attached the idle detector, so " +
      "the whole trail ran at heuristic speed"
  }

  fun requireTurboHeld(scope: String) {
    // The verdict is only sound once the launch confirmation has stopped writing to it. Gated on
    // strict mode so an ordinary run — which cannot fail on the answer anyway — keeps the whole
    // launch saving that detaching the confirmation bought.
    if (!isRequired()) return
    InProcessIdleLaunchReattacher.awaitConfirmationSettled(attachedAppId)
    val failure = turboFailureAtEndOfTrail(
      outcome = lastOutcome,
      required = isRequired(),
      confirmedAppId = detectorConfirmedFor,
      targetAppId = attachedAppId,
      lostReason = detectorLostReason,
    ) ?: return
    throw TurboRequiredButUnavailableException("${TurboMessages.LOG_TAG} $scope: $failure")
  }

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
    // Invalidate FIRST, then reset. A confirmation the previous trail left polling would otherwise
    // write into the cleared fields, either confirming a detector this trail never earned or
    // reddening it for the previous trail's launch. Once this returns, no such poll can still write
    // (it checks and records under one lock), so whatever it wrote landed before the reset below.
    InProcessIdleLaunchReattacher.invalidateInFlightConfirmations()
    // Reset BEFORE the attach: the attach itself is what confirms a detector on the non-deferred
    // paths, and a shard runs several trails in one process — carrying the previous trail's
    // confirmation forward would let a deferred attach inherit a promise it never kept.
    lastOutcome = null
    detectorConfirmedFor = null
    detectorLostReason = null
    try {
      val requested = isRequested()
      val appId = attachTarget(requested, targetAppId)
      // Named BEFORE the attach, not just after the decision below: the attach itself is what
      // confirms a detector on the ATTACHED path, and [noteDetectorConfirmed] only records evidence
      // about the turbo target. Leaving the target unnamed until afterwards would throw that
      // confirmation away and fail an ATTACHED run for an attach that plainly succeeded. Every
      // outcome that does NOT leave turbo in play clears it again below.
      attachedAppId = appId
      // The attach is the only side effect; everything about what it MEANT is decided by [decide],
      // which is pure and unit-tested.
      val attach = appId?.let { runCatching { InProcessIdleAttacher.attachNowOrAtLaunch(it) } }
      plan = decide(
        requested = requested,
        appId = appId,
        attachError = attach?.exceptionOrNull(),
        deferredToLaunch = attach?.getOrNull() == InProcessIdleAttacher.Attach.DEFERRED_TO_LAUNCH,
      )
      plan.message(scope, deviceLabel)?.let { log(it) }
      // Named on the outcomes that leave turbo in play — attached now, or armed for the first
      // launch — and cleared otherwise: a run whose attach failed has no turbo target, so a later
      // launch of that same app must not be held to the strict contract.
      attachedAppId = if (plan.outcome.namesTurboTarget) appId else null
      lastOutcome = plan.outcome
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
  internal enum class Outcome(
    val isFailure: Boolean,
    val sparesLiveDetector: Boolean,
    /**
     * Whether this outcome leaves turbo in play for the target app, so a later launch of that app
     * is held to [TURBO_REQUIRED_ARG] — see [isTurboTarget]. True for an attach that completed AND
     * for one deferred to the first launch; deferring is a promise the launch has to keep.
     */
    val namesTurboTarget: Boolean = false,
  ) {
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
    ATTACHED(isFailure = false, sparesLiveDetector = false, namesTurboTarget = true),

    /**
     * Turbo was asked for, the target is not running, so the attach is left to its first launch
     * ([InProcessIdleAttacher.Attach.DEFERRED_TO_LAUNCH]). Not a failure: the switch is on and the
     * launch re-attach is gated. Like [ATTACHED] it never clears — the switch is this run's — and
     * the end-of-run hook turns it off.
     */
    DEFERRED_TO_LAUNCH(isFailure = false, sparesLiveDetector = false, namesTurboTarget = true),
  }

  /**
   * What [start] should do, decided purely so every branch is testable without a device.
   *
   * [clearSwitch] is the load-bearing field: the settle gates' idle request is not app-scoped, so a
   * switch left on by an earlier run can end a wait early on whatever process still holds the
   * detector port. Every outcome except [Outcome.ATTACHED] and [Outcome.DEFERRED_TO_LAUNCH]
   * therefore clears it — including the failure paths, which is exactly when it is easiest to
   * forget.
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
      Outcome.DEFERRED_TO_LAUNCH -> TurboMessages.turboArmedForLaunch(scope, appId ?: "", deviceLabel)
      Outcome.NO_APP, Outcome.ATTACH_FAILED -> TurboMessages.normalSpeed(scope, reason ?: outcome.name)
    }
  }

  /** Pure: the app turbo would attach to, or null when it has nothing to attach to. */
  internal fun attachTarget(requested: Boolean, targetAppId: String?): String? =
    if (!requested) null else targetAppId?.trim()?.takeIf { it.isNotBlank() }

  /**
   * Pure policy over the one side effect's outcome. [attachError] is null when the attach worked;
   * [deferredToLaunch] is whether "worked" meant the attacher left it to the app's first launch.
   */
  internal fun decide(
    requested: Boolean,
    appId: String?,
    attachError: Throwable?,
    deferredToLaunch: Boolean = false,
  ): Plan = when {
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

    deferredToLaunch -> Plan(Outcome.DEFERRED_TO_LAUNCH, appId = appId, clearSwitch = false, reason = null)

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
    // The debug app the attach named is a persistent setting and goes with the switch, so a later
    // non-turbo run on this same device gets its ANRs back. Rides the switch on purpose: a turbo-off
    // run whose switch is already off still costs one `getprop` and no shell write.
    AdbCommandUtil.execShellCommand(InProcessIdle.clearDebugAppShellArgs().joinToString(" "))
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
