package xyz.block.trailblaze.android.accessibility

import java.util.concurrent.atomic.AtomicReference
import xyz.block.trailblaze.inprocessidle.InProcessIdle
import xyz.block.trailblaze.util.Console

/**
 * The one place that decides whether a settle gate may race the in-process idle detector, and the
 * one place that runs that race.
 *
 * Why the decision is not just "is the sysprop on": the detector lives INSIDE one app's process
 * (see [InProcessIdleSettleClient]). It reports that app's main looper and Choreographer are
 * quiet. When a trail drives a DIFFERENT app — `launchApp` to system Settings, an OS permission
 * dialog, a share sheet — the detector's app is backgrounded and therefore trivially idle, so it
 * answers `IDLE 2` while the screen the trail is actually waiting on is still mid-transition. The
 * race treats that as a settle, cancels the event-quiet heuristic that WAS watching the real
 * foreground, and the next action runs against a half-drawn screen. The detector isn't wrong; it
 * is answering a question about the wrong process.
 *
 * So the arm only counts when the foreground app IS the app the detector lives in. Both facts are
 * cheap to establish: the detector names itself in its `PING` reply (`PONG <appId>`), cached here
 * with a short TTL so the hot path pays a socket probe at most every few seconds; and the
 * foreground package comes from the accessibility service's own window list
 * ([TrailblazeAccessibilityService.foregroundAppIdOrNull]), one root read and no tree walk.
 *
 * Either fact being UNKNOWN keeps today's behaviour — race. Only a KNOWN mismatch skips the arm
 * and settles by the heuristic alone. That direction is deliberate: an unreadable foreground or an
 * unreachable detector must not silently turn turbo off, whereas a known mismatch is exactly the
 * case where the arm's answer is about the wrong process.
 *
 * Every settle gate under turbo goes through [settleUnderTurbo] rather than calling
 * [InProcessIdleSettleClient.raceIdleAgainstHeuristic] itself, so the four gates cannot drift into
 * disagreeing about when the arm counts.
 */
object InProcessIdleForegroundGate {

  /**
   * Connect bound on the identity probe. Localhost, and the detector answers `PING` immediately or
   * not at all, so this is a bound on a wedged detector rather than a latency budget.
   */
  private const val HELPER_PING_CONNECT_TIMEOUT_MS = 250

  /**
   * Read bound on the identity probe. A detector that ACCEPTS the connection and then never
   * replies is not covered by the connect bound — `InProcessIdle.ping`'s own default would sit on
   * the read for two seconds, and this probe runs in front of BOTH settle arms, so that would be
   * two seconds added to every settle whose cached identity had expired. Bounding the read keeps
   * the whole probe under [HELPER_PING_CONNECT_TIMEOUT_MS] + this, once per
   * [HELPER_APP_ID_TTL_MS], and an expired read reads as "identity unknown" — which races, i.e.
   * exactly what this code did before the foreground was consulted at all.
   */
  private const val HELPER_PING_READ_TIMEOUT_MS = 250

  /**
   * How long a resolved detector identity is trusted before it is probed again. The identity only
   * changes when something attaches or re-attaches a detector, and both of those paths call
   * [noteAttached] / [invalidate] directly — so this is the safety net for a change that happened
   * behind our back (a host-side `am instrument`, a crash), not the primary refresh mechanism.
   * Sized so a settle-heavy trail pays a handful of probes per minute rather than one per settle.
   */
  internal const val HELPER_APP_ID_TTL_MS = 5_000L

  /**
   * How long a KNOWN identity survives probe failures before degrading to unknown.
   *
   * Downgrading known -> unknown on one missed probe is the dangerous direction: unknown races, so
   * a single 250 ms timeout on a loaded emulator would re-enable the wrong-process settle this
   * whole object exists to prevent, for a full [HELPER_APP_ID_TTL_MS]. A detector that answered
   * once is overwhelmingly likely to still be the same app, so a miss keeps the last answer.
   *
   * Bounded rather than sticky forever so the TTL's real job survives: noticing a detector that
   * changed app behind our back, where no in-process path called [invalidate]. Staying stale past
   * that is only harmful in one narrow case — the port serving a DIFFERENT app than we believe
   * while the app we believe is in the foreground — and this bounds how long that can last.
   */
  internal const val HELPER_APP_ID_STALE_GRACE_MS = 15_000L

  /**
   * One probe's worth of detector identity, published as a unit.
   *
   * A single immutable record rather than separate fields because a reader that saw a fresh
   * timestamp beside a half-written appId would trust the wrong app — the exact mistake this
   * object exists to catch. Null overall means "never probed, or explicitly invalidated".
   */
  private data class Identity(
    /** The last appId a probe actually returned, or null if none ever has. */
    val appId: String?,
    /** When [appId] was last CONFIRMED by a probe — drives [HELPER_APP_ID_STALE_GRACE_MS]. */
    val confirmedAtMs: Long,
    /** When we last probed at all — drives [HELPER_APP_ID_TTL_MS], so a down detector is not
     *  re-probed on every settle. */
    val probedAtMs: Long,
  )

  private val identity = AtomicReference<Identity?>(null)

  /**
   * The foreground app we last announced as "not the turbo app", or null when the last decision
   * was to race. Holds the once-per-switch property of the `[turbo]` lines: without it, a trail
   * driving Settings would repeat the same line twice per action.
   */
  private val announcedMismatchFor = AtomicReference<String?>(null)

  /**
   * The decision, pure over its three inputs.
   *
   * Unknown on either side means race, which is what this code did before the foreground was
   * consulted at all. Only [foregroundAppId] and [helperAppId] both being known and different
   * withholds the arm.
   */
  internal fun shouldRaceInProcessIdle(
    enabled: Boolean,
    foregroundAppId: String?,
    helperAppId: String?,
  ): Boolean = enabled &&
    (foregroundAppId == null || helperAppId == null || foregroundAppId == helperAppId)

  /**
   * The appId out of a `PING` reply, or null for anything that isn't a well-formed `PONG <appId>`.
   * Pure so the reply vocabulary is pinned without a socket: a reply this cannot read must read as
   * "identity unknown" (which races) and never as an appId that happens to mismatch.
   */
  internal fun helperAppIdFromPong(reply: String?): String? {
    if (reply == null || !reply.startsWith("PONG ")) return null
    return reply.removePrefix("PONG ").trim().takeIf { it.isNotEmpty() }
  }

  /**
   * What the `[settle] … via …` line says when the arm was withheld. The reason travels in the
   * settle line itself, not only in the once-per-switch `[turbo]` line, because a triage reading
   * one slow action's log should not have to scroll back to the switch to learn why that action
   * settled the slow way.
   */
  internal fun heuristicOnlyLabel(
    settled: Boolean,
    foregroundAppId: String,
    helperAppId: String,
  ): String {
    val why = "(foreground $foregroundAppId is not the turbo app $helperAppId)"
    return if (settled) "event-quiet heuristic $why" else "timeout (heuristic did not settle) $why"
  }

  /** A `[turbo]` line to emit, plus the mismatch state that emitting it leaves behind. */
  internal data class SwitchAnnouncement(val line: String?, val announcedMismatchFor: String?)

  /**
   * Stand-ins for "we resumed racing, but not because the turbo app came back". They occupy the
   * same slot as an announced foreground package so the once-per-switch property covers them too;
   * the angle brackets are illegal in a package name, so neither can collide with a real one.
   */
  internal const val FOREGROUND_UNREADABLE = "<foreground unreadable>"
  internal const val HELPER_UNIDENTIFIED = "<turbo app unidentified>"

  /** Emits [line] only on the transition INTO [state], so a persistent condition says it once. */
  private fun announceOnce(
    previouslyAnnouncedFor: String?,
    state: String,
    line: String,
  ): SwitchAnnouncement = if (previouslyAnnouncedFor == state) {
    SwitchAnnouncement(null, state)
  } else {
    SwitchAnnouncement(line, state)
  }

  /**
   * Once-per-switch announcement, pure over the previous state. Returns a null [line] when the
   * state is unchanged, so the caller emits on the transition only.
   *
   * The `back on` line names the detector's app when it is known, because that is the app that is
   * being raced again; it falls back to the foreground package only when the detector's identity
   * is momentarily unknown (which is itself a race decision).
   */
  internal fun announceSwitch(
    previouslyAnnouncedFor: String?,
    foregroundAppId: String?,
    helperAppId: String?,
    racing: Boolean,
  ): SwitchAnnouncement = if (!racing) {
    // Not racing can only mean a known mismatch — [shouldRaceInProcessIdle] races on any unknown.
    val foreground = foregroundAppId ?: return SwitchAnnouncement(null, previouslyAnnouncedFor)
    val helper = helperAppId ?: return SwitchAnnouncement(null, previouslyAnnouncedFor)
    if (previouslyAnnouncedFor == foreground) {
      SwitchAnnouncement(null, foreground)
    } else {
      SwitchAnnouncement(
        "[turbo] foreground $foreground is not the turbo app $helper — " +
          "settling by heuristic until it returns",
        foreground,
      )
    }
  } else if (previouslyAnnouncedFor == null) {
    SwitchAnnouncement(null, null)
  } else {
    // Three different things resume the race, and they are not interchangeable to someone
    // triaging a slow action: only one of them is the turbo app actually coming back. The other
    // two race because an UNKNOWN races, and reporting either as a return would send triage
    // looking for a foreground switch that never happened.
    when {
      foregroundAppId == null -> announceOnce(
        previouslyAnnouncedFor,
        FOREGROUND_UNREADABLE,
        "[turbo] foreground unreadable — racing the helper again",
      )

      helperAppId == null -> announceOnce(
        previouslyAnnouncedFor,
        HELPER_UNIDENTIFIED,
        "[turbo] turbo app unidentified — racing the helper again " +
          "(foreground is still $foregroundAppId)",
      )

      else -> SwitchAnnouncement(
        "[turbo] foreground back on $helperAppId — racing the helper again",
        null,
      )
    }
  }

  /**
   * Records the identity a successful attach just confirmed, so the next settle uses it without
   * waiting out [HELPER_APP_ID_TTL_MS]. Called by [InProcessIdleLaunchReattacher] when its
   * post-launch `PONG` arrives.
   */
  fun noteAttached(appId: String) {
    val nowMs = System.currentTimeMillis()
    identity.set(Identity(appId = appId, confirmedAtMs = nowMs, probedAtMs = nowMs))
  }

  /**
   * Drops the cached identity so the next settle re-probes, and defeats the stale grace — an
   * explicit "forget it" has to beat [HELPER_APP_ID_STALE_GRACE_MS], or a re-attach could not
   * clear an identity it knows is gone. Called wherever a detector may have gone away or changed
   * app — a launch that force-stopped its process, a re-attach that never confirmed.
   */
  fun invalidate() {
    identity.set(null)
  }

  /**
   * One `PING`, bounded on both connect and read. Returns the raw reply line; injectable at
   * [helperAppId] so the cache rules are testable without a socket, and raw rather than parsed so
   * a test drives the same reply vocabulary the detector actually speaks.
   */
  private fun pingHelper(): String? = InProcessIdle.ping(
    connectTimeoutMs = HELPER_PING_CONNECT_TIMEOUT_MS,
    readTimeoutMs = HELPER_PING_READ_TIMEOUT_MS,
  )

  /**
   * The detector's appId, or null when nothing has ever answered. At most one probe per
   * [HELPER_APP_ID_TTL_MS] — a device with no detector must not pay a probe per settle.
   *
   * A probe that fails does NOT erase a known identity: see
   * [HELPER_APP_ID_STALE_GRACE_MS] for why that direction is the unsafe one.
   */
  internal fun helperAppId(
    nowMs: Long = System.currentTimeMillis(),
    probe: () -> String? = ::pingHelper,
  ): String? {
    val before = identity.get()
    if (before != null && nowMs - before.probedAtMs < HELPER_APP_ID_TTL_MS) return before.appId

    val resolved = helperAppIdFromPong(probe())
    val next = if (resolved != null) {
      Identity(appId = resolved, confirmedAtMs = nowMs, probedAtMs = nowMs)
    } else {
      val lastKnown = before?.appId
      if (lastKnown != null && nowMs - before.confirmedAtMs < HELPER_APP_ID_STALE_GRACE_MS) {
        // Keep the identity, but re-stamp the probe time so an outage costs one probe per TTL
        // rather than one per settle. Logged because a silently degraded identity would otherwise
        // only be visible as an absence.
        Console.log(
          "[turbo] identity probe found nothing — keeping $lastKnown as the turbo app " +
            "(last confirmed ${nowMs - before.confirmedAtMs}ms ago)",
        )
        before.copy(probedAtMs = nowMs)
      } else {
        Identity(appId = null, confirmedAtMs = 0L, probedAtMs = nowMs)
      }
    }
    // CAS rather than set: this probe blocked for up to half a second, and an attach that
    // published a NEWER identity while it was in flight must not lose to our older answer.
    return if (identity.compareAndSet(before, next)) next.appId else identity.get()?.appId
  }

  /**
   * Whether the capture-time tree-stability probe ([TrailblazeAccessibilityService.awaitTreeStable])
   * may ask the detector at all. That gate has no heuristic lambda to hand to [settleUnderTurbo] —
   * its fallback is its own sampling loop — so it asks this instead of reading the sysprop directly,
   * and takes the same foreground decision, and the same once-per-switch announcement, as the
   * other four sites. False means "run the standard stability loop, the detector's answer would be
   * about the wrong app".
   */
  fun treeProbeArmed(): Boolean {
    if (!InProcessIdleSettleClient.isEnabled()) return false
    return decideAndAnnounce(
      foregroundAppId = TrailblazeAccessibilityService.foregroundAppIdOrNull(),
      helperAppId = helperAppId(),
    )
  }

  /**
   * Whether an idle verdict may be spent on a tree whose root reports [treeAppId].
   *
   * [treeProbeArmed] decides before the probe starts waiting, and the foreground can change while
   * it waits — a `launchApp` to Settings, an OS permission dialog. That change is precisely what
   * makes the detector answer: its app just went to the background, where it is idle instantly and
   * truthfully, about a screen that is no longer on top. Without this second look the capture would
   * accept the incoming app's tree on the outgoing app's idle signal.
   *
   * Compared against the tree's own root rather than a re-read of the foreground, so what is
   * checked is the identity of the exact tree about to be accepted, with no third window able to
   * slip between the check and the acceptance.
   *
   * Unknown on either side accepts, the same direction as [shouldRaceInProcessIdle]: an unreadable
   * package must not silently turn turbo off.
   */
  internal fun idleVerdictAppliesTo(treeAppId: String?, helperAppId: String?): Boolean =
    treeAppId == null || helperAppId == null || treeAppId == helperAppId

  /**
   * [idleVerdictAppliesTo] against the detector's current identity, which is cached — so a capture
   * that consumes a verdict pays no socket probe that [treeProbeArmed] has not already paid.
   */
  fun idleVerdictAppliesToTree(treeAppId: String?): Boolean =
    idleVerdictAppliesTo(treeAppId, helperAppId())

  private fun decideAndAnnounce(foregroundAppId: String?, helperAppId: String?): Boolean {
    val racing = shouldRaceInProcessIdle(
      enabled = true,
      foregroundAppId = foregroundAppId,
      helperAppId = helperAppId,
    )
    announceSwitch(
      previouslyAnnouncedFor = announcedMismatchFor.get(),
      foregroundAppId = foregroundAppId,
      helperAppId = helperAppId,
      racing = racing,
    ).let { announcement ->
      announcedMismatchFor.set(announcement.announcedMismatchFor)
      announcement.line?.let { Console.log(it) }
    }
    return racing
  }

  /**
   * Runs [label]'s settle under turbo and logs its `[settle]` line, racing the detector only when
   * [shouldRaceInProcessIdle] says the arm counts.
   *
   * Returns false — having done nothing at all — when turbo is off, so each caller keeps its own
   * non-turbo path exactly as it was. That shape, rather than an if/else in every gate, is what
   * keeps the four gates from drifting.
   */
  fun settleUnderTurbo(
    label: String,
    timeoutMs: Long,
    heuristic: (earlyExit: () -> Boolean) -> Boolean,
  ): Boolean {
    if (!InProcessIdleSettleClient.isEnabled()) return false
    val foregroundAppId = TrailblazeAccessibilityService.foregroundAppIdOrNull()
    val helperAppId = helperAppId()
    val racing = decideAndAnnounce(foregroundAppId, helperAppId)
    val winner = if (racing) {
      InProcessIdleSettleClient.raceIdleAgainstHeuristic(timeoutMs, heuristic = heuristic)
    } else {
      // No arm to cancel, so the heuristic gets an earlyExit that never fires — the same shape it
      // has on the non-turbo path.
      heuristicOnlyLabel(
        settled = heuristic { false },
        foregroundAppId = foregroundAppId!!,
        helperAppId = helperAppId!!,
      )
    }
    Console.log("[settle] $label via $winner")
    return true
  }
}
