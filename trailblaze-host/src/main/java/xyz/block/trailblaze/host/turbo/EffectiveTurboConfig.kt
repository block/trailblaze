package xyz.block.trailblaze.host.turbo

/**
 * JVM-wide effective value of the persisted `turbo` config toggle, mirroring
 * [xyz.block.trailblaze.host.animations.EffectiveDisableAnimationsConfig]: the daemon's
 * `TrailblazeSettingsRepo` collector and the standalone-CLI `CliConfigHelper.readConfig()` both
 * push the user's saved preference here, and [TurboGate] reads it (env-over-config) when a
 * session starts.
 *
 * A JVM-wide holder rather than threading the config through the session wiring, for the same
 * reason as its neighbors: sessions start under the daemon AND in standalone `--no-daemon` CLI
 * runs, and neither has the saved config in hand where session device setup runs.
 */
object EffectiveTurboConfig {
  /** Whether the persisted config opts sessions into turbo mode. */
  @Volatile
  var enabled: Boolean = false

  /** Test-only reset so a suite that mutates the singleton can restore it in `@After`. */
  fun clearForTests() {
    enabled = false
  }
}
