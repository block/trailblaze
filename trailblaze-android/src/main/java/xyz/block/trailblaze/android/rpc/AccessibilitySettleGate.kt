package xyz.block.trailblaze.android.rpc

import xyz.block.trailblaze.android.accessibility.InProcessIdleForegroundGate
import xyz.block.trailblaze.android.accessibility.TrailblazeAccessibilityService

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
      // EXPERIMENTAL inprocess-idle race (see [InProcessIdleForegroundGate]): settle on whichever
      // answers first — true main-thread idle or the standard event-quiet wait — but only while
      // the detector's own app is in front. Only ever faster; never faster on someone else's app.
      val handled = InProcessIdleForegroundGate.settleUnderTurbo("pre-tool", 5_000L) { earlyExit ->
        TrailblazeAccessibilityService.waitForSettled(earlyExit = earlyExit)
      }
      if (handled) return
      TrailblazeAccessibilityService.waitForSettled()
    }
  }
}
