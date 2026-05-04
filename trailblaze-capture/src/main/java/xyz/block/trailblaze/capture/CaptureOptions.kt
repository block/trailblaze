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

  companion object {
    val NONE = CaptureOptions()
    const val DEFAULT_SPRITE_FPS = 2
    const val DEFAULT_SPRITE_HEIGHT = 360
    const val DEFAULT_SPRITE_QUALITY = 80
  }
}
