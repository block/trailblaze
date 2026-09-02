package xyz.block.trailblaze.ui.composables

import androidx.compose.ui.graphics.ImageBitmap
import xyz.block.trailblaze.ui.tabs.session.SpriteSheetInfo

/** Inert wasmJs actual — see `xyz.block.trailblaze.ui.WasmActuals` for why this source set exists. */
actual fun createVideoFrameCache(
  videoPath: String,
  fps: Int,
  spriteInfo: SpriteSheetInfo?,
): VideoFrameCache = object : VideoFrameCache {
  override fun getFrame(timestampMs: Long): ImageBitmap? = null

  override fun isExtractionComplete(): Boolean = true

  override fun dispose() = Unit
}
