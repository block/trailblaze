package xyz.block.trailblaze.toolcalls.commands.barcode

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEqualTo
import assertk.assertions.isSameInstanceAs
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatWriter
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import org.junit.Test
import xyz.block.trailblaze.api.TrailblazeImageFormat
import xyz.block.trailblaze.toolcalls.commands.barcode.BarcodeTestImages.QR_CODE_CONTENT
import xyz.block.trailblaze.toolcalls.commands.barcode.BarcodeTestImages.driverEncodedScreenshotBytes
import xyz.block.trailblaze.toolcalls.commands.barcode.BarcodeTestImages.screenshotBytes
import xyz.block.trailblaze.toolcalls.commands.barcode.BarcodeTestImages.screenshotSource
import kotlin.test.assertFailsWith

/**
 * Exercises the decoder against real iPad-simulator captures of a page showing a QR code that
 * encodes [QR_CODE_CONTENT], with the iOS status bar and the Safari address bar and tab strip
 * above it. Browsing-history text in the tab titles was painted out before committing; the
 * favicons, which are the pixels that matter here, are untouched.
 *
 * The screenshot is the point: a bare QR image decodes on the first try and would prove nothing
 * about [BarcodeDecoder], whose whole reason to exist is that screenshots do not.
 */
class BarcodeDecoderTest {

  @Test
  fun `decodes the QR code out of a full device screenshot`() {
    assertThat(BarcodeDecoder.decode(screenshotSource())).isEqualTo(QR_CODE_CONTENT)
  }

  @Test
  fun `decodes the screenshot in the size and format a driver actually delivers`() {
    // The committed PNG is a raw capture. What a driver puts on ScreenState.screenshotBytes has
    // been through ScreenshotScalingConfig: downscaled to a 768px short side and re-encoded as
    // lossy WebP. Decoding the raw capture proves nothing about the bytes the tool really gets.
    val source = screenshotBytesToLuminanceSource(driverEncodedScreenshotBytes())

    assertThat(source.width).isEqualTo(768)
    assertThat(BarcodeDecoder.decode(source)).isEqualTo(QR_CODE_CONTENT)
  }

  @Test
  fun `linear symbologies read a wrong value off a lossy screenshot, so they are opt-in`() {
    // The reason BarcodeDecoder allow-lists formats instead of letting every ZXing reader
    // participate. On a JPEG capture at the default scale, the 1D readers find a barcode in the
    // compression noise and win the race against the real QR code — returning a confident wrong
    // answer (an 8-digit number, at the time of writing "02807591"), which is worse than no
    // answer because the caller remembers it and asserts on it.
    val source = screenshotBytesToLuminanceSource(BarcodeTestImages.lossyJpegScreenshotBytes())

    val withLinear = BarcodeDecoder.decode(
      source,
      BarcodeDecoder.TWO_DIMENSIONAL_FORMATS + BarcodeDecoder.LINEAR_FORMATS,
    )
    assertThat(withLinear).isNotEqualTo(QR_CODE_CONTENT)

    // The default set gets it right on the same pixels. If a change lets linear formats back into
    // the default, this half fails.
    assertThat(BarcodeDecoder.decode(source)).isEqualTo(QR_CODE_CONTENT)
  }

  @Test
  fun `falls back to tiles when the barcode is too small to find in the whole image`() {
    // A small code in a large otherwise-empty frame: the whole-image pass misses it, and only the
    // tile escalation brings it within reach. Pins the fallback independently of any one capture.
    val source = BarcodeTestImages.qrInLargeCanvas(
      content = "tile-fallback",
      qrSize = 120,
      canvasWidth = 1400,
      canvasHeight = 2200,
      offsetX = 900,
      offsetY = 1700,
    )

    assertFailsWith<NotFoundException> {
      BarcodeDecoder.decode(source.crop(0, 0, source.width, source.height / 3))
    }
    assertThat(BarcodeDecoder.decode(source)).isEqualTo("tile-fallback")
  }

  @Test
  fun `reports how much was scanned so a miss can be told apart from a bad capture`() {
    var attempt: BarcodeDecoder.Attempt? = null
    assertFailsWith<NotFoundException> {
      BarcodeDecoder.decode(BarcodeTestImages.blankSource(800, 1200)) { attempt = it }
    }

    // Whole image plus every tile of the 2x3 grid.
    assertThat(attempt).isEqualTo(BarcodeDecoder.Attempt(regionsScanned = 7, tiled = true))
  }

  @Test
  fun `an uncroppable source is scanned whole rather than crashing`() {
    // LuminanceSource.crop throws UnsupportedOperationException unless the implementation opts in,
    // and that is not a NotFoundException, so an unguarded tile scan would escape decode's
    // contract entirely.
    val uncroppable = BarcodeTestImages.uncroppable(BarcodeTestImages.blankSource(400, 400))

    assertFailsWith<NotFoundException> { BarcodeDecoder.decode(uncroppable) }
  }

  @Test
  fun `cropping chrome starts the scan below the lowest bar in the top quarter of the screen`() {
    val source = screenshotSource()
    // 2360 screenshot pixels over 1180 logical points — a 2x iPad screen.
    val deviceHeight = 1180

    val cropped = source.cropAwaySystemChrome(
      viewHierarchy = BarcodeTestImages.iosChromeHierarchy(deviceHeight = deviceHeight),
      deviceHeight = deviceHeight,
    )

    // Chrome bottoms are 24 / 118 / 170pt, so the crop starts at 170pt = 340px. The 1180pt
    // bottom tab bar is outside the top quarter and must not drag the crop down with it.
    assertThat(cropped.height).isEqualTo(source.height - 340)
    assertThat(cropped.width).isEqualTo(source.width)
  }

  @Test
  fun `a hierarchy with no chrome bars leaves the image untouched`() {
    val source = screenshotSource()

    assertThat(
      source.cropAwaySystemChrome(
        viewHierarchy = BarcodeTestImages.hierarchyWithoutChrome(deviceHeight = 1180),
        deviceHeight = 1180,
      ),
    ).isSameInstanceAs(source)
  }

  @Test
  fun `an unknown device height leaves the image untouched rather than guessing a scale`() {
    val source = screenshotSource()

    assertThat(
      source.cropAwaySystemChrome(
        viewHierarchy = BarcodeTestImages.iosChromeHierarchy(deviceHeight = 1180),
        deviceHeight = 0,
      ),
    ).isSameInstanceAs(source)
  }

  @Test
  fun `every format a driver can encode a screenshot as is readable`() {
    // ScreenshotScalingConfig can emit any TrailblazeImageFormat, and the JVM host needs a reader
    // for all of them — the JDK has none for WebP, which is the default. Enumerated from the enum
    // so a new format cannot be added without this failing.
    val decodedPerFormat = TrailblazeImageFormat.entries.associateWith { format ->
      val encoded = BarcodeTestImages.encodeQr(QR_CODE_CONTENT, format)
      BarcodeDecoder.decode(screenshotBytesToLuminanceSource(encoded))
    }

    assertThat(decodedPerFormat).isEqualTo(
      TrailblazeImageFormat.entries.associateWith { QR_CODE_CONTENT },
    )
  }

  @Test
  fun `bytes that are not an image name the readers that were tried`() {
    val failure = assertFailsWith<IllegalStateException> {
      screenshotBytesToLuminanceSource(byteArrayOf(1, 2, 3, 4))
    }

    // The message has to name what was registered: "no reader claimed it" with a missing WebP
    // plugin is the exact failure this tool shipped with, and it was indistinguishable from
    // corrupt bytes.
    assertThat(failure.message!!.contains("webp", ignoreCase = true)).isEqualTo(true)
  }

  @Test
  fun `a transparent barcode is composited rather than read as black`() {
    val decoded = BarcodeDecoder.decode(
      screenshotBytesToLuminanceSource(BarcodeTestImages.transparentBackgroundQrPng("rgba-qr")),
    )

    assertThat(decoded).isEqualTo("rgba-qr")
  }

  @Test
  fun `screenshot fixtures stay in sync`() {
    // Guards the pair: if the PNG is ever re-captured without regenerating the WebP, the
    // driver-shaped test above would silently keep asserting against a stale image.
    assertThat(BarcodeDecoder.decode(screenshotSource()))
      .isEqualTo(BarcodeDecoder.decode(screenshotBytesToLuminanceSource(driverEncodedScreenshotBytes())))
    assertThat(screenshotBytes().isNotEmpty()).isEqualTo(true)
  }
}

/** Fixtures and synthetic images shared by the barcode tests. */
internal object BarcodeTestImages {

  const val QR_CODE_CONTENT = "https://squareup.com"

  fun screenshotBytes(): ByteArray = readResource("screenshot_with_qr_code.png")

  /**
   * The same screenshot after the default [xyz.block.trailblaze.api.ScreenshotScalingConfig]
   * treatment a driver applies: 768px short side, lossy WebP. Regenerate with
   * `ffmpeg -i screenshot_with_qr_code.png -vf scale=768:-1 -quality 80 <out>.webp`.
   */
  fun driverEncodedScreenshotBytes(): ByteArray =
    readResource("screenshot_with_qr_code_driver_encoded.webp")

  fun screenshotSource(): LuminanceSource = screenshotBytesToLuminanceSource(screenshotBytes())

  private fun readResource(name: String): ByteArray = BarcodeTestImages::class.java.classLoader
    .getResourceAsStream(name)
    ?.use { it.readBytes() }
    ?: error("Test asset $name not found in jvmTest resources")

  fun encodeQr(content: String, format: TrailblazeImageFormat): ByteArray {
    val image = qrBufferedImage(content, size = 400)
    return JvmTestImageIo.encode(image, format)
  }

  fun transparentBackgroundQrPng(content: String): ByteArray =
    JvmTestImageIo.encodeTransparentBackground(qrBufferedImage(content, size = 400))

  fun encodeBlankPng(width: Int, height: Int): ByteArray = JvmTestImageIo.blankPng(width, height)

  /** The screenshot as a driver would deliver it with `screenshot-format jpeg`. */
  fun lossyJpegScreenshotBytes(): ByteArray =
    JvmTestImageIo.lossyJpegAtDefaultScale(screenshotBytes())

  /** A [qrSize]px QR code placed at ([offsetX], [offsetY]) on an otherwise white canvas. */
  fun qrInLargeCanvas(
    content: String,
    qrSize: Int,
    canvasWidth: Int,
    canvasHeight: Int,
    offsetX: Int,
    offsetY: Int,
  ): LuminanceSource {
    val pixels = IntArray(canvasWidth * canvasHeight) { WHITE }
    val matrix = MultiFormatWriter().encode(
      content,
      BarcodeFormat.QR_CODE,
      qrSize,
      qrSize,
      mapOf(EncodeHintType.MARGIN to 1),
    )
    for (y in 0 until matrix.height) {
      for (x in 0 until matrix.width) {
        val canvasX = offsetX + x
        val canvasY = offsetY + y
        if (canvasX !in 0 until canvasWidth || canvasY !in 0 until canvasHeight) continue
        if (matrix[x, y]) pixels[canvasY * canvasWidth + canvasX] = BLACK
      }
    }
    return RGBLuminanceSource(canvasWidth, canvasHeight, pixels)
  }

  fun blankSource(width: Int, height: Int): LuminanceSource =
    RGBLuminanceSource(width, height, IntArray(width * height) { WHITE })

  /** Wraps [delegate] so [LuminanceSource.crop] is refused, like the ZXing base class. */
  fun uncroppable(delegate: LuminanceSource): LuminanceSource =
    object : LuminanceSource(delegate.width, delegate.height) {
      override fun getRow(y: Int, row: ByteArray?): ByteArray = delegate.getRow(y, row)
      override fun getMatrix(): ByteArray = delegate.matrix
      override fun isCropSupported(): Boolean = false
    }

  fun iosChromeHierarchy(deviceHeight: Int) = JvmTestHierarchies.iosChrome(deviceHeight)

  fun hierarchyWithoutChrome(deviceHeight: Int) = JvmTestHierarchies.noChrome(deviceHeight)

  private fun qrBufferedImage(content: String, size: Int) =
    JvmTestImageIo.fromMatrix(
      MultiFormatWriter().encode(
        content,
        BarcodeFormat.QR_CODE,
        size,
        size,
        mapOf(EncodeHintType.MARGIN to 2),
      ),
    )

  private const val WHITE = 0xFFFFFFFF.toInt()
  private const val BLACK = 0xFF000000.toInt()
}
