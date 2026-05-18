package xyz.block.trailblaze.ui.recording

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image as SkiaImage

internal actual fun ByteArray.decodeFrameBytes(): ImageBitmap? = try {
  val skiaImage = SkiaImage.makeFromEncoded(this)
  try {
    skiaImage.toComposeImageBitmap()
  } finally {
    skiaImage.close()
  }
} catch (e: Exception) {
  null
}
