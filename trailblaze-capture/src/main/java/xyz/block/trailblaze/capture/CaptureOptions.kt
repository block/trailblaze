package xyz.block.trailblaze.capture

import xyz.block.trailblaze.util.Console

/**
 * Options for capture, controlled by CLI flags or desktop app settings.
 *
 * Video capture is on by default; use `--no-capture-video` on the CLI to disable.
 */
data class CaptureOptions(
  val captureVideo: Boolean = true,
  /**
   * Capture Android logcat (filtered to the app under test) to `device.log`. On by default
   * (only takes effect when running on Android). Disable with `--no-capture-logcat`.
   */
  val captureLogcat: Boolean = true,
  /**
   * Capture the iOS Simulator system log via `xcrun simctl spawn log stream`. On by default
   * (only takes effect when running on iOS). [xyz.block.trailblaze.capture.logcat.IosLogCapture]
   * scopes the stream to the app under test at `--level info`, so this is the logcat-equivalent
   * app log — not the system firehose. Disable with `--no-capture-ios-logs`.
   */
  val captureIosLogs: Boolean = true,
  /** Frames per second for sprite sheet extraction. */
  val spriteFrameFps: Int = DEFAULT_SPRITE_FPS,
  /** Height in pixels for each frame in the sprite sheet. Width scales proportionally. */
  val spriteFrameHeight: Int = DEFAULT_SPRITE_HEIGHT,
  /** WebP quality for sprite sheet frames (0–100, higher is better). */
  val spriteQuality: Int = DEFAULT_SPRITE_QUALITY,
) {
  val hasAnyCaptureEnabled: Boolean
    get() = captureVideo || captureLogcat || captureIosLogs

  /**
   * Sprite frame height to use for the web/Playwright timeline.
   *
   * The timeline scrubber renders these sprite frames in a large pane, so the mobile-tuned
   * [DEFAULT_SPRITE_HEIGHT] (360) gets upscaled and looks grainy. Web is captured in landscape
   * at the ~800px-tall CSS viewport, so [WEB_SPRITE_HEIGHT] (720) keeps frames near the source
   * video's native resolution. Substituted only when the user hasn't overridden
   * [spriteFrameHeight] from the default via CLI flag / desktop setting — an explicit override
   * is honored unchanged.
   */
  fun webSpriteFrameHeight(): Int =
    if (spriteFrameHeight == DEFAULT_SPRITE_HEIGHT) WEB_SPRITE_HEIGHT else spriteFrameHeight

  /**
   * WebP quality to use for web/Playwright timeline sprite frames. Substitutes
   * [WEB_SPRITE_QUALITY] (90) for the mobile-tuned [DEFAULT_SPRITE_QUALITY] (80) when the user
   * hasn't overridden [spriteQuality]; an explicit override is honored unchanged.
   */
  fun webSpriteQuality(): Int =
    if (spriteQuality == DEFAULT_SPRITE_QUALITY) WEB_SPRITE_QUALITY else spriteQuality

  companion object {
    /**
     * No capture at all — every stream off. Explicit (not `CaptureOptions()`) because the
     * constructor now defaults video/logcat/iOS-logs ON, so `CaptureOptions()` is the opposite
     * of "none". Used as `CaptureStream.stop`'s default arg, where only sprite tuning is read.
     */
    val NONE = CaptureOptions(captureVideo = false, captureLogcat = false, captureIosLogs = false)
    const val DEFAULT_SPRITE_FPS = 2
    const val DEFAULT_SPRITE_HEIGHT = 360
    const val DEFAULT_SPRITE_QUALITY = 80

    /**
     * Web/desktop timeline sprite tuning. The recorded web video is ~800px tall (the CSS
     * viewport), so 720 stays near native — going higher would just upscale the source.
     */
    const val WEB_SPRITE_HEIGHT = 720
    const val WEB_SPRITE_QUALITY = 90

    /**
     * Host-session sprite tuning (the values [hostCaptureOptions] callers used to hardcode).
     * Height differs from [DEFAULT_SPRITE_HEIGHT]: host sessions record device-resolution video,
     * so 720 keeps the timeline scrubber sharp where 360 looks grainy.
     */
    const val HOST_SPRITE_FPS = 2
    const val HOST_SPRITE_HEIGHT = 720
    const val HOST_SPRITE_QUALITY = 80

    const val ENV_SPRITE_FPS = "TRAILBLAZE_SPRITE_FPS"
    const val ENV_SPRITE_FRAME_HEIGHT = "TRAILBLAZE_SPRITE_FRAME_HEIGHT"
    const val ENV_SPRITE_QUALITY = "TRAILBLAZE_SPRITE_QUALITY"

    /**
     * Capture options for host-driven sessions, with sprite tuning overridable via environment
     * variables ([ENV_SPRITE_FPS] / [ENV_SPRITE_FRAME_HEIGHT] / [ENV_SPRITE_QUALITY]) so CI
     * pipelines can trade sprite size against playback fidelity without a release. Absent or
     * invalid values fall back to the host defaults (2 fps · 720 px · quality 80) — a bad env
     * var must never take down video capture.
     */
    fun hostCaptureOptions(
      captureVideo: Boolean = true,
      captureLogcat: Boolean = true,
      captureIosLogs: Boolean = true,
      env: (String) -> String? = System::getenv,
    ): CaptureOptions = CaptureOptions(
      captureVideo = captureVideo,
      captureLogcat = captureLogcat,
      captureIosLogs = captureIosLogs,
      spriteFrameFps = spriteEnvInt(env, ENV_SPRITE_FPS, HOST_SPRITE_FPS, 1..60),
      spriteFrameHeight = spriteEnvInt(env, ENV_SPRITE_FRAME_HEIGHT, HOST_SPRITE_HEIGHT, 16..16383),
      spriteQuality = spriteEnvInt(env, ENV_SPRITE_QUALITY, HOST_SPRITE_QUALITY, 1..100),
    )

    private fun spriteEnvInt(env: (String) -> String?, name: String, default: Int, valid: IntRange): Int {
      val raw = env(name)?.trim()?.takeIf { it.isNotEmpty() } ?: return default
      val value = raw.toIntOrNull()
      if (value == null || value !in valid) {
        Console.log("$name='$raw' is not an integer in $valid — using default $default")
        return default
      }
      return value
    }
  }
}
