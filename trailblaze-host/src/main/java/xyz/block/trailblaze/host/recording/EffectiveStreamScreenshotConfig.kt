package xyz.block.trailblaze.host.recording

/**
 * JVM-wide effective value of the persisted `stream-screenshots` config toggle, mirroring
 * the [xyz.block.trailblaze.api.EffectiveScreenshotScalingConfig] pattern: the daemon's
 * `TrailblazeSettingsRepo` collector and the standalone-CLI `CliConfigHelper.readConfig()` both
 * push the user's saved preference here, and each platform's runner/agent reads it once at
 * construction (see [xyz.block.trailblaze.host.StreamScreenshotMode]).
 *
 * A JVM-wide holder (rather than threading the config into the agent constructor) is used because
 * the host agent runs both under the daemon and in standalone `--no-daemon` CLI runs, and neither
 * path has the `SavedTrailblazeAppConfig` in hand at agent-construction time — the same reason the
 * screenshot scaling config uses this seam.
 */
object EffectiveStreamScreenshotConfig {
  /** Whether the persisted config opts runs into stream-sourced screenshots (all platforms). */
  @Volatile
  var enabled: Boolean = false

  /** Test-only reset so a suite that mutates the singleton can restore it in `@After`. */
  fun clearForTests() {
    enabled = false
  }
}
