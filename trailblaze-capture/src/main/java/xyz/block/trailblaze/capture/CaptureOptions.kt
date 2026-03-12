package xyz.block.trailblaze.capture

/**
 * Options for capture, controlled by CLI flags or desktop app settings.
 *
 * Video capture is on by default; use `--no-capture-video` on the CLI to disable.
 */
data class CaptureOptions(
  val captureVideo: Boolean = true,
  val captureLogcat: Boolean = false,
  /** Frames per second for sprite sheet extraction. */
  val spriteFrameFps: Int = DEFAULT_SPRITE_FPS,
  /** Height in pixels for each frame in the sprite sheet. Width scales proportionally. */
  val spriteFrameHeight: Int = DEFAULT_SPRITE_HEIGHT,
  /** JPEG quality for sprite sheet frames (1=worst, 31=best for ffmpeg -q:v, lower is better). */
  val spriteJpegQuality: Int = DEFAULT_SPRITE_JPEG_QUALITY,
) {
  val hasAnyCaptureEnabled: Boolean
    get() = captureVideo || captureLogcat

  companion object {
    val NONE = CaptureOptions()
    const val DEFAULT_SPRITE_FPS = 2
    const val DEFAULT_SPRITE_HEIGHT = 360
    const val DEFAULT_SPRITE_JPEG_QUALITY = 5
  }
}
