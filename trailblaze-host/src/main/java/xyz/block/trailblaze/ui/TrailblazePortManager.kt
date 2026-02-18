package xyz.block.trailblaze.ui

import xyz.block.trailblaze.ui.models.TrailblazeServerState.SavedTrailblazeAppConfig

/**
 * Manages HTTP/HTTPS port resolution for the Trailblaze server.
 *
 * Ports are resolved with the following precedence:
 * 1. **Runtime overrides** — transient, in-memory values set by CLI flags (`-p`) or
 *    environment variables (`TRAILBLAZE_PORT`). These are never persisted, so multiple
 *    concurrent instances can each run on a different port without racing on the shared
 *    settings file.
 * 2. **Persisted settings** — values saved by the Settings UI (or `trailblaze config`).
 *
 * @param persistedConfigProvider Supplies the current [SavedTrailblazeAppConfig] for
 *   reading the persisted port values as a fallback.
 */
class TrailblazePortManager(
  private val persistedConfigProvider: () -> SavedTrailblazeAppConfig,
) {

  // ── Runtime-only overrides (transient, NOT persisted to disk) ──
  @Volatile
  private var runtimeHttpPort: Int? = null
  @Volatile
  private var runtimeHttpsPort: Int? = null

  /** The effective HTTP port for this process. */
  val httpPort: Int
    get() = runtimeHttpPort ?: persistedConfigProvider().serverPort

  /** The effective HTTPS port for this process. */
  val httpsPort: Int
    get() = runtimeHttpsPort ?: persistedConfigProvider().serverHttpsPort

  /** The effective server base URL for this process (e.g. `http://localhost:52525`). */
  val serverUrl: String
    get() = "http://localhost:$httpPort"

  /** Whether a runtime (non-persisted) override is currently active. */
  val hasRuntimeOverride: Boolean
    get() = runtimeHttpPort != null || runtimeHttpsPort != null

  /**
   * Sets transient port overrides for this process only.
   *
   * These are **not** saved to the settings file, so concurrent instances
   * each keep their own ports without interfering with each other.
   */
  fun setRuntimeOverrides(httpPort: Int, httpsPort: Int) {
    runtimeHttpPort = httpPort
    runtimeHttpsPort = httpsPort
  }
}
