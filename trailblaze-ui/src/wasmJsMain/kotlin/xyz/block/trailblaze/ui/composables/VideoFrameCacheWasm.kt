package xyz.block.trailblaze.ui.composables

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jetbrains.skia.Image
import xyz.block.trailblaze.ui.decompressImageCallback
import xyz.block.trailblaze.ui.tabs.session.SpriteSheetInfo
import xyz.block.trailblaze.util.Console
import kotlin.coroutines.resume
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * FPS used by WasmReport to extract video frames during report generation.
 * Must match the value in WasmReport.WASM_VIDEO_FPS.
 */
private const val WASM_EMBEDDED_FPS = 2

actual fun createVideoFrameCache(
  videoPath: String,
  fps: Int,
  spriteInfo: SpriteSheetInfo?,
): VideoFrameCache = WasmEmbeddedVideoFrameCache(sessionId = videoPath)

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
 *
 * **Sparse / truncated sprite sheets.** When the embedded sprite sheet covers a shorter window
 * than the session timeline (e.g. a broken Android `screenrecord` chain produced only a 2-second
 * MP4 from an 87-second test, so only `vf_000001..vf_000004` exist for a timeline that scrubs to
 * ~175), naive lookup would return null for the vast majority of timestamps and the UI would sit
 * on its "Loading frame…" placeholder. To make the timeline honest, [getFrameAsync] falls back
 * to the nearest preceding existing frame on miss. The highest known-existing index is cached
 * after the first walk-back so subsequent high-index requests are O(1).
 */
@OptIn(ExperimentalEncodingApi::class)
private class WasmEmbeddedVideoFrameCache(
  private val sessionId: String,
) : VideoFrameCache {
  private val fps: Int = WASM_EMBEDDED_FPS
  private val frames = mutableMapOf<Int, ImageBitmap>()

  // Highest sprite-sheet frame index we've seen successfully resolve. Frames are extracted
  // contiguously 1..N at report-generation time (see WasmReport.extractFromSpriteSheet), so
  // once we've discovered the upper bound we can clamp future requests instead of probing
  // hundreds of missing keys on every scrub.
  private var maxKnownExistingIndex: Int? = null

  override fun getFrame(timestampMs: Long): ImageBitmap? {
    val requestedIndex = (timestampMs * fps / 1000 + 1).toInt().coerceAtLeast(1)
    val targetIndex = maxKnownExistingIndex?.let { minOf(requestedIndex, it) } ?: requestedIndex
    frames[targetIndex]?.let { return it }
    // Synchronous variant can't probe embedded data — fall back to the nearest preceding
    // already-cached frame so scrubbing across un-cached gaps doesn't flash empty.
    for (i in (targetIndex - 1) downTo 1) {
      frames[i]?.let { return it }
    }
    return null
  }

  override suspend fun getFrameAsync(timestampMs: Long): ImageBitmap? {
    val requestedIndex = (timestampMs * fps / 1000 + 1).toInt().coerceAtLeast(1)
    val targetIndex = maxKnownExistingIndex?.let { minOf(requestedIndex, it) } ?: requestedIndex
    frames[targetIndex]?.let { return it }

    loadFrame(targetIndex)?.let { bitmap ->
      frames[targetIndex] = bitmap
      return bitmap
    }

    // Exact miss. Walk backward to find the nearest preceding existing frame and remember
    // its index so future high-index requests skip the walk entirely. resolveScreenshot
    // returns null for unknown keys without decompression, so the linear scan is cheap;
    // only the first hit pays the base64-decode cost.
    for (i in (targetIndex - 1) downTo 1) {
      frames[i]?.let {
        maxKnownExistingIndex = i
        return it
      }
      val bitmap = loadFrame(i)
      if (bitmap != null) {
        frames[i] = bitmap
        maxKnownExistingIndex = i
        return bitmap
      }
    }
    // No frames exist at any preceding index — there's genuinely nothing to show.
    return null
  }

  private suspend fun loadFrame(index: Int): ImageBitmap? {
    val paddedIndex = index.toString().padStart(6, '0')
    val imageKey = "$sessionId/vf_$paddedIndex.jpg"
    // Bypass `resolveScreenshot` (and therefore `transformImageUrl`) intentionally — sprite
    // frames live inside the compressed data block on the page; they are NEVER uploaded as
    // standalone files on the artifact server. Some hosted-report environments override
    // `transformImageUrl` to rewrite every relative key into a full `https://…/<key>` URL,
    // which 404s for sprite keys (the .jpg doesn't exist on disk), and the upstream resolver
    // returns the URL string verbatim instead of a data URL, so `dataUrlToImageBitmap` then
    // sees no base64 payload and we render "Loading frame…" forever. Going straight to the
    // JS decompress bridge skips that branch and lets the alias-aware compressed-images map
    // answer.
    val dataUrl = suspendCancellableCoroutine<String?> { continuation ->
      try {
        decompressImageCallback(imageKey) { continuation.resume(it) }
      } catch (e: Exception) {
        Console.log("Failed to resolve video frame: $imageKey - ${e.message}")
        continuation.resume(null)
      }
    } ?: return null
    return dataUrlToImageBitmap(dataUrl)
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
