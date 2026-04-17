package xyz.block.trailblaze.ui.composables

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import org.jetbrains.skia.Image
import xyz.block.trailblaze.ui.tabs.session.SpriteSheetInfo
import xyz.block.trailblaze.util.Console

actual fun createVideoFrameCache(
  videoPath: String,
  fps: Int,
  spriteInfo: SpriteSheetInfo?,
): VideoFrameCache {
  return JvmSpriteFrameCache(videoPath, spriteInfo)
}

/**
 * Loads a sprite sheet image and crops individual frames on demand. No ffmpeg needed — the sprite
 * sheet is a single JPEG with frames arranged in a grid at [spriteInfo.frameHeight] pixels each.
 * Physical frame N is at column `N / rows`, row `N % rows`.
 */
private class JvmSpriteFrameCache(
  spriteSheetPath: String,
  private val spriteInfo: SpriteSheetInfo?,
) : VideoFrameCache {
  private val spriteImage: java.awt.image.BufferedImage?
  private val frames = mutableMapOf<Int, ImageBitmap>()

  init {
    val spriteFile = File(spriteSheetPath)
    spriteImage =
      if (spriteFile.exists()) {
        try {
          ImageIO.read(spriteFile)
        } catch (e: Exception) {
          Console.log("Failed to load sprite sheet: ${e.message}")
          null
        }
      } else null
  }

  override fun getFrame(timestampMs: Long): ImageBitmap? {
    val info = spriteInfo ?: return null
    val image = spriteImage ?: return null
    val frameIndex =
      (timestampMs * info.fps / 1000).toInt().coerceIn(0, info.frameCount - 1)
    frames[frameIndex]?.let { return it }

    // Resolve logical frame index to physical sprite position via frameMap (if present).
    val physicalIndex = info.frameMap?.getOrNull(frameIndex) ?: frameIndex

    return try {
      // Grid layout: physical frame N is at column N/rows, row N%rows
      val col = physicalIndex / info.rows
      val row = physicalIndex % info.rows
      val frameWidth = image.width / info.columns
      val x = col * frameWidth
      val y = row * info.frameHeight
      val width = frameWidth.coerceAtMost(image.width - x)
      val height = info.frameHeight.coerceAtMost(image.height - y)
      if (width <= 0 || height <= 0) return null
      val subImage = image.getSubimage(x, y, width, height)
      // Convert BufferedImage to Skia Image via JPEG bytes
      val baos = ByteArrayOutputStream()
      ImageIO.write(subImage, "jpg", baos)
      val bitmap = Image.makeFromEncoded(baos.toByteArray()).toComposeImageBitmap()
      frames[frameIndex] = bitmap
      bitmap
    } catch (e: Exception) {
      null
    }
  }

  override fun isExtractionComplete(): Boolean = true

  override fun dispose() {
    frames.clear()
  }
}
