package xyz.block.trailblaze.model

import kotlinx.serialization.Serializable

/**
 * Default value for whether AI fallback is enabled.
 *
 * AI fallback can mask issues with recordings by recovering via LLM calls when a recorded tool
 * sequence fails. Keeping it disabled by default makes failures more actionable and ensures
 * recordings are validated unless explicitly opted-in.
 */
const val AI_FALLBACK_DEFAULT: Boolean = false

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
 * @property aiFallback If true, allows AI fallback when recorded steps fail;
 *                      if false, disables AI fallback (useful for debugging recorded steps).
 */
@Serializable
data class TrailblazeConfig(
  val setOfMarkEnabled: Boolean,
  val aiFallback: Boolean = AI_FALLBACK_DEFAULT,
) {
  companion object {
    /**
     * Default configuration with Set of Mark enabled and AI fallback disabled.
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
