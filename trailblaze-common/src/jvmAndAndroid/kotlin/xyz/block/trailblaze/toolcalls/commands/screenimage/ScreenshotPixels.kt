package xyz.block.trailblaze.toolcalls.commands.screenimage

import xyz.block.trailblaze.api.TrailblazeNode

/**
 * A driver screenshot decoded for reading. Rows are copied out one at a time, so a crop never
 * needs a second full-screen pixel array beside the decoded image.
 */
interface DecodedScreenshot : AutoCloseable {
  val width: Int
  val height: Int

  /** Copies [length] `0xAARRGGBB` pixels of row [y], starting at column [x], into [into]. */
  fun readRow(x: Int, y: Int, length: Int, into: IntArray)
}

/**
 * Decodes a driver's encoded screenshot: PNG, WebP or JPEG, whatever the effective
 * `ScreenshotScalingConfig` produced. ImageIO on the JVM, BitmapFactory on Android, for the same
 * reason the barcode reader splits `screenshotBytesToLuminanceSource`.
 *
 * @throws IllegalStateException if the bytes cannot be decoded as an image.
 */
expect fun decodeScreenshot(bytes: ByteArray): DecodedScreenshot

/** The container a screenshot was encoded in, and whether it keeps every pixel's exact colour. */
internal enum class ScreenshotEncoding(val wireName: String, val lossless: Boolean) {
  PNG("png", lossless = true),
  WEBP_LOSSLESS("webp", lossless = true),
  WEBP_LOSSY("webp", lossless = false),
  JPEG("jpeg", lossless = false),
  UNKNOWN("unknown", lossless = false),
  ;

  companion object {
    /** Reads the container's magic bytes; for WebP, walks the RIFF chunks to find `VP8L` vs `VP8 `. */
    fun of(bytes: ByteArray): ScreenshotEncoding {
      fun at(i: Int) = bytes.getOrNull(i)?.toInt()?.and(0xFF) ?: -1
      fun fourCc(i: Int) = if (i + 4 <= bytes.size) bytes.decodeToString(i, i + 4) else ""
      return when {
        at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 -> PNG
        at(0) == 0xFF && at(1) == 0xD8 -> JPEG
        fourCc(0) == "RIFF" && fourCc(8) == "WEBP" -> webpEncoding(bytes, ::fourCc)
        else -> UNKNOWN
      }
    }

    private fun webpEncoding(bytes: ByteArray, fourCc: (Int) -> String): ScreenshotEncoding {
      var offset = 12
      while (offset + 8 <= bytes.size) {
        when (fourCc(offset)) {
          "VP8L" -> return WEBP_LOSSLESS
          "VP8 " -> return WEBP_LOSSY
        }
        val size = (bytes[offset + 4].toInt() and 0xFF) or
          ((bytes[offset + 5].toInt() and 0xFF) shl 8) or
          ((bytes[offset + 6].toInt() and 0xFF) shl 16) or
          ((bytes[offset + 7].toInt() and 0xFF) shl 24)
        if (size < 0) break
        // Chunks are padded to an even length.
        offset += 8 + size + (size and 1)
      }
      return WEBP_LOSSY
    }
  }
}

/**
 * Maps a rectangle in the driver's coordinates to the screenshot's pixels, scaling each axis on
 * its own (screenshot pixels ÷ device units), then clamps it to the image.
 *
 * Driver units are device pixels on Android, points on iOS and CSS pixels on web, and the
 * screenshot may be downscaled by the driver, so neither axis can assume a 1:1 mapping. The edges
 * round outward so a fractional mapping never drops the element's outermost pixels.
 *
 * Returns null when the clamped rectangle has no area: off screen, or empty to begin with.
 */
internal fun toPixelRect(
  bounds: TrailblazeNode.Bounds?,
  deviceWidth: Int,
  deviceHeight: Int,
  imageWidth: Int,
  imageHeight: Int,
): TrailblazeNode.Bounds? {
  if (bounds == null) return TrailblazeNode.Bounds(0, 0, imageWidth, imageHeight)
  val scaleX = imageWidth.toDouble() / deviceWidth
  val scaleY = imageHeight.toDouble() / deviceHeight
  val left = kotlin.math.floor(bounds.left * scaleX).toInt().coerceIn(0, imageWidth)
  val top = kotlin.math.floor(bounds.top * scaleY).toInt().coerceIn(0, imageHeight)
  val right = kotlin.math.ceil(bounds.right * scaleX).toInt().coerceIn(left, imageWidth)
  val bottom = kotlin.math.ceil(bounds.bottom * scaleY).toInt().coerceIn(top, imageHeight)
  if (right == left || bottom == top) return null
  return TrailblazeNode.Bounds(left, top, right, bottom)
}

/** Output size for a [width] x [height] crop whose longer side may be at most [maxDimension]. */
internal fun outputSize(width: Int, height: Int, maxDimension: Int): Pair<Int, Int> {
  val longest = maxOf(width, height)
  if (longest <= maxDimension) return width to height
  val scale = maxDimension.toDouble() / longest
  return maxOf(1, Math.round(width * scale).toInt()) to maxOf(1, Math.round(height * scale).toInt())
}

/**
 * Samples [rect] of [source] to [outWidth] x [outHeight] RGBA bytes, nearest-neighbour at each
 * output pixel's centre. Nearest-neighbour never blends two colours, so on a lossless source
 * every returned pixel is a colour that was actually on screen.
 */
internal fun sampleRgba(
  source: DecodedScreenshot,
  rect: TrailblazeNode.Bounds,
  outWidth: Int,
  outHeight: Int,
): ByteArray {
  val cropWidth = rect.right - rect.left
  val cropHeight = rect.bottom - rect.top
  val row = IntArray(cropWidth)
  val columns = IntArray(outWidth) { x -> ((2L * x + 1) * cropWidth / (2L * outWidth)).toInt() }
  val rgba = ByteArray(outWidth * outHeight * 4)
  var o = 0
  for (y in 0 until outHeight) {
    val sourceY = rect.top + ((2L * y + 1) * cropHeight / (2L * outHeight)).toInt()
    source.readRow(rect.left, sourceY, cropWidth, row)
    for (x in 0 until outWidth) {
      val p = row[columns[x]]
      rgba[o++] = (p shr 16).toByte()
      rgba[o++] = (p shr 8).toByte()
      rgba[o++] = p.toByte()
      rgba[o++] = (p ushr 24).toByte()
    }
  }
  return rgba
}

/** True when no pixel of [rgba] has a colour channel above [BLANK_CHANNEL_MAX]. */
internal fun isBlank(rgba: ByteArray): Boolean {
  for (i in rgba.indices) {
    if (i % 4 != 3 && (rgba[i].toInt() and 0xFF) > BLANK_CHANNEL_MAX) return false
  }
  return true
}

/**
 * The brightest channel value still counted as black. Above zero because a lossy encoder can lift
 * pure black by a few levels.
 */
internal const val BLANK_CHANNEL_MAX = 8

/**
 * True when every pixel of [image], at full resolution, is black. Stops at the first pixel that
 * isn't, so a screen with content returns almost at once; a sample would miss a thin stripe.
 */
internal fun isBlankScreenshot(image: DecodedScreenshot): Boolean {
  val row = IntArray(image.width)
  for (y in 0 until image.height) {
    image.readRow(0, y, image.width, row)
    for (argb in row) {
      if ((argb ushr 16 and 0xFF) > BLANK_CHANNEL_MAX ||
        (argb ushr 8 and 0xFF) > BLANK_CHANNEL_MAX ||
        (argb and 0xFF) > BLANK_CHANNEL_MAX
      ) {
        return false
      }
    }
  }
  return true
}
