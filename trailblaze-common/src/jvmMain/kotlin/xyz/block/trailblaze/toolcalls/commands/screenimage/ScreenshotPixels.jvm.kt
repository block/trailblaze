package xyz.block.trailblaze.toolcalls.commands.screenimage

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/**
 * PNG and JPEG through the JDK's ImageIO readers; WebP through the webp-imageio plugin that
 * `build.gradle.kts` puts on the JVM classpath for the barcode reader.
 */
actual fun decodeScreenshot(bytes: ByteArray): DecodedScreenshot {
  val image = ImageIO.read(ByteArrayInputStream(bytes))
    ?: error(
      "No ImageIO reader claimed ${bytes.size} bytes of screenshot. Readers registered: " +
        "${ImageIO.getReaderFormatNames().sorted().joinToString(", ")}.",
    )
  return object : DecodedScreenshot {
    override val width = image.width
    override val height = image.height
    override fun readRow(x: Int, y: Int, length: Int, into: IntArray) {
      image.getRGB(x, y, length, 1, into, 0, length)
    }
    override fun close() = Unit
  }
}
