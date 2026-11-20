package xyz.block.trailblaze.android.accessibility

import kotlinx.datetime.Clock
import xyz.block.trailblaze.toolcalls.commands.SettledScreen

/**
 * The accessibility driver's answer to [SettledScreen.appIdleProbe]: is the app under test idle?
 *
 * With the in-process idle detector attached and its app in front, the app answers for itself. Otherwise idle means no
 * UI accessibility event for the quiet window ([TrailblazeAccessibilityService.lastUiEventTimestampMs]).
 *
 * Installed by [AccessibilityDeviceManager], which is what runs a selector query on the device in
 * both modes: a host-driven run sends `findSelectorMatches` to the on-device runner, and a farm run
 * executes it there directly. So one install covers both.
 */
internal object AccessibilityAppIdleProbe {

  /** How long one in-process idle check may hold a poll. A busy app answers TIMEOUT: not idle. */
  private const val IN_PROCESS_IDLE_PROBE_MS = 200L

  fun install() {
    SettledScreen.appIdleProbe = ::isAppIdle
  }

  fun isAppIdle(quietWindowMs: Long): Boolean {
    // No service, no events: "quiet" would be vacuous, so never call that idle.
    if (!TrailblazeAccessibilityService.isServiceRunning()) return false
    // The detector speaks for the app it lives in. When a trail is on Settings or another app, that
    // app is backgrounded and trivially idle, so its answer only counts while it is in front, both
    // before the probe and after it returns. Otherwise fall back to event quiet.
    if (InProcessIdleForegroundGate.treeProbeArmed()) {
      val reply = InProcessIdleSettleClient.awaitIdle(IN_PROCESS_IDLE_PROBE_MS)
      if (reply != null &&
        InProcessIdleForegroundGate.idleVerdictAppliesToTree(TrailblazeAccessibilityService.foregroundAppIdOrNull())
      ) {
        return isIdleReply(reply)
      }
    }
    return eventQuiet(
      nowMs = Clock.System.now().toEpochMilliseconds(),
      lastUiEventMs = TrailblazeAccessibilityService.lastUiEventTimestampMs,
      quietWindowMs = quietWindowMs,
    )
  }

  internal fun isIdleReply(reply: String): Boolean = reply.startsWith("IDLE")

  internal fun eventQuiet(nowMs: Long, lastUiEventMs: Long, quietWindowMs: Long): Boolean =
    nowMs - lastUiEventMs >= quietWindowMs
}
