package xyz.block.trailblaze.setofmark.android

import android.graphics.Bitmap
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

  fun Bitmap.scale(
    scale: Float,
  ): Bitmap = if (scale == 1f) {
    this // No need to scale
  } else {
    val scaledBitmap = Bitmap.createScaledBitmap(
      this,
      (width * scale).toInt(),
      (height * scale).toInt(),
      true,
    )
    this.recycle() // Recycle the original bitmap to free up memory
    scaledBitmap
  }

  fun Bitmap.scale(
    maxDim1: Int,
    maxDim2: Int,
  ): Bitmap {
    val targetLong = maxOf(maxDim1, maxDim2)
    val targetShort = minOf(maxDim1, maxDim2)

    val imageLong = maxOf(width, height)
    val imageShort = minOf(width, height)

    // Only scale down, not up
    return if (imageLong <= targetLong && imageShort <= targetShort) {
      this
    } else {
      val scaleLong = targetLong.toFloat() / imageLong
      val scaleShort = targetShort.toFloat() / imageShort
      val scaleAmount = minOf(scaleLong, scaleShort)

      this.scale(scaleAmount)
    }
  }
}
