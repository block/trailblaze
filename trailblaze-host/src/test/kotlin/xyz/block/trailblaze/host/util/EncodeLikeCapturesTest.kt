package xyz.block.trailblaze.host.util

import xyz.block.trailblaze.api.EffectiveScreenshotScalingConfig
import xyz.block.trailblaze.api.ScreenshotScalingConfig
import xyz.block.trailblaze.api.TrailblazeImageFormat
import java.awt.image.BufferedImage
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A set-of-mark overlay the host draws on an on-device screenshot goes out in every LLM request
 * that carries it, so it must be encoded the way captures are, not as a lossless PNG.
 */
class EncodeLikeCapturesTest {

  private val image = BufferedImage(64, 128, BufferedImage.TYPE_INT_RGB)

  @AfterTest
  fun resetConfig() {
    EffectiveScreenshotScalingConfig.setEffectiveDefault(null)
  }

  @Test
  fun `the default capture format is what the overlay is written in`() {
    assertEquals(TrailblazeImageFormat.WEBP, ScreenshotScalingConfig.DEFAULT.imageFormat)

    assertEquals("WEBP", riffKind(BufferedImageUtils.encodeLikeCaptures(image)))
  }

  @Test
  fun `a configured capture format is followed`() {
    EffectiveScreenshotScalingConfig.setEffectiveDefault(
      ScreenshotScalingConfig(imageFormat = TrailblazeImageFormat.JPEG),
    )

    val bytes = BufferedImageUtils.encodeLikeCaptures(image)

    assertEquals(listOf(0xFF, 0xD8), bytes.take(2).map { it.toInt() and 0xFF })
  }

  private fun riffKind(bytes: ByteArray): String = String(bytes.copyOfRange(8, 12), Charsets.US_ASCII)
}
