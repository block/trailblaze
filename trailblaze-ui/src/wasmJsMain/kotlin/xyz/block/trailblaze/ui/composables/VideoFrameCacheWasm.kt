package xyz.block.trailblaze.ui.composables

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image
import xyz.block.trailblaze.ui.resolveScreenshot
import xyz.block.trailblaze.util.Console
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * FPS used by WasmReport to extract video frames during report generation.
 * Must match the value in WasmReport.WASM_VIDEO_FPS.
 */
private const val WASM_EMBEDDED_FPS = 2

actual fun createVideoFrameCache(videoPath: String, fps: Int): VideoFrameCache =
  WasmEmbeddedVideoFrameCache(sessionId = videoPath)

/**
 * WASM video frame cache that lazily loads pre-extracted frames from the embedded report data.
 *
 * Frames are **not** loaded eagerly — they are decompressed on demand via [resolveScreenshot]
 * when [getFrameAsync] is called, following the same lazy decompression pattern used for session
 * logs and other per-session data. Decoded frames are cached in memory for subsequent access.
 *
 * Frames are stored in the compressed images map with keys like "{sessionId}/vf_000001.jpg"
 * and the FPS is fixed to [WASM_EMBEDDED_FPS] to match the extraction rate used during report
 * generation.
 */
@OptIn(ExperimentalEncodingApi::class)
private class WasmEmbeddedVideoFrameCache(
  private val sessionId: String,
) : VideoFrameCache {
  private val fps: Int = WASM_EMBEDDED_FPS
  private val frames = mutableMapOf<Int, ImageBitmap>()

  override fun getFrame(timestampMs: Long): ImageBitmap? {
    val frameIndex = (timestampMs * fps / 1000 + 1).toInt().coerceAtLeast(1)
    return frames[frameIndex]
  }

  override suspend fun getFrameAsync(timestampMs: Long): ImageBitmap? {
    val frameIndex = (timestampMs * fps / 1000 + 1).toInt().coerceAtLeast(1)
    frames[frameIndex]?.let { return it }

    // Try to decompress from embedded data
    val paddedIndex = frameIndex.toString().padStart(6, '0')
    val imageKey = "$sessionId/vf_$paddedIndex.jpg"
    val dataUrl = try {
      resolveScreenshot(imageKey)
    } catch (e: Exception) {
      Console.log("Failed to resolve video frame: $imageKey - ${e.message}")
      null
    } ?: return null

    val bitmap = dataUrlToImageBitmap(dataUrl)
    if (bitmap != null) {
      frames[frameIndex] = bitmap
    }
    return bitmap
  }

  override fun isExtractionComplete(): Boolean = true

  override fun dispose() {
    frames.clear()
  }

  /**
   * Converts a data URL (data:image/jpeg;base64,...) to an ImageBitmap.
   */
  private fun dataUrlToImageBitmap(dataUrl: String): ImageBitmap? {
    return try {
      val base64Data = dataUrl.substringAfter(";base64,", "")
      if (base64Data.isEmpty()) return null
      val bytes = Base64.decode(base64Data)
      Image.makeFromEncoded(bytes).toComposeImageBitmap()
    } catch (e: Exception) {
      Console.log("Failed to decode video frame image: ${e.message}")
      null
    }
  }
}
