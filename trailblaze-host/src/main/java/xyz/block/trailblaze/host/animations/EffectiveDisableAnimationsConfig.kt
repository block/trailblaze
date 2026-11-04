package xyz.block.trailblaze.host.animations

/**
 * JVM-wide effective value of the persisted `disable-animations` config toggle, mirroring
 * [xyz.block.trailblaze.host.recording.EffectiveStreamScreenshotConfig]: the daemon's
 * `TrailblazeSettingsRepo` collector and the standalone-CLI `CliConfigHelper.readConfig()` both
 * push the user's saved preference here, and [DisableAnimationsGate] reads it (env-over-config)
 * when a session starts.
 *
 * A JVM-wide holder (rather than threading the config through the session wiring) is used for the
 * same reason as the stream-screenshot config: sessions start under the daemon and in standalone
 * `--no-daemon` CLI runs, and neither has the `SavedTrailblazeAppConfig` in hand where the session
 * device setup runs.
 */
object EffectiveDisableAnimationsConfig {
  /** Whether the persisted config opts sessions into OS-animation disabling. */
  @Volatile
  var enabled: Boolean = false

  /**
   * Whether the user explicitly said `false`, as opposed to never setting it. Only that answer
   * turns off a driver that disables animations by default ([DisableAnimationsGate.enabled]).
   */
  @Volatile
  var explicitlyOff: Boolean = false

  /** Pushes the persisted tri-state into both fields. */
  fun set(persisted: Boolean?) {
    enabled = persisted == true
    explicitlyOff = persisted == false
  }

  /** Test-only reset so a suite that mutates the singleton can restore it in `@After`. */
  fun clearForTests() {
    enabled = false
    explicitlyOff = false
  }
}
