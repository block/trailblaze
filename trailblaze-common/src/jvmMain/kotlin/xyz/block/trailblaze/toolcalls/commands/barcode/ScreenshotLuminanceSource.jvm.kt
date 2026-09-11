package xyz.block.trailblaze.toolcalls.commands.barcode

import com.google.zxing.LuminanceSource
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/**
 * Reads PNG and JPEG through the JDK's own ImageIO readers, and WebP — the default screenshot
 * format — through the TwelveMonkeys reader plugin that `build.gradle.kts` puts on the JVM
 * classpath. The plugin registers itself via the ImageIO SPI, so there is nothing to wire here
 * beyond depending on it.
 */
actual fun screenshotBytesToLuminanceSource(screenshotBytes: ByteArray): LuminanceSource {
  val original = ImageIO.read(ByteArrayInputStream(screenshotBytes))
    ?: error(
      "No ImageIO reader claimed ${screenshotBytes.size} bytes of screenshot. Readers " +
        "registered: ${ImageIO.getReaderFormatNames().sorted().joinToString(", ")}.",
    )

  // Composite onto a white canvas to drop any alpha channel. A transparent region in an RGBA
  // screenshot reads as luminance noise to ZXing's binarizer and can stop a perfectly legible
  // barcode from being found.
  val rgb = BufferedImage(original.width, original.height, BufferedImage.TYPE_INT_RGB)
  val graphics = rgb.createGraphics()
  try {
    graphics.color = Color.WHITE
    graphics.fillRect(0, 0, original.width, original.height)
    graphics.drawImage(original, 0, 0, null)
  } finally {
    graphics.dispose()
  }

  return BufferedImageLuminanceSource(rgb)
}
