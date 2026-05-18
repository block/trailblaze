package xyz.block.trailblaze.ui.recording

import androidx.compose.ui.graphics.ImageBitmap
import org.jetbrains.compose.resources.decodeToImageBitmap

internal actual fun ByteArray.decodeFrameBytes(): ImageBitmap? = try {
  decodeToImageBitmap()
} catch (e: Exception) {
  null
}
