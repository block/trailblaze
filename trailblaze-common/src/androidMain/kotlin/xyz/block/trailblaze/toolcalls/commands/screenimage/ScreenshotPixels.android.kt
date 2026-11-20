package xyz.block.trailblaze.toolcalls.commands.screenimage

import android.graphics.BitmapFactory

/**
 * `BitmapFactory` decodes PNG, JPEG and WebP natively. Rows are read straight off the bitmap and
 * the bitmap is recycled on [DecodedScreenshot.close], so a crop costs the decoded screenshot and
 * nothing screen-sized beside it: this can run inside the app's own heap.
 */
actual fun decodeScreenshot(bytes: ByteArray): DecodedScreenshot {
  val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    ?: error("BitmapFactory could not decode ${bytes.size} bytes of screenshot as an image.")
  return object : DecodedScreenshot {
    override val width = bitmap.width
    override val height = bitmap.height
    override fun readRow(x: Int, y: Int, length: Int, into: IntArray) {
      bitmap.getPixels(into, 0, length, x, y, length, 1)
    }
    override fun close() = bitmap.recycle()
  }
}
