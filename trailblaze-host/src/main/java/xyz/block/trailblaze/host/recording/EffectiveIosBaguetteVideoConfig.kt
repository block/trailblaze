package xyz.block.trailblaze.host.recording

/**
 * JVM-wide effective value of the persisted `ios-baguette-video` config toggle, mirroring
 * [EffectiveStreamScreenshotConfig]: the daemon's `TrailblazeSettingsRepo` collector and the
 * standalone-CLI `CliConfigHelper.readConfig()` both push the user's saved preference here, and the
 * capture wiring reads it (env-over-config) when deciding the iOS recorder — see
 * [xyz.block.trailblaze.host.capture.IosBaguetteVideoGate].
 *
 * A JVM-wide holder (rather than threading the config through the capture coordinator) is used for
 * the same reason as the stream-screenshot config: the capture path runs under the daemon and in
 * standalone `--no-daemon` CLI runs, and neither has the `SavedTrailblazeAppConfig` in hand where
 * the recorder is chosen.
 */
object EffectiveIosBaguetteVideoConfig {
  /** Whether the persisted config opts iOS runs into baguette-stream video recording. */
  @Volatile
  var enabled: Boolean = false

  /** Test-only reset so a suite that mutates the singleton can restore it in `@After`. */
  fun clearForTests() {
    enabled = false
  }
}
