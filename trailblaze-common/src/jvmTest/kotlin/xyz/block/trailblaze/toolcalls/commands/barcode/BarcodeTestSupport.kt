package xyz.block.trailblaze.toolcalls.commands.barcode

import com.google.zxing.common.BitMatrix
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import xyz.block.trailblaze.api.TrailblazeImageFormat
import xyz.block.trailblaze.api.ViewHierarchyTreeNode

/** Encodes synthetic test images on the JVM. */
internal object JvmTestImageIo {

  fun fromMatrix(matrix: BitMatrix): BufferedImage {
    val image = BufferedImage(matrix.width, matrix.height, BufferedImage.TYPE_INT_RGB)
    for (y in 0 until matrix.height) {
      for (x in 0 until matrix.width) {
        image.setRGB(x, y, if (matrix[x, y]) BLACK else WHITE)
      }
    }
    return image
  }

  fun encode(image: BufferedImage, format: TrailblazeImageFormat): ByteArray = when (format) {
    TrailblazeImageFormat.PNG -> ByteArrayOutputStream()
      .also { ImageIO.write(image, "png", it) }
      .toByteArray()

    TrailblazeImageFormat.JPEG -> lossyJpeg(image, quality = 0.80f)

    // No JVM WebP *writer* exists (which is why the reader had to be added as a dependency), so
    // WebP coverage comes from the committed driver-encoded fixture instead of a synthetic image.
    TrailblazeImageFormat.WEBP -> BarcodeTestImages.driverEncodedScreenshotBytes()
  }

  /** A QR code drawn on a fully transparent background, as PNG. */
  fun encodeTransparentBackground(opaque: BufferedImage): ByteArray {
    val argb = BufferedImage(opaque.width, opaque.height, BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until opaque.height) {
      for (x in 0 until opaque.width) {
        val isDark = (opaque.getRGB(x, y) and 0xFFFFFF) == 0
        argb.setRGB(x, y, if (isDark) BLACK else TRANSPARENT)
      }
    }
    return ByteArrayOutputStream().also { ImageIO.write(argb, "png", it) }.toByteArray()
  }

  private fun lossyJpeg(image: BufferedImage, quality: Float): ByteArray {
    val out = ByteArrayOutputStream()
    val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
    val params = writer.defaultWriteParam.apply {
      compressionMode = ImageWriteParam.MODE_EXPLICIT
      compressionQuality = quality
    }
    ImageIO.createImageOutputStream(out).use { stream ->
      writer.output = stream
      writer.write(null, IIOImage(image, null, null), params)
    }
    writer.dispose()
    return out.toByteArray()
  }

  /**
   * The screenshot downscaled to a 768px short side and re-encoded as lossy JPEG — what a driver
   * delivers when the user sets `screenshot-format jpeg`. Kept distinct from the committed WebP
   * fixture because WebP at the same quality is visibly cleaner, and the difference matters: the
   * 1D-reader false positive only appears on the JPEG.
   */
  fun lossyJpegAtDefaultScale(pngBytes: ByteArray): ByteArray {
    val original = ImageIO.read(java.io.ByteArrayInputStream(pngBytes))!!
    val scale = minOf(
      1536f / maxOf(original.width, original.height),
      768f / minOf(original.width, original.height),
    )
    val width = (original.width * scale).toInt()
    val height = (original.height * scale).toInt()
    val scaled = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val graphics = scaled.createGraphics()
    try {
      graphics.setRenderingHint(
        java.awt.RenderingHints.KEY_INTERPOLATION,
        java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR,
      )
      graphics.drawImage(original, 0, 0, width, height, null)
    } finally {
      graphics.dispose()
    }
    return lossyJpeg(scaled, quality = 0.80f)
  }

  fun blankPng(width: Int, height: Int): ByteArray {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val graphics = image.createGraphics()
    try {
      graphics.color = java.awt.Color.WHITE
      graphics.fillRect(0, 0, width, height)
    } finally {
      graphics.dispose()
    }
    return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
  }

  private const val WHITE = 0xFFFFFF
  private const val BLACK = 0xFF000000.toInt()
  private const val TRANSPARENT = 0x00000000
}

/** View hierarchies standing in for the top of an iOS screenshot. */
internal object JvmTestHierarchies {

  /**
   * Status bar, address bar and tab strip stacked at the top, a bottom tab bar that the
   * top-quarter rule has to ignore, and page content that is not chrome at all.
   */
  fun iosChrome(deviceHeight: Int) = ViewHierarchyTreeNode(
    className = "Root",
    x1 = 0,
    y1 = 0,
    x2 = WIDTH,
    y2 = deviceHeight,
    children = listOf(
      node("UIStatusBarView", y1 = 0, y2 = 24),
      node("_UINavigationBarContentView", y1 = 24, y2 = 118),
      node("BrowserTabBarView", y1 = 118, y2 = 170),
      node("WebContentView", y1 = 170, y2 = deviceHeight - 60),
      // Named like chrome, but at the bottom of the screen — a crop to here would throw away the
      // entire screenshot.
      node("UITabBar", y1 = deviceHeight - 60, y2 = deviceHeight),
    ),
  )

  fun noChrome(deviceHeight: Int) = ViewHierarchyTreeNode(
    className = "Root",
    x1 = 0,
    y1 = 0,
    x2 = WIDTH,
    y2 = deviceHeight,
    children = listOf(node("WebContentView", y1 = 0, y2 = deviceHeight)),
  )

  private fun node(className: String, y1: Int, y2: Int) = ViewHierarchyTreeNode(
    className = className,
    x1 = 0,
    y1 = y1,
    x2 = WIDTH,
    y2 = y2,
  )

  private const val WIDTH = 820
}
