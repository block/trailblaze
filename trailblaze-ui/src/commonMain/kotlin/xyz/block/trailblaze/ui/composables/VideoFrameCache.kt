package xyz.block.trailblaze.ui.composables

import androidx.compose.ui.graphics.ImageBitmap

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
 * On JVM: runs a single background ffmpeg process to extract JPEG frames at [fps] into a temp dir.
 * On WASM: returns a no-op stub.
 */
expect fun createVideoFrameCache(videoPath: String, fps: Int): VideoFrameCache
