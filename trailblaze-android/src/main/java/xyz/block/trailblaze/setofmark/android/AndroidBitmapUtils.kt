package xyz.block.trailblaze.setofmark.android

import android.graphics.Bitmap
import xyz.block.trailblaze.api.TrailblazeImageFormat
import java.io.ByteArrayOutputStream

object AndroidBitmapUtils {

  fun Bitmap.toByteArray(
    format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG,
    quality: Int = 100,
  ): ByteArray {
    val bitmap = this
    ByteArrayOutputStream().use {
      check(bitmap.compress(format, quality, it)) { "Failed to compress bitmap" }
      return it.toByteArray()
    }
  }

  /**
   * Converts a Bitmap to a byte array using ScreenshotScalingConfig format settings.
   */
  fun Bitmap.toByteArray(
    format: TrailblazeImageFormat,
    compressionQuality: Float,
  ): ByteArray {
    val androidFormat = when (format) {
      TrailblazeImageFormat.PNG -> Bitmap.CompressFormat.PNG
      TrailblazeImageFormat.JPEG -> Bitmap.CompressFormat.JPEG
    }
    // Android quality is 0-100, our config is 0.0-1.0
    val androidQuality = (compressionQuality * 100).toInt().coerceIn(0, 100)
    return toByteArray(androidFormat, androidQuality)
  }

  fun Bitmap.scale(
    scale: Float,
  ): Bitmap {
    if (scale == 1f) {
      return this // No need to scale
    }
    val newWidth = (width * scale).toInt()
    val newHeight = (height * scale).toInt()
    // Avoid unnecessary scaling if dimensions do not change
    if (newWidth == width && newHeight == height) {
      return this
    }
    val scaledBitmap = Bitmap.createScaledBitmap(
      this,
      newWidth,
      newHeight,
      true,
    )
    this.recycle() // Recycle the original bitmap to free up memory
    return scaledBitmap
  }

  fun Bitmap.scale(
    maxDim1: Int,
    maxDim2: Int,
  ): Bitmap {
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
