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
 * @property aiFallback If true, allows AI fallback when recorded steps fail;
 *                      if false, disables AI fallback (useful for debugging recorded steps).
 * @property browserHeadless If true, the Playwright browser runs headless (no visible window);
 *                           if false, the browser window is shown on screen.
 */
@Serializable
data class TrailblazeConfig(
  val sendSessionStartLog: Boolean = true,
  val sendSessionEndLog: Boolean = true,
  /** Provide a non-null session ID to override the default session ID generation. */
  val overrideSessionId: SessionId? = null,
  val aiFallback: Boolean = AI_FALLBACK_DEFAULT,
  val browserHeadless: Boolean = true,
  val nodeSelectorMode: NodeSelectorMode = NodeSelectorMode.DEFAULT,
  /**
   * When true and using an on-device driver (accessibility or instrumentation), the agent
   * loop runs on the host with individual tool calls dispatched to the device via RPC.
   * When false, the entire agent loop runs on the device.
   *
   * Only applies when running from a host (desktop app / MCP). On device farms where there
   * is no host, the agent always runs on-device regardless of this setting.
   */
  val preferHostAgent: Boolean = true,
) {
  companion object {
    val DEFAULT = TrailblazeConfig()
  }
}
