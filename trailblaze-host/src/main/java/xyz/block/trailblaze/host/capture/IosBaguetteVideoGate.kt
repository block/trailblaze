package xyz.block.trailblaze.host.capture

import xyz.block.trailblaze.host.isTrailblazeFlagEnabled
import xyz.block.trailblaze.host.recording.EffectiveIosBaguetteVideoConfig

/**
 * Resolves whether the **experimental** baguette-stream iOS video recorder
 * ([BaguetteIosVideoCapture]) is used for a run, resolved once per capture-session construction
 * (a persistent daemon picks up a change on the next trail, not mid-session).
 *
 * **Default off, by decision** — when neither source opts in, iOS video recording stays on
 * `xcrun simctl io recordVideo` ([xyz.block.trailblaze.capture.video.IosVideoCapture]). That is
 * the settled arrangement rather than a staging step: simctl tracks the screen to about 13 ms and
 * its stop-time transcode costs well under a second on a real session, while baguette is installed
 * on no CI image. The reasoning and measurements live in [BaguetteIosVideoCapture]'s "Why simctl
 * stays the default"; do not flip this without new evidence recorded there first.
 *
 * Two sources, env-over-config (mirrors [xyz.block.trailblaze.host.StreamScreenshotMode]):
 * - `trailblaze config ios-baguette-video true` — the discoverable, persistent toggle, read via
 *   the JVM-wide [EffectiveIosBaguetteVideoConfig] holder.
 * - `TRAILBLAZE_IOS_BAGUETTE_VIDEO=1` — env override for one-off / CI / on-device validation.
 *
 * Env values `1` / `true` (case-insensitive) enable, matching the other Trailblaze env toggles.
 * Enabling only *selects* the recorder — on a machine without baguette installed it still declines
 * per session and falls back to simctl, so a global opt-in is safe.
 */
object IosBaguetteVideoGate {
  fun enabled(): Boolean =
    fromValues(
      env = System.getenv(ENV_VAR),
      configEnabled = EffectiveIosBaguetteVideoConfig.enabled,
    )

  const val ENV_VAR: String = "TRAILBLAZE_IOS_BAGUETTE_VIDEO"

  /** Pure seam for tests — [enabled] just feeds it the real environment + config holder. */
  internal fun fromValues(env: String?, configEnabled: Boolean): Boolean =
    env.isTrailblazeFlagEnabled() || configEnabled
}
