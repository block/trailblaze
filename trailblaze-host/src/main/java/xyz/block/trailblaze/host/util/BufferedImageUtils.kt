package xyz.block.trailblaze.host.util

import xyz.block.trailblaze.api.TrailblazeImageFormat
import java.awt.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

object BufferedImageUtils {

  /**
   * Converts a BufferedImage to a byte array with the specified format and quality.
   */
  fun BufferedImage.toByteArray(
    format: TrailblazeImageFormat = TrailblazeImageFormat.PNG,
    compressionQuality: Float = 0.85f,
  ): ByteArray {
    ByteArrayOutputStream().use { outputStream ->
      when (format) {
        TrailblazeImageFormat.PNG -> {
          ImageIO.write(this, format.formatName, outputStream)
        }

        TrailblazeImageFormat.JPEG -> {
          // JPEG doesn't support transparency, so convert ARGB to RGB if needed
          val imageToWrite = if (this.type == BufferedImage.TYPE_INT_ARGB ||
            this.type == BufferedImage.TYPE_4BYTE_ABGR ||
            this.transparency != BufferedImage.OPAQUE
          ) {
            val rgbImage = BufferedImage(this.width, this.height, BufferedImage.TYPE_INT_RGB)
            val graphics = rgbImage.createGraphics()
            try {
              // Fill with white background (common convention for removing alpha)
              graphics.drawImage(this, 0, 0, java.awt.Color.WHITE, null)
            } finally {
              graphics.dispose()
            }
            rgbImage
          } else {
            this
          }

          val writer = ImageIO.getImageWritersByFormatName(format.formatName).next()
          val writeParam = writer.defaultWriteParam

          if (writeParam.canWriteCompressed()) {
            writeParam.compressionMode = ImageWriteParam.MODE_EXPLICIT
            writeParam.compressionQuality = compressionQuality.coerceIn(0f, 1f)
          }

          writer.output = ImageIO.createImageOutputStream(outputStream)
          writer.write(null, IIOImage(imageToWrite, null, null), writeParam)
          writer.dispose()
        }
      }
      return outputStream.toByteArray()
    }
  }

  /**
   * Scales a BufferedImage by a specific scale factor.
   */
  fun BufferedImage.scale(
    scale: Float,
  ): BufferedImage = if (scale == 1f) {
    this // No need to scale
  } else {
    val scaledWidth = (width * scale).toInt()
    val scaledHeight = (height * scale).toInt()

    val scaledImage = BufferedImage(scaledWidth, scaledHeight, type)
    val graphics = scaledImage.createGraphics()

    try {
      // Use high-quality scaling
      graphics.drawImage(
        this.getScaledInstance(scaledWidth, scaledHeight, Image.SCALE_SMOOTH),
        0,
        0,
        null,
      )
    } finally {
      graphics.dispose()
    }

    scaledImage
  }

  /**
   * Scales a BufferedImage to fit within the specified dimensions while maintaining aspect ratio.
   * Only scales down, never up. Mirrors the behavior of AndroidBitmapUtils.scale().
   *
   * @param maxDim1 One of the maximum dimensions (either width or height)
   * @param maxDim2 The other maximum dimension (either width or height)
   * @return A scaled BufferedImage if scaling is needed, or the original if it already fits
   */
  fun BufferedImage.scale(
    maxDim1: Int,
    maxDim2: Int,
  ): BufferedImage {
    val targetLong = maxOf(maxDim1, maxDim2)
    val targetShort = minOf(maxDim1, maxDim2)

    val imageLong = maxOf(width, height)
    val imageShort = minOf(width, height)

    // Only scale down if image exceeds bounds
    if (imageLong <= targetLong && imageShort <= targetShort) {
      return this // Already fits, no scaling needed
    }

    // Calculate scale factors for both dimensions
    val scaleLong = targetLong.toFloat() / imageLong
    val scaleShort = targetShort.toFloat() / imageShort
    val scaleAmount = minOf(scaleLong, scaleShort)

    return this.scale(scaleAmount)
  }
}
