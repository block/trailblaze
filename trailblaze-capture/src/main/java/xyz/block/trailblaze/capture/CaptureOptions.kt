package xyz.block.trailblaze.capture

/**
 * Options for capture, controlled by CLI flags or desktop app settings.
 *
 * Video capture is on by default; use `--no-capture-video` on the CLI to disable.
 */
data class CaptureOptions(
  val captureVideo: Boolean = true,
  /** Capture Android logcat (only takes effect when running on Android). */
  val captureLogcat: Boolean = false,
  /**
   * Capture iOS Simulator system logs via `xcrun simctl spawn log stream`. Off by default —
   * iOS logs are firehose-volume and can fill local disks fast.
   */
  val captureIosLogs: Boolean = false,
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
    val NONE = CaptureOptions()
    const val DEFAULT_SPRITE_FPS = 2
    const val DEFAULT_SPRITE_HEIGHT = 360
    const val DEFAULT_SPRITE_QUALITY = 80

    /**
     * Web/desktop timeline sprite tuning. The recorded web video is ~800px tall (the CSS
     * viewport), so 720 stays near native — going higher would just upscale the source.
     */
    const val WEB_SPRITE_HEIGHT = 720
    const val WEB_SPRITE_QUALITY = 90
  }
}
