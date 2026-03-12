package xyz.block.trailblaze.compose.driver

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toAwtImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** Converts a Compose [ImageBitmap] to PNG byte array. */
fun imageBitmapToPngBytes(image: ImageBitmap): ByteArray {
  val bufferedImage = image.toAwtImage()
  val outputStream = ByteArrayOutputStream()
  ImageIO.write(bufferedImage, "PNG", outputStream)
  return outputStream.toByteArray()
}
