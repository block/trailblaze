package xyz.block.trailblaze.scripting.callback

/**
 * Process-wide holder for the Trailblaze daemon's HTTP base URL (e.g.
 * `http://localhost:52525`). Populated once at server startup — the desktop app's server-init
 * path sets this from `TrailblazePortManager.serverUrl` alongside
 * `NetworkImageLoader.currentServerBaseUrl`.
 *
 * Subprocess MCP runtime plumbing reads this when launching servers for a session so the
 * `_meta.trailblaze.baseUrl` envelope points back at the live daemon. Kept separate from
 * `NetworkImageLoader.currentServerBaseUrl` because:
 *
 *  1. This value is consumed by non-UI code (`:trailblaze-scripting-subprocess`) that doesn't
 *     pull in `:trailblaze-ui` and shouldn't have to.
 *  2. The name advertises the *contract* (callback endpoint lives on this URL) rather than a
 *     general-purpose server URL — keeps load-bearing plumbing easy to grep for.
 *
 * Null when no server is running (e.g. unit tests without a server init path). Readers must
 * handle null gracefully — the envelope injection degrades to "don't inject baseUrl" in that
 * case, and no callbacks fire.
 */
object JsScriptingCallbackBaseUrl {

  @Volatile
  private var baseUrl: String? = null

  /** Current base URL, or null if none has been set. */
  fun get(): String? = baseUrl

  /**
   * Sets the base URL for this process. Idempotent — call from the server-init path. Safe to
   * call multiple times (e.g. after a port-manager runtime override).
   */
  fun set(url: String) {
    baseUrl = url
  }

  /** Clears the holder — primarily for tests that want a clean slate. */
  fun clear() {
    baseUrl = null
  }
}
