package xyz.block.trailblaze.model

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.logs.model.SessionId

/**
 * Default value for whether self-heal is enabled.
 *
 * Self-heal recovers via LLM calls when a recorded tool sequence fails, and can update the
 * recording so the test heals itself. Keeping it disabled by default makes failures more
 * actionable and ensures recordings are validated unless explicitly opted-in.
 */
const val SELF_HEAL_DEFAULT: Boolean = false


/**
 * Configuration class for Trailblaze test execution parameters.
 * This class encapsulates various settings that affect how Trailblaze runs tests,
 * making it easier to add new configuration parameters without modifying method signatures
 * throughout the codebase.
 *
 * @property selfHeal If true, allows self-heal (AI takes over) when recorded steps fail;
 *                    if false, disables self-heal (useful for debugging recorded steps).
 * @property browserHeadless If true, the Playwright browser runs headless (no visible window);
 *                           if false, the browser window is shown on screen.
 */
@Serializable
data class TrailblazeConfig(
  val sendSessionStartLog: Boolean = true,
  val sendSessionEndLog: Boolean = true,
  /** Provide a non-null session ID to override the default session ID generation. */
  val overrideSessionId: SessionId? = null,
  val selfHeal: Boolean = SELF_HEAL_DEFAULT,
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
  /**
   * When true, supported sessions auto-start the framework network capture engine for
   * the duration of the run — `<session-dir>/network.ndjson` is populated without any
   * per-trail capture-start call. Currently honored by Playwright web + Electron;
   * on-device mobile engines plug into the same flag. Mirrors
   * the desktop-app `captureLogcat` pattern. Off by default; flip via the desktop
   * settings toggle, the `--capture-network` CLI flag, or set on this config directly.
   */
  val captureNetworkTraffic: Boolean = false,
) {
  companion object {
    val DEFAULT = TrailblazeConfig()
  }
}
