package xyz.block.trailblaze.ui

import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.model.DesktopAppRunYamlParams

/**
 * Interface for tracking analytics events in Trailblaze.
 * Implementations can send events to various analytics backends.
 */
interface TrailblazeAnalytics {

  /**
   * Tracks when a test run is initiated.
   *
   * @param params The parameters for the YAML test run, containing device, app, and config info.
   */
  fun runTest(trailblazeDriverType: TrailblazeDriverType, params: DesktopAppRunYamlParams)

  fun appLaunch()

  /**
   * No-op implementation for when analytics tracking is not needed.
   */
  object NoOp : TrailblazeAnalytics {
    override fun runTest(
      trailblazeDriverType: TrailblazeDriverType,
      params: DesktopAppRunYamlParams
    ) {
      // No-op
    }

    override fun appLaunch() {
      // No-op
    }
  }
}
