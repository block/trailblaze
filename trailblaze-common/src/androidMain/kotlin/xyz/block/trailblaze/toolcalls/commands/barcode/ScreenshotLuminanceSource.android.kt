package xyz.block.trailblaze.toolcalls.commands.barcode

import android.graphics.BitmapFactory
import com.google.zxing.LuminanceSource
import com.google.zxing.RGBLuminanceSource

/**
 * `BitmapFactory` decodes PNG, JPEG and WebP natively, so on-device needs no reader plugin.
 *
 * The bitmap is recycled as soon as its pixels are copied out. A full-resolution screenshot is
 * ~15 MB as `ARGB_8888`, and the [IntArray] copy is another ~15 MB, so holding both any longer
 * than necessary is a real risk in an instrumentation process.
 */
actual fun screenshotBytesToLuminanceSource(screenshotBytes: ByteArray): LuminanceSource {
  val bitmap = BitmapFactory.decodeByteArray(screenshotBytes, 0, screenshotBytes.size)
    ?: error(
      "BitmapFactory could not decode ${screenshotBytes.size} bytes of screenshot as an image.",
    )
  val width = bitmap.width
  val height = bitmap.height
  val hasAlpha = bitmap.hasAlpha()
  val pixels = IntArray(width * height)
  try {
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
  } finally {
    bitmap.recycle()
  }

  // Matches the JVM actual's white composite, in place on the pixels already copied rather than
  // via a second bitmap. See `compositeOntoWhite` for why black-backed transparency breaks a
  // decode; it is shared code so it can be tested without a device.
  if (hasAlpha) {
    compositeOntoWhite(pixels)
  }

  return RGBLuminanceSource(width, height, pixels)
}
