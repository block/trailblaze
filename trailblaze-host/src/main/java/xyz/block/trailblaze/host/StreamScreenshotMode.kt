package xyz.block.trailblaze.host

import xyz.block.trailblaze.host.recording.EffectiveStreamScreenshotConfig

/**
 * Experimental screenshot-source selection for the host-side agent loop, resolved once per
 * runner/agent construction (i.e. per run — a persistent daemon picks up changes on the next
 * trail, not mid-session). Shared by every host-driven agent-loop path: Android
 * ([resolve] — `HostOnDeviceRpcTrailblazeAgent`, screenrecord tee), iOS ([resolveIos] —
 * `MaestroHostRunnerImpl`, baguette WebSocket), and web ([resolveWeb] —
 * `WebStreamScreenshotSupport`, CDP screencast).
 *
 * Each platform resolves from two sources, env-over-config:
 * - `trailblaze config stream-screenshots true` — the discoverable, persistent toggle, shared by
 *   all three platforms (one engine, one switch). Serves LLM-loop screenshots from the stream
 *   (equivalent to [STREAM]). Read via the JVM-wide [EffectiveStreamScreenshotConfig] holder.
 *   Platforms whose feed isn't available (no baguette, no screencast) decline per capture and
 *   fall back to direct screenshots, so a global opt-in is safe everywhere.
 * - `TRAILBLAZE_ANDROID_STREAM_SCREENSHOT=1` / `TRAILBLAZE_IOS_STREAM_SCREENSHOT=1` /
 *   `TRAILBLAZE_WEB_STREAM_SCREENSHOT=1` — per-platform env override (one-off / CI). Also
 *   selects [STREAM]; redundant with the config toggle when both are on.
 *
 * Each platform's `..._AB=1` variant is A/B validation mode ([AB_COMPARE]): the direct
 * screenshot stays authoritative but the stream matcher also runs on every capture and logs a
 * `[stream-screenshot] AB …` line (match/mismatch, clock skew, payload sizes). Env-only — it's a
 * validation tool, not a persistent user setting — and takes precedence over both STREAM sources
 * so setting it always compares rather than switches.
 *
 * [STREAM] serves screenshots from the device's live stream instead of the per-capture direct
 * screenshot; the tree still comes from the platform's own capture path. Stream frames use the
 * feed's capture resolution and JPEG quality — the `trailblaze config screenshot-*`
 * scaling/format settings apply only to the warm-up and fallback captures.
 *
 * Env values `1` / `true` (case-insensitive) enable, matching the other Trailblaze env toggles.
 */
internal enum class StreamScreenshotMode {
  OFF,
  STREAM,
  AB_COMPARE,
  ;

  companion object {
    fun resolve(): StreamScreenshotMode = fromValues(
      stream = System.getenv("TRAILBLAZE_ANDROID_STREAM_SCREENSHOT"),
      abCompare = System.getenv("TRAILBLAZE_ANDROID_STREAM_SCREENSHOT_AB"),
      configEnabled = EffectiveStreamScreenshotConfig.enabled,
    )

    fun resolveIos(): StreamScreenshotMode = fromValues(
      stream = System.getenv("TRAILBLAZE_IOS_STREAM_SCREENSHOT"),
      abCompare = System.getenv("TRAILBLAZE_IOS_STREAM_SCREENSHOT_AB"),
      configEnabled = EffectiveStreamScreenshotConfig.enabled,
    )

    fun resolveWeb(): StreamScreenshotMode = fromValues(
      stream = System.getenv("TRAILBLAZE_WEB_STREAM_SCREENSHOT"),
      abCompare = System.getenv("TRAILBLAZE_WEB_STREAM_SCREENSHOT_AB"),
      configEnabled = EffectiveStreamScreenshotConfig.enabled,
    )

    /** Pure seam for tests — the resolvers just feed it the real environment + config holder. */
    internal fun fromValues(
      stream: String?,
      abCompare: String?,
      configEnabled: Boolean,
    ): StreamScreenshotMode = when {
      abCompare.isTrailblazeFlagEnabled() -> AB_COMPARE
      stream.isTrailblazeFlagEnabled() || configEnabled -> STREAM
      else -> OFF
    }
  }
}
