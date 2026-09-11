package xyz.block.trailblaze.toolcalls.commands.barcode

import com.google.zxing.BarcodeFormat
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatWriter
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.BitMatrix

/** Raw ARGB pixels, the one image representation both the JVM and Android can build. */
internal class PixelImage(val width: Int, val height: Int, val pixels: IntArray) {
  fun copyPixels() = PixelImage(width, height, pixels.copyOf())
}

/**
 * Builds barcode images in pure Kotlin — ZXing's writers and [RGBLuminanceSource], no
 * `BufferedImage` and no `ImageIO`.
 *
 * That restriction is what lets these fixtures run on Android as well as the JVM. The JVM-only
 * fixtures (real screenshots, WebP/JPEG encoding) live in `jvmTest` instead, because they need a
 * platform image codec; what lives here is everything that can be proven without one.
 */
internal object BarcodeTestMatrices {

  /** A barcode of [format] on a white background with a quiet zone, as ARGB pixels. */
  fun barcodePixels(
    content: String,
    format: BarcodeFormat,
    width: Int = 400,
    height: Int = if (format == BarcodeFormat.QR_CODE) 400 else 160,
  ): PixelImage = fromMatrix(MultiFormatWriter().encode(content, format, width, height))

  /**
   * A QR code whose background is fully transparent (alpha 0) rather than white — an overlay or a
   * reused icon asset. Un-composited these pixels are uniformly black to a luminance reader.
   */
  fun qrPixelsOnTransparentBackground(content: String): PixelImage {
    val opaque = barcodePixels(content, BarcodeFormat.QR_CODE)
    val pixels = IntArray(opaque.pixels.size) { index ->
      if (opaque.pixels[index] == OPAQUE_BLACK) OPAQUE_BLACK else TRANSPARENT
    }
    return PixelImage(opaque.width, opaque.height, pixels)
  }

  fun sourceFrom(image: PixelImage): LuminanceSource =
    RGBLuminanceSource(image.width, image.height, image.pixels)

  /**
   * Places [inner] into a larger white canvas, so the barcode is a small part of a big image the
   * way it is in a real screenshot. Offsets are fractions of the free space.
   */
  fun inset(inner: PixelImage, canvasWidth: Int, canvasHeight: Int): PixelImage {
    val pixels = IntArray(canvasWidth * canvasHeight) { OPAQUE_WHITE }
    val offsetX = (canvasWidth - inner.width) / 2
    val offsetY = (canvasHeight - inner.height) / 2
    for (y in 0 until inner.height) {
      for (x in 0 until inner.width) {
        pixels[(y + offsetY) * canvasWidth + (x + offsetX)] = inner.pixels[y * inner.width + x]
      }
    }
    return PixelImage(canvasWidth, canvasHeight, pixels)
  }

  private fun fromMatrix(matrix: BitMatrix): PixelImage {
    val pixels = IntArray(matrix.width * matrix.height) { index ->
      if (matrix[index % matrix.width, index / matrix.width]) OPAQUE_BLACK else OPAQUE_WHITE
    }
    return PixelImage(matrix.width, matrix.height, pixels)
  }

  private const val TRANSPARENT = 0x00000000
  private val OPAQUE_WHITE = 0xFFFFFFFF.toInt()
  private val OPAQUE_BLACK = 0xFF000000.toInt()
}
