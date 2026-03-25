package xyz.block.trailblaze.model

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.logs.model.SessionId

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
 * @property browserHeadless If true, the Playwright browser runs headless (no visible window);
 *                           if false, the browser window is shown on screen.
 * @property useRevylNativeSteps If true, Revyl device prompt steps use
 *                               `revyl device instruction` / `validation` instead of the
 *                               LLM agent pipeline. Defaults to false (LLM pipeline) until
 *                               native step reporting with screenshots is implemented.
 *                               Only affects REVYL_ANDROID/REVYL_IOS drivers.
 */
@Serializable
data class TrailblazeConfig(
  val setOfMarkEnabled: Boolean,
  val sendSessionStartLog: Boolean = true,
  val sendSessionEndLog: Boolean = true,
  /** Provide a non-null session ID to override the default session ID generation. */
  val overrideSessionId: SessionId? = null,
  val aiFallback: Boolean = AI_FALLBACK_DEFAULT,
  val browserHeadless: Boolean = true,
  val useRevylNativeSteps: Boolean = false,
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
