package xyz.block.trailblaze.android.rpc

import xyz.block.trailblaze.android.accessibility.InProcessIdleSettleClient
import xyz.block.trailblaze.android.accessibility.TrailblazeAccessibilityService
import xyz.block.trailblaze.util.Console

/**
 * The accessibility runner's pre-tool UI-settle gate for `RunYamlRequestHandler`'s
 * `waitForSettled` seam — the body that used to be that handler's hardcoded default, moved here
 * verbatim so the RPC server module carries no driver dependency. A no-op when the service isn't
 * bound (instrumentation mode); the in-process ANDROID_TEST driver passes its own gate instead,
 * since Espresso/Compose synchronization already settles per dispatch.
 */
object AccessibilitySettleGate {

  suspend fun waitForSettled() {
    if (TrailblazeAccessibilityService.isServiceRunning()) {
      // EXPERIMENTAL inprocess-idle race (see [InProcessIdleSettleClient]): settle on whichever answers
      // first — true main-thread idle or the standard event-quiet wait. Only ever faster.
      if (InProcessIdleSettleClient.isEnabled()) {
        val winner = InProcessIdleSettleClient.raceIdleAgainstHeuristic(5_000L) { earlyExit ->
          TrailblazeAccessibilityService.waitForSettled(earlyExit = earlyExit)
        }
        Console.log("[settle] pre-tool via $winner")
        return
      }
      TrailblazeAccessibilityService.waitForSettled()
    }
  }
}
