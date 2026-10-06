package xyz.block.trailblaze.util

import com.luciad.imageio.webp.WebPImageWriterSpi
import com.luciad.imageio.webp.WebPWriteParam
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Locale
import javax.imageio.IIOImage
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageOutputStream

/**
 * Lossy WebP encoding on the host, through libwebp. The JDK has no WebP writer.
 *
 * Method 3 is the speed/size trade-off Skia's encoder made, which the host used before this: on 108
 * real screenshots the two produce the same average size, PSNR and encode time, so captures do not
 * change.
 *
 * The writer is built from its SPI rather than looked up through `ImageIO`, because the CLI JAR keeps
 * only the first `META-INF/services` file of each name and a registry lookup would depend on
 * classpath order.
 */
object WebpEncoder {

  private const val METHOD = 3

  private val writerSpi = WebPImageWriterSpi()

  /** [image] as a lossy WebP at [quality] (0.0 to 1.0). */
  fun encode(image: BufferedImage, quality: Float): ByteArray {
    val param = WebPWriteParam(Locale.ROOT).apply {
      compressionMode = ImageWriteParam.MODE_EXPLICIT
      setCompressionType("Lossy")
      compressionQuality = quality.coerceIn(0f, 1f)
      method = METHOD
    }
    val writer = writerSpi.createWriterInstance(null)
    val out = ByteArrayOutputStream()
    try {
      // Memory-backed: ImageIO.createImageOutputStream may spill to a temp file when its disk cache is on.
      MemoryCacheImageOutputStream(out).use { stream ->
        writer.output = stream
        writer.write(null, IIOImage(straightAlpha(image), null, null), param)
      }
    } finally {
      writer.dispose()
    }
    return out.toByteArray()
  }

  /** libwebp reads the raw samples, so premultiplied colors would come out darkened. */
  private fun straightAlpha(image: BufferedImage): BufferedImage {
    if (!image.isAlphaPremultiplied) return image
    return BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB).also { copy ->
      val g = copy.createGraphics()
      try {
        g.drawImage(image, 0, 0, null)
      } finally {
        g.dispose()
      }
    }
  }
}
