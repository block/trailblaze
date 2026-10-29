package xyz.block.trailblaze.util

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebpEncoderTest {

  @Test
  fun `encodes a WebP that decodes back to the same picture`() {
    val bytes = WebpEncoder.encode(screen(BufferedImage.TYPE_INT_RGB), quality = 0.9f)

    assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
    assertEquals("WEBP", String(bytes, 8, 4, Charsets.US_ASCII))
    val decoded = decode(bytes)
    assertEquals(320 to 640, decoded.width to decoded.height)
    assertNear(Color.RED, Color(decoded.getRGB(40, 40)))
    assertNear(Color.WHITE, Color(decoded.getRGB(300, 600)))
  }

  @Test
  fun `lower quality gives a smaller file`() {
    val image = noisy()

    val high = WebpEncoder.encode(image, quality = 0.9f).size
    val low = WebpEncoder.encode(image, quality = 0.2f).size

    assertTrue(low < high, "quality 0.2 gave $low bytes, quality 0.9 gave $high")
  }

  @Test
  fun `encodes any pixel layout, not just RGB`() {
    for (type in listOf(BufferedImage.TYPE_BYTE_GRAY, BufferedImage.TYPE_BYTE_INDEXED, BufferedImage.TYPE_INT_ARGB_PRE)) {
      val decoded = decode(WebpEncoder.encode(screen(type), quality = 0.9f))
      assertEquals(320 to 640, decoded.width to decoded.height, "type $type")
      assertNear(Color.WHITE, Color(decoded.getRGB(300, 600)), "type $type")
    }
  }

  @Test
  fun `premultiplied translucent pixels keep their color`() {
    val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB_PRE).apply {
      val g = createGraphics()
      g.color = Color(255, 0, 0, 128)
      g.fillRect(0, 0, width, height)
      g.dispose()
    }

    val pixel = Color(decode(WebpEncoder.encode(image, quality = 0.9f)).getRGB(32, 32), true)

    assertNear(Color.RED, pixel)
    assertTrue(abs(pixel.alpha - 128) <= 8, "expected alpha ~128, got ${pixel.alpha}")
  }

  /** A white phone-shaped screen with a red block in the top-left corner. */
  private fun screen(type: Int): BufferedImage = BufferedImage(320, 640, type).apply {
    val g = createGraphics()
    g.color = Color.WHITE
    g.fillRect(0, 0, width, height)
    g.color = Color.RED
    g.fillRect(0, 0, 100, 100)
    g.dispose()
  }

  private fun noisy(): BufferedImage = BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB).apply {
    val random = java.util.Random(7)
    for (x in 0 until width) for (y in 0 until height) setRGB(x, y, random.nextInt(0xFFFFFF))
  }

  private fun decode(bytes: ByteArray): BufferedImage =
    ImageIO.read(ByteArrayInputStream(bytes)) ?: error("no ImageIO reader decoded the WebP")

  private fun assertNear(expected: Color, actual: Color, message: String = "") {
    val off = maxOf(abs(expected.red - actual.red), abs(expected.green - actual.green), abs(expected.blue - actual.blue))
    assertTrue(off <= 8, "$message expected ~$expected, got $actual")
  }
}
