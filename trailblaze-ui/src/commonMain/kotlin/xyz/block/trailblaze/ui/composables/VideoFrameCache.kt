package xyz.block.trailblaze.ui.composables

import androidx.compose.ui.graphics.ImageBitmap
import xyz.block.trailblaze.ui.tabs.session.SpriteSheetInfo

/**
 * Pre-extracts video frames at a fixed fps for fast timeline scrubbing and playback.
 *
 * Call [getFrame] to retrieve the frame closest to a given timestamp. Returns null if the frame
 * hasn't been extracted yet. Call [dispose] to clean up temp files when done.
 */
interface VideoFrameCache {
  /** Returns the frame closest to [timestampMs], or null if not yet available. */
  fun getFrame(timestampMs: Long): ImageBitmap?

  /**
   * Async version of [getFrame] that can perform I/O to load frames on demand.
   * Default implementation delegates to [getFrame].
   */
  suspend fun getFrameAsync(timestampMs: Long): ImageBitmap? = getFrame(timestampMs)

  /** True once background extraction has finished. */
  fun isExtractionComplete(): Boolean

  /** Cleans up temp files and stops any running extraction. */
  fun dispose()
}

/**
 * Creates a platform-specific [VideoFrameCache].
 *
 * On JVM: loads a sprite sheet image and crops individual frames on demand.
 * On WASM: lazily loads pre-extracted frames from embedded report data.
 *
 * @param spriteInfo Pre-parsed sprite sheet metadata. On JVM this avoids re-reading the companion
 *   metadata file from disk. Ignored on WASM.
 */
expect fun createVideoFrameCache(
  videoPath: String,
  fps: Int,
  spriteInfo: SpriteSheetInfo? = null,
): VideoFrameCache
