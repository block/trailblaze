package xyz.block.trailblaze.android.accessibility

import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
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
 * would let such a run report "turbo required" and still replay every action at heuristic speed.
 * EVERY leg that ends with the target app having no detector therefore throws on those lanes, and
 * only those — a skip decision, a probe that threw, a refused `am set-debug-app`, an `am instrument`
 * that threw or was rejected, and a post-launch PONG that never came.
 *
 * Idle detector package convention: see [InProcessIdle.packageFor] — the same convention
 * [InProcessIdleAttacher] and the idle detector APK build use.
 */
object InProcessIdleLaunchReattacher {

  /**
   * Bound on the post-launch PONG wait when this launch RE-attaches; the attach already started,
   * this only confirms it. Wider than the naive "warm process re-binds instantly" guess: the
   * reattach follows a force-stop, so `am instrument` cold-starts the target again, and a heavy
   * Compose app's re-init on a loaded CI emulator can take double-digit seconds even with
   * dexopt/page-cache warm. Sized to the trail's own post-reattach assertion window (30s) so a
   * slow-but-successful reattach still confirms rather than falling back to heuristic speed (and,
   * on the farm shards, failing the reattach assertion).
   */
  private const val REATTACH_PONG_WAIT_MS = 30_000L

  /**
   * Bound on the same wait when this launch carries the run's FIRST attach of the app, because the
   * pre-trail attach was deferred to it.
   *
   * [REATTACH_PONG_WAIT_MS] is sized for a process the device has already started once this run.
   * A first attach has none of that: no warmed page cache, no resident dex, and the launch usually
   * follows a data clear. On a slow tablet that is the difference between confirming at ~40s and
   * timing out at 30s — the whole trail then fails a required-turbo run for an attach that was
   * merely slow. Matched to [InProcessIdleAttacher]'s own deadline, which is the budget this attach
   * would have had if it had not been deferred. Overshooting only delays the heuristic fallback on
   * a genuine failure.
   */
  private const val FIRST_ATTACH_PONG_WAIT_MS = 120_000L
  private const val PONG_POLL_INTERVAL_MS = 500L

  /**
   * Which launch of a given app a pending confirmation belongs to, so a confirmation still polling
   * from an earlier launch can tell that its answer is no longer about that app's current process.
   *
   * Keyed by app because only a launch of app A can replace A's process. A trail that opens a
   * browser, Settings or a second app has done nothing to the turbo target's detector, and a single
   * shared counter would have let that unrelated launch discard the target's pending verdict —
   * silently dropping a real loss on a `turboRequired` run, which is the failure this whole
   * confirmation exists to catch.
   */
  private val launchEpochs = ConcurrentHashMap<String, AtomicInteger>()

  private fun epochFor(appId: String): AtomicInteger =
    launchEpochs.getOrPut(appId) { AtomicInteger(0) }

  /**
   * A confirmation still polling, paired with the moment its own budget expires.
   *
   * Held so [awaitConfirmationSettled] can wait the verdict out rather than read a field the poll
   * has not written yet.
   */
  private class PendingConfirmation(val thread: Thread, val deadlineElapsedMs: Long)

  /** Keyed by app for the same reason [launchEpochs] is: each app's verdict is its own. */
  private val pendingConfirmations = ConcurrentHashMap<String, PendingConfirmation>()

  /**
   * The PONG budget this launch is owed. A deferred pre-trail attach makes its launch the first
   * attach; every other launch is a re-attach.
   */
  private fun pongWaitMs(appId: String): Long =
    if (OnDeviceTurbo.carriesFirstAttach(appId)) FIRST_ATTACH_PONG_WAIT_MS else REATTACH_PONG_WAIT_MS

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
    // Before any decision: whatever this launch does to THIS app, a confirmation still polling from
    // a previous launch of it is now describing a process this launch is about to replace. Scoped
    // to [appId] — this runs on every launch, including ones for apps turbo never attached to.
    epochFor(appId).incrementAndGet()
    // The split host needs no re-attach: the app's own package carries the detector, and the
    // platform starts it with the app. What the launch still owes is the confirmation — the
    // pre-trail attach deferred to this launch, and a strict run's accounting is settled by the
    // PONG that follows it, exactly as after an `am instrument`. Decided before the PING: the
    // launch just stopped the app, so the port answers for nothing until the launch below.
    if (InProcessIdleAttacher.isSplitResident(appId)) {
      InProcessIdleForegroundGate.invalidate()
      Console.log("[inprocess-idle-reattach] $appId carries split ${InProcessIdle.SPLIT_NAME} — no re-attach needed, confirming after launch")
      return true
    }
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
      requireTurboIfDemanded("probe failed: ${t.message}", appId)
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
        // A PONG naming this app is proof a detector serves it, which is what a deferred pre-trail
        // attach promised. Keeps the end-of-trail check honest on a launch that needed no re-attach.
        OnDeviceTurbo.noteDetectorConfirmed(appId)
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
        // The launch just force-stopped or cleared the app, so the force-stop this carries is
        // free. Without it an ANR in the re-attached app ends the instrumentation and kills the
        // app. See [InProcessIdle.keepAliveThroughAnrShellArgs]. Its own try, so the strict throw
        // below is not caught by the `am instrument` handler and relabelled.
        val debugAppOutput = try {
          AdbCommandUtil.execShellCommand(InProcessIdle.keepAliveThroughAnrShellArgs(appId).joinToString(" "))
        } catch (t: Throwable) {
          Console.log("[inprocess-idle-reattach] am set-debug-app failed for $appId: ${t.message}")
          requireTurboIfDemanded("am set-debug-app failed: ${t.message}", appId)
          return false
        }
        // Silent on success, so anything printed is a refusal. Attaching anyway would leave the
        // app instrumented but unprotected, which is strictly worse than not re-attaching.
        if (InProcessIdle.setDebugAppReportedFailure(debugAppOutput)) {
          Console.log("[inprocess-idle-reattach] am set-debug-app refused $appId: ${debugAppOutput.trim()}")
          requireTurboIfDemanded("am set-debug-app refused it", appId)
          return false
        }
        val output = try {
          AdbCommandUtil.execShellCommand(
            "am instrument $inProcessIdlePackage/${InProcessIdle.INSTRUMENTATION_CLASS}",
          )
        } catch (t: Throwable) {
          Console.log("[inprocess-idle-reattach] am instrument failed for $inProcessIdlePackage: ${t.message}")
          // The debug app named above is a persistent setting; with no detector it has no job.
          // Best-effort: this runs on a path that has ALREADY failed to attach, and a second shell
          // failure here would escape before the launch itself — turning a lost accelerator into a
          // lost trail on a run that never required turbo.
          runCatching { AdbCommandUtil.execShellCommand(InProcessIdle.clearDebugAppShellArgs().joinToString(" ")) }
          requireTurboIfDemanded("am instrument failed: ${t.message}", appId)
          return false
        }
        if (amInstrumentReportedFailure(output)) {
          Console.log("[inprocess-idle-reattach] am instrument failed for $inProcessIdlePackage: ${output.trim()}")
          // The debug app named above is a persistent setting; with no detector it has no job.
          // Best-effort: this runs on a path that has ALREADY failed to attach, and a second shell
          // failure here would escape before the launch itself — turning a lost accelerator into a
          // lost trail on a run that never required turbo.
          runCatching { AdbCommandUtil.execShellCommand(InProcessIdle.clearDebugAppShellArgs().joinToString(" ")) }
          requireTurboIfDemanded("am instrument was rejected", appId)
          return false
        }
        return true
      }
    }
  }

  /**
   * Phase 2 — call after the foreground launch that follows a true [attachBeforeLaunch].
   *
   * Returns immediately. The detector is confirmed on a background thread, because waiting for it
   * here buys the trail nothing: the launch has already started the app, the detector binds while
   * the app finishes starting, and the trail's very next act is to wait for that same startup. The
   * two waits are for one event, so running them in sequence adds the shorter of them to every
   * launch — 15s on a phone, 25s on a tablet, against a settle saving of about the same size.
   *
   * Nothing is unsafe about proceeding without the confirmation. A settle gate probes the detector
   * port itself ([InProcessIdleForegroundGate]); until one answers, the identity reads as unknown,
   * which races the heuristic and falls back to it — exactly what a run without turbo does. The
   * moment the detector binds, every later gate uses it.
   *
   * What this cannot do is throw: the trail has moved on. A strict run's loss is recorded on
   * [OnDeviceTurbo] instead and fails the trail at its end, where the whole-trail gate already
   * lives.
   */
  fun confirmAttachedAfterLaunch(appId: String) {
    // Read on the caller's thread: it describes the launch that just happened, and a confirmation
    // from this very launch would otherwise be able to narrow its own budget.
    val waitMs = pongWaitMs(appId)
    val epoch = epochFor(appId).get()
    // Minted here, not on the new thread, so the record and the poll it describes share ONE
    // deadline — a thread that takes a moment to start would otherwise poll past the instant the
    // end-of-trail gate believes it stopped. Monotonic, like [InProcessIdleAttacher]'s own wait: a
    // farm device that syncs its clock mid-poll would expire this early or overrun it, and the
    // wider first-attach budget leaves more room for that to happen.
    val deadline = SystemClock.elapsedRealtime() + waitMs
    val thread = Thread(
      { confirmUntilDeadline(appId, waitMs, deadline, epoch) },
      "trailblaze-inprocess-idle-confirm",
    ).apply { isDaemon = true }
    pendingConfirmations[appId] = PendingConfirmation(thread, deadline)
    thread.start()
  }

  /**
   * Blocks until the launch confirmation in flight has recorded its verdict, or until its own
   * budget is spent.
   *
   * The confirmation is detached so a launch does not pay for it, which leaves a window at the end
   * of a trail where the detector has already been lost but nothing has written that down yet. A
   * strict run reading the verdict in that window sees no loss and reports green — the silent green
   * `turboRequired` exists to eliminate. So the gate, and only the gate, waits.
   *
   * Bounded by the confirmation's OWN deadline rather than a fresh timeout: the poll is already
   * committed to stopping then, so this adds no wall clock to a trail beyond what the poll had
   * left. A run that never asked for strict turbo never calls this and keeps the full launch saving.
   */
  fun awaitConfirmationSettled(appId: String?) {
    val inFlight = appId?.let { pendingConfirmations[it] } ?: return
    val remainingMs = confirmationJoinBudgetMs(inFlight.deadlineElapsedMs, SystemClock.elapsedRealtime())
    if (remainingMs <= 0) return
    try {
      inFlight.thread.join(remainingMs)
    } catch (e: InterruptedException) {
      // Restore the flag rather than swallow it. This runs on the test's own thread during
      // teardown, and an interrupt dropped here resurfaces as a hang somewhere with no connection
      // to turbo.
      Thread.currentThread().interrupt()
    }
  }

  /**
   * How long [awaitConfirmationSettled] should wait on a confirmation whose own budget expires at
   * [deadlineElapsedMs], as of [nowElapsedMs]. Zero or less means the poll is far enough past its
   * budget that there is nothing left to wait for.
   *
   * Pure over its inputs so the one property that matters is testable without a device: the budget
   * has to outlast a probe that STARTED just inside the deadline, because that is the probe whose
   * answer the gate is waiting for.
   */
  internal fun confirmationJoinBudgetMs(deadlineElapsedMs: Long, nowElapsedMs: Long): Long =
    deadlineElapsedMs - nowElapsedMs + CONFIRM_SETTLE_GRACE_MS

  /**
   * Makes any confirmation still polling stop recording anything, and forgets it.
   *
   * A shard runs several trails in one process, and a trail can end while its last launch's
   * confirmation is still inside a 30s or 120s budget. Nothing about that poll belongs to the next
   * trail: left running, it can land a confirmation the new trail never earned, or report a loss
   * against it for the previous trail's launch. The epoch otherwise only moves on a launch, so a
   * trail whose deferred attach never reaches `launchApp` would inherit the stale poll outright.
   */
  fun invalidateInFlightConfirmations() {
    // Every app, not just the turbo target: a trail that ends mid-confirmation leaves nothing the
    // next one should inherit, whichever app the poll was about. Under [verdictLock] so that once
    // this returns, no poll can still be between its epoch check and its write.
    synchronized(verdictLock) { launchEpochs.values.forEach { it.incrementAndGet() } }
    pendingConfirmations.clear()
  }

  /**
   * Held while a poll checks its epoch and records its verdict, and while an epoch moves. Without
   * it a poll could pass the check, lose the CPU, and land its verdict after the next trail had
   * already reset the accounting — the exact write the epoch exists to keep out.
   */
  private val verdictLock = Any()

  /**
   * Polls for the detector until it answers or [waitMs] runs out, then records which happened.
   *
   * [epoch] is the launch this confirmation belongs to. A trail that launches twice has two of
   * these in flight, and the first one's verdict is worthless the moment the second launch
   * force-stops the app: it would either confirm a detector the run no longer has, or report a
   * loss the newer launch has already made good. So a stale confirmation records nothing.
   */
  private fun confirmUntilDeadline(appId: String, waitMs: Long, deadline: Long, epoch: Int) {
    try {
      pollUntilDeadline(appId, waitMs, deadline, epoch)
    } finally {
      // Only if it is still OURS: a newer launch of this app may have registered its own, and
      // dropping that one would leave the gate with nothing to wait for.
      pendingConfirmations.remove(appId, pendingConfirmations[appId]?.takeIf { it.thread == Thread.currentThread() })
    }
  }

  private fun pollUntilDeadline(appId: String, waitMs: Long, deadline: Long, epoch: Int) {
    val expected = "PONG $appId"
    while (SystemClock.elapsedRealtime() < deadline) {
      if (epochFor(appId).get() != epoch) return
      if (ping() == expected) {
        // Check and write under one lock: an epoch that moves between them belongs to a launch or
        // a trail this verdict knows nothing about.
        synchronized(verdictLock) {
          if (epochFor(appId).get() != epoch) return
          Console.log("[inprocess-idle-reattach] attached: $expected")
          InProcessIdleForegroundGate.noteAttached(appId)
          // The launch kept whatever promise a deferred pre-trail attach made. This is the ONLY
          // place a deferred run can be confirmed, which is why the end-of-trail check exists for
          // the trail that never gets here.
          OnDeviceTurbo.noteDetectorConfirmed(appId)
        }
        return
      }
      Thread.sleep(PONG_POLL_INTERVAL_MS)
    }
    synchronized(verdictLock) {
      if (epochFor(appId).get() != epoch) return
      InProcessIdleForegroundGate.invalidate()
      Console.log(
        "[inprocess-idle-reattach] idle detector for $appId never answered PING within ${waitMs}ms — " +
          "settle gates will fall back to the event-quiet heuristic",
      )
      // Same target scope as [requireTurboIfDemanded]: only the app turbo attached to is held to
      // the strict contract. Recorded rather than thrown — see [confirmAttachedAfterLaunch].
      if (OnDeviceTurbo.isTurboTarget(appId)) {
        OnDeviceTurbo.noteDetectorLost(
          "the idle detector never answered PING within ${waitMs}ms after its launch attach, so " +
            "the rest of this trail ran at heuristic speed",
        )
      }
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
    requireTurboIfDemanded(decision.toString(), appId)
  }

  /**
   * The same contract for a leg that has no [Decision] to name — a probe that threw, an
   * `am instrument` that threw or was rejected, a refused `am set-debug-app`. Each of those ends
   * with the app having no detector just as surely as a skip decision does, so gating strict mode
   * on the enum alone let a `turboRequired` run pass on exactly the failures most likely to happen.
   */
  private fun requireTurboIfDemanded(reason: String, appId: String) {
    if (!OnDeviceTurbo.isRequired()) return
    // Scoped to the app turbo actually attached to. This runs around EVERY launch, and a trail that
    // opens a second app, a browser or Settings has no detector staged for it and never will — only
    // one app per device can be turbo. Without this scope, strict mode would fail such a trail for
    // launching something that was never meant to be turbo.
    if (!OnDeviceTurbo.isTurboTarget(appId)) return
    throw OnDeviceTurbo.TurboRequiredButUnavailableException(
      "${TurboMessages.LOG_TAG} $appId lost turbo at a launch ($reason), so the rest of this " +
        "trail would have run at heuristic speed",
    )
  }

  /**
   * Tight connect bound: this probe runs on EVERY `launchApp`, including on runs that never opted
   * into the detector, so an unattached device must pay next to nothing here.
   */
  internal const val PING_CONNECT_TIMEOUT_MS = 250

  /**
   * Read bound on a PING once connected. A detector answers immediately, so this only ever elapses
   * for a listener that accepted the connection and then went silent — and that case still has to
   * end inside the grace below, which is why the bound is named here rather than left to the
   * default inside [InProcessIdle.ping].
   */
  internal const val PING_READ_TIMEOUT_MS = 2_000

  /** The longest a single PING can take: connect bound plus read bound. */
  internal const val PING_MAX_MS = PING_CONNECT_TIMEOUT_MS + PING_READ_TIMEOUT_MS

  /**
   * How long past its own budget a confirmation is given to finish before the end-of-trail gate
   * stops waiting on it.
   *
   * The poll checks its deadline BEFORE each PING, so the final probe starts inside the budget and
   * can still be in flight after it expires. A join bounded by the budget alone would therefore
   * return just before the verdict it exists to wait for. One whole probe ([PING_MAX_MS], connect
   * AND read) plus one poll interval covers that last probe, including a listener that connects
   * and never replies.
   */
  private const val CONFIRM_SETTLE_GRACE_MS = PING_MAX_MS + PONG_POLL_INTERVAL_MS

  private fun ping(): String? = InProcessIdle.ping(PING_CONNECT_TIMEOUT_MS, readTimeoutMs = PING_READ_TIMEOUT_MS)
}
