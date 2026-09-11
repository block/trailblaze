package xyz.block.trailblaze.ui.tabs.settings

import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType

/**
 * Whether Settings offers the "run the Android agent on the host" switch.
 *
 * A pure function rather than a condition inline in the composable so it can be exercised without
 * Compose — the question is about driver capabilities, and getting it wrong shows the user a
 * control that changes nothing.
 */
object HostAgentToggleVisibility {

  /**
   * True only when the switch's value can actually change where the agent runs.
   *
   * Requires a [selectedDriver] that [TrailblazeDriverType.hostAgentDispatchable] admits:
   * `DesktopDispatchDecision` consults `preferHostAgent` only for such a driver, so offering the
   * switch for any other one writes a setting that dispatch then ignores. `ANDROID_TEST` is the
   * case that matters — it runs the trail inside the app's own instrumentation test, which is
   * on-device by construction.
   *
   * Android-only because the switch is about the on-device RPC server, which is the Android
   * transport; no iOS or web driver has one.
   */
  fun shouldShow(platform: TrailblazeDevicePlatform, selectedDriver: TrailblazeDriverType?): Boolean =
    platform == TrailblazeDevicePlatform.ANDROID &&
      selectedDriver != null &&
      selectedDriver.hostAgentDispatchable
}
