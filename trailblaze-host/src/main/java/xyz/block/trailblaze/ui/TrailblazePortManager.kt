package xyz.block.trailblaze.ui

import xyz.block.trailblaze.devices.TrailblazeDevicePort
import xyz.block.trailblaze.ui.models.TrailblazeServerState.SavedTrailblazeAppConfig

/**
 * Manages HTTP/HTTPS port resolution for the Trailblaze server.
 *
 * Ports are resolved with the following precedence:
 * 1. **Runtime overrides** — transient, in-memory values set via [setRuntimeOverrides]
 *    (called by the CLI when `-p` flags or env vars are detected).
 * 2. **Persisted settings** — non-default values saved by the Settings UI (or `trailblaze config`).
 * 3. **Environment variables** — `TRAILBLAZE_PORT` / `TRAILBLAZE_HTTPS_PORT`.
 * 4. **Defaults** — 52525 (HTTP); HTTPS derives from the resolved HTTP port (+1).
 *
 * @param persistedConfigProvider Supplies the current [SavedTrailblazeAppConfig] for
 *   reading the persisted port values as a fallback.
 */
class TrailblazePortManager(
  private val persistedConfigProvider: () -> SavedTrailblazeAppConfig,
) {

  // ── Runtime-only overrides (transient, NOT persisted to disk) ──
  // Bundled into a single volatile field so both ports are read/written atomically.
  private data class PortOverrides(val httpPort: Int, val httpsPort: Int)
  @Volatile
  private var runtimePorts: PortOverrides? = null

  /**
   * The effective HTTP port for this process.
   *
   * Precedence: runtime override → persisted non-default → TRAILBLAZE_PORT env var → default.
   * This matches [resolveEffectiveHttpPort] so that the OAuth redirect_uri, server binding,
   * and CLI all agree on the port even when runtime overrides haven't been applied yet.
   */
  val httpPort: Int
    get() = runtimePorts?.httpPort
      ?: persistedConfigProvider().serverPort.takeIf { it != TrailblazeDevicePort.TRAILBLAZE_DEFAULT_HTTP_PORT }
      ?: resolveHttpPortFromEnvOrDefault()

  /**
   * The effective HTTPS port for this process.
   *
   * Precedence: runtime override → persisted non-default → TRAILBLAZE_HTTPS_PORT env var → default.
   */
  val httpsPort: Int
    get() = runtimePorts?.httpsPort
      ?: persistedConfigProvider().serverHttpsPort.takeIf { it != TrailblazeDevicePort.TRAILBLAZE_DEFAULT_HTTPS_PORT }
      ?: resolveHttpsPortFromEnvOrDefault()

  /** The effective server base URL for this process (e.g. `http://localhost:52525`). */
  val serverUrl: String
    get() = "http://localhost:$httpPort"

  /** Whether a runtime (non-persisted) override is currently active. */
  val hasRuntimeOverride: Boolean
    get() = runtimePorts != null

  /**
   * Sets transient port overrides for this process only.
   *
   * These are **not** saved to the settings file, so concurrent instances
   * each keep their own ports without interfering with each other.
   */
  fun setRuntimeOverrides(httpPort: Int, httpsPort: Int) {
    runtimePorts = PortOverrides(httpPort, httpsPort)
  }
 
  companion object {
    const val HTTP_PORT_ENV_VAR = "TRAILBLAZE_PORT"
    const val HTTPS_PORT_ENV_VAR = "TRAILBLAZE_HTTPS_PORT"
 
    fun resolveEffectiveHttpPort(
      savedConfigProvider: (() -> SavedTrailblazeAppConfig?)? = null,
    ): Int {
      val savedConfig = savedConfigProvider?.invoke()
      if (savedConfig != null &&
        savedConfig.serverPort != TrailblazeDevicePort.TRAILBLAZE_DEFAULT_HTTP_PORT
      ) {
        return savedConfig.serverPort
      }
      return resolveHttpPortFromEnvOrDefault()
    }
 
    fun resolveEffectiveHttpsPort(
      savedConfigProvider: (() -> SavedTrailblazeAppConfig?)? = null,
    ): Int {
      val savedConfig = savedConfigProvider?.invoke()
      if (savedConfig != null &&
        savedConfig.serverHttpsPort != TrailblazeDevicePort.TRAILBLAZE_DEFAULT_HTTPS_PORT
      ) {
        return savedConfig.serverHttpsPort
      }
      return resolveHttpsPortFromEnvOrDefault()
    }
 
    fun resolveHttpPortFromEnvOrDefault(): Int {
      return System.getenv(HTTP_PORT_ENV_VAR)?.toIntOrNull()
        ?: TrailblazeDevicePort.TRAILBLAZE_DEFAULT_HTTP_PORT
    }
 
    fun resolveHttpsPortFromEnvOrDefault(): Int {
      // Use the explicit HTTPS env var when present. Otherwise, derive HTTPS from the
      // resolved HTTP port (+1) so a single TRAILBLAZE_PORT override moves both ports
      // together. When neither env var is set, this derives from the default HTTP port
      // (HTTP default + 1 = HTTPS default — same value as TRAILBLAZE_DEFAULT_HTTPS_PORT).
      return System.getenv(HTTPS_PORT_ENV_VAR)?.toIntOrNull()
        ?: (resolveHttpPortFromEnvOrDefault() + 1)
    }
  }
}
