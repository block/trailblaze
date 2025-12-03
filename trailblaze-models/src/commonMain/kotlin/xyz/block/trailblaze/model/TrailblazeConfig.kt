package xyz.block.trailblaze.model

import kotlinx.serialization.Serializable

/**
 * Configuration class for Trailblaze test execution parameters.
 * This class encapsulates various settings that affect how Trailblaze runs tests,
 * making it easier to add new configuration parameters without modifying method signatures
 * throughout the codebase.
 *
 * Configuration must be explicitly created at the entry point of your test or application
 * and passed through the system, ensuring clarity about what configuration is being used.
 *
 * @property setOfMarkEnabled If true, uses Set of Mark tools for UI interaction;
 *                            if false, uses Device Control tools.
 */
@Serializable
data class TrailblazeConfig(
  val setOfMarkEnabled: Boolean,
) {
  companion object {
    /**
     * Default configuration with Set of Mark enabled.
     * Use this for most UI testing scenarios.
     */
    val DEFAULT = TrailblazeConfig(setOfMarkEnabled = true)

    /**
     * Configuration with Set of Mark disabled (uses Device Control instead).
     * Use this for web testing or scenarios where Set of Mark is not suitable.
     */
    val DEVICE_CONTROL = TrailblazeConfig(setOfMarkEnabled = false)
  }
}
