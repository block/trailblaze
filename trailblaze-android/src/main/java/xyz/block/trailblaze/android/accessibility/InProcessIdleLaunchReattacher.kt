package xyz.block.trailblaze.android.accessibility

import xyz.block.trailblaze.AdbCommandUtil
import xyz.block.trailblaze.inprocessidle.InProcessIdle
import xyz.block.trailblaze.inprocessidle.TurboMessages
import xyz.block.trailblaze.util.Console

/**
 * Re-attaches the in-process idle (see [InProcessIdleSettleClient]) around `launchApp` so a launch that
 * stops or clears the target app doesn't leave the rest of the run settling at heuristic speed.
 *
 * The idle detector is a foreign instrumentation running INSIDE the target app's process, so anything
 * that kills that process — `am force-stop` from a FORCE_RESTART/REINSTALL launch, `pm clear`, a
 * trailhead that resets app state — detaches it. This helper makes `launchApp` self-healing:
 * when the settle-race sysprop is on and a convention-named idle detector package is installed for the
 * target, it re-runs `am instrument` before the foreground launch and confirms the idle detector serves
 * again afterwards.
 *
 * Attach ordering is load-bearing (same contract as [InProcessIdleAttacher]):
 *  - `am instrument` itself restarts the target's process, so it must run BEFORE the foreground
 *    launch — attaching after would kill the UI the launch just brought up.
 *  - The foreground launch must follow PROMPTLY: `am instrument` cold-starts the target
 *    headless, and a heavy app's Application init can overrun the platform's ~20s background
 *    proc-start ANR watchdog unless an activity start keeps it visible.
 *
 * Everything here is best-effort: the idle detector is an accelerator, and the settle gates race it
 * against the standard heuristic ([InProcessIdleSettleClient.raceIdleAgainstHeuristic]) — a failed
 * re-attach means gates settle exactly as they would with the feature off. Failures are logged
 * loudly (`[inprocess-idle-reattach]`) so a silently-degraded run is still diagnosable.
 *
 * **The one exception is a run that demanded turbo** ([OnDeviceTurbo.TURBO_REQUIRED_ARG]). This is
 * the re-attach a trail with a clearing trailhead actually rides, so leaving it best-effort there
 * would let such a run report "turbo required" and still replay every action at heuristic speed. A
 * launch that leaves the app with no detector therefore throws on those lanes, and only those.
 *
 * Idle detector package convention: see [InProcessIdle.packageFor] — the same convention
 * [InProcessIdleAttacher] and the idle detector APK build use.
 */
object InProcessIdleLaunchReattacher {

  /**
   * Bound on the post-launch PONG wait; the attach already started, this only confirms it. Wider
   * than the naive "warm process re-binds instantly" guess: the reattach follows a force-stop, so
   * `am instrument` cold-starts the target again, and a heavy Compose app's re-init on a loaded CI
   * emulator can take double-digit seconds even with dexopt/page-cache warm. Sized to the trail's
   * own post-reattach assertion window (30s) so a slow-but-successful reattach still confirms
   * rather than falling back to heuristic speed (and, on the farm shards, failing the reattach
   * assertion). Overshooting only delays the heuristic fallback on a genuine failure.
   */
  private const val PONG_WAIT_MS = 30_000L
  private const val PONG_POLL_INTERVAL_MS = 500L

  /**
   * Convention-named idle detector package for a target applicationId. Private: the convention has
   * one public home, [InProcessIdle.packageFor] — this is a local shorthand, not a second name for
   * it.
   */
  private fun inProcessIdlePackageFor(appId: String): String = InProcessIdle.packageFor(appId)

  /**
   * What [attachBeforeLaunch] decided, pure over its inputs so the policy is unit-testable.
   *
   * [leavesAppWithoutDetector] says whether the decision ends with this app having NO detector.
   * It is what [OnDeviceTurbo.TURBO_REQUIRED_ARG] gates on: a run that requires turbo must not pass
   * a launch that quietly left the app running at heuristic speed. Carried on the enum rather than
   * written as a `when` at the call site so adding a decision forces a choice about it.
   */
  internal enum class Decision(val leavesAppWithoutDetector: Boolean) {
    /**
     * Settle-race sysprop is off — the run never opted into idle detector mode. Unreachable from
     * [attachBeforeLaunch], which returns before deciding; kept so the policy stays total and
     * testable on its own. Not a failure: a run without turbo is not a run that lost turbo.
     */
    SKIP_DISABLED(leavesAppWithoutDetector = false),

    /** An idle detector already answers PING for this app (e.g. a RESUME launch) — nothing to do. */
    SKIP_ALREADY_ATTACHED(leavesAppWithoutDetector = false),

    /**
     * Port 7777 serves a DIFFERENT app's idle detector. Only one idle detector can serve per device, and
     * detaching the other one means force-stopping that app — too destructive for a launch
     * side-effect (a multi-app trail may be mid-flow in it). Skip and say so.
     */
    SKIP_PORT_HELD_BY_OTHER(leavesAppWithoutDetector = true),

    /** No convention-named idle detector package is installed for this target. */
    SKIP_NOT_INSTALLED(leavesAppWithoutDetector = true),

    /**
     * Idle detector mode is on, the package is present, and nothing serves — re-attach. Whether the
     * app ends up with a detector is not known yet; [awaitAttachedAfterLaunch] decides that.
     */
    ATTACH(leavesAppWithoutDetector = false),
  }

  /**
   * Pure attach policy. [pingReply] is the current `PING` answer (null when nothing serves);
   * checked before [inProcessIdleInstalled] — a lambda, so its shell probe only runs when the ping
   * didn't already decide (the common already-attached case costs one socket probe).
   */
  internal fun decide(
    syspropEnabled: Boolean,
    inProcessIdleInstalled: () -> Boolean,
    pingReply: String?,
    appId: String,
  ): Decision = when {
    !syspropEnabled -> Decision.SKIP_DISABLED
    pingReply == "PONG $appId" -> Decision.SKIP_ALREADY_ATTACHED
    pingReply?.startsWith("PONG ") == true -> Decision.SKIP_PORT_HELD_BY_OTHER
    !inProcessIdleInstalled() -> Decision.SKIP_NOT_INSTALLED
    else -> Decision.ATTACH
  }

  /**
   * Which app a decision PROVES holds the port, or null when the PING proved nothing.
   *
   * Every well-formed PONG names a live detector, whether or not it is the one this launch wanted,
   * so both skip-on-PONG decisions are usable identity evidence. Dropping the other-app case would
   * leave the settle gates with a stale "identity unknown" — and unknown means they race, which is
   * exactly the wrong-process settle the foreground gate exists to prevent.
   */
  internal fun provenHelperAppId(decision: Decision, pingReply: String?, appId: String): String? =
    when (decision) {
      Decision.SKIP_ALREADY_ATTACHED -> appId
      Decision.SKIP_PORT_HELD_BY_OTHER -> InProcessIdleForegroundGate.helperAppIdFromPong(pingReply)
      else -> null
    }

  /** See [InProcessIdle.amInstrumentReportedFailure] — kept as a local name for the tests. */
  internal fun amInstrumentReportedFailure(output: String): Boolean =
    InProcessIdle.amInstrumentReportedFailure(output)

  /**
   * Phase 1 — call AFTER the launch's force-stop/clear, BEFORE the foreground launch.
   * Starts `am instrument` for the convention-named idle detector when the policy says to.
   *
   * Returns true when an attach was started and [awaitAttachedAfterLaunch] should be called
   * once the foreground launch has been dispatched.
   */
  fun attachBeforeLaunch(appId: String): Boolean {
    // Decided before any probing: launchApp runs on every trail, and a run that never opted
    // into idle detector mode must not pay a socket probe + shell exec per launch. [decide] keeps its
    // own SKIP_DISABLED leg so the policy stays complete (and unit-tested) on its own.
    if (!InProcessIdleSettleClient.isEnabled()) return false
    val pingReply = ping()
    val decision = try {
      decide(
        syspropEnabled = true,
        // `pm path` over the shell needs no <queries> package-visibility declaration.
        inProcessIdleInstalled = {
          AdbCommandUtil.execShellCommand("pm path ${inProcessIdlePackageFor(appId)}")
            .contains("package:")
        },
        pingReply = pingReply,
        appId = appId,
      )
    } catch (t: Throwable) {
      Console.log("[inprocess-idle-reattach] skipping for $appId — probe failed (${t.message})")
      return false
    }
    // The PING that produced this decision already proves whose detector holds the port; hand that
    // to the settle gates so they don't re-probe — or, worse, keep racing on an unknown identity.
    provenHelperAppId(decision, pingReply, appId)?.let { InProcessIdleForegroundGate.noteAttached(it) }
    when (decision) {
      Decision.SKIP_DISABLED,
      Decision.SKIP_NOT_INSTALLED,
      -> {
        requireTurboIfDemanded(decision, appId)
        return false
      }

      Decision.SKIP_ALREADY_ATTACHED -> {
        Console.log("[inprocess-idle-reattach] idle detector already attached to $appId")
        return false
      }

      Decision.SKIP_PORT_HELD_BY_OTHER -> {
        Console.log(
          "[inprocess-idle-reattach] port ${InProcessIdle.PORT} serves another app's idle detector ($pingReply) — " +
            "skipping re-attach for $appId (one idle detector per device)",
        )
        requireTurboIfDemanded(decision, appId)
        return false
      }

      Decision.ATTACH -> {
        // From here the detector's identity is in flux — `am instrument` restarts the target's
        // process — so no settle gate may keep trusting a cached one.
        InProcessIdleForegroundGate.invalidate()
        val inProcessIdlePackage = inProcessIdlePackageFor(appId)
        Console.log("[inprocess-idle-reattach] re-attaching $inProcessIdlePackage to $appId")
        val output = try {
          AdbCommandUtil.execShellCommand(
            "am instrument $inProcessIdlePackage/${InProcessIdle.INSTRUMENTATION_CLASS}",
          )
        } catch (t: Throwable) {
          Console.log("[inprocess-idle-reattach] am instrument failed for $inProcessIdlePackage: ${t.message}")
          return false
        }
        if (amInstrumentReportedFailure(output)) {
          Console.log("[inprocess-idle-reattach] am instrument failed for $inProcessIdlePackage: ${output.trim()}")
          return false
        }
        return true
      }
    }
  }

  /**
   * Phase 2 — call after the foreground launch that follows a true [attachBeforeLaunch].
   * Confirms the idle detector serves again, logging the outcome either way. Best-effort: a timeout
   * logs a warning and returns; the settle gates keep racing and falling back regardless.
   */
  fun awaitAttachedAfterLaunch(appId: String) {
    val expected = "PONG $appId"
    val deadline = System.currentTimeMillis() + PONG_WAIT_MS
    while (System.currentTimeMillis() < deadline) {
      if (ping() == expected) {
        Console.log("[inprocess-idle-reattach] attached: $expected")
        InProcessIdleForegroundGate.noteAttached(appId)
        return
      }
      Thread.sleep(PONG_POLL_INTERVAL_MS)
    }
    InProcessIdleForegroundGate.invalidate()
    Console.log(
      "[inprocess-idle-reattach] idle detector for $appId never answered PING within ${PONG_WAIT_MS}ms — " +
        "settle gates will fall back to the event-quiet heuristic",
    )
    // Same target scope as [requireTurboIfDemanded]: only the app turbo attached to is held to the
    // strict contract.
    if (OnDeviceTurbo.isRequired() && OnDeviceTurbo.isTurboTarget(appId)) {
      throw OnDeviceTurbo.TurboRequiredButUnavailableException(
        "${TurboMessages.LOG_TAG} $appId lost turbo at a launch: the idle detector never answered " +
          "PING within ${PONG_WAIT_MS}ms after re-attach, so the rest of this trail would have run " +
          "at heuristic speed",
      )
    }
  }

  /**
   * Fails the run when a launch left the app with no detector and the run demanded turbo.
   *
   * Without this, `turboRequired` gated only the attach BEFORE a trail — and a trailhead that
   * force-restarts or clears the app throws that attach away, so the trail actually rides this
   * re-attach. A trail could therefore satisfy "turbo required" and then replay every action at
   * heuristic speed, which is the silent green the flag exists to eliminate.
   *
   * Off unless the run set [OnDeviceTurbo.TURBO_REQUIRED_ARG], so the ordinary best-effort contract
   * in this object's header is unchanged for every lane that did not ask.
   */
  private fun requireTurboIfDemanded(decision: Decision, appId: String) {
    if (!decision.leavesAppWithoutDetector) return
    if (!OnDeviceTurbo.isRequired()) return
    // Scoped to the app turbo actually attached to. This runs around EVERY launch, and a trail that
    // opens a second app, a browser or Settings has no detector staged for it and never will — only
    // one app per device can be turbo. Without this scope, strict mode would fail such a trail for
    // launching something that was never meant to be turbo.
    if (!OnDeviceTurbo.isTurboTarget(appId)) return
    throw OnDeviceTurbo.TurboRequiredButUnavailableException(
      "${TurboMessages.LOG_TAG} $appId lost turbo at a launch ($decision), so the rest of this " +
        "trail would have run at heuristic speed",
    )
  }

  /**
   * Tight connect bound: this probe runs on EVERY `launchApp`, including on runs that never opted
   * into the detector, so an unattached device must pay next to nothing here.
   */
  private const val PING_CONNECT_TIMEOUT_MS = 250

  private fun ping(): String? = InProcessIdle.ping(PING_CONNECT_TIMEOUT_MS)
}
