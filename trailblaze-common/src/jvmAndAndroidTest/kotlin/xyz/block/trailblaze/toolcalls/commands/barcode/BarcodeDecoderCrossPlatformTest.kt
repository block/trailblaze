package xyz.block.trailblaze.toolcalls.commands.barcode

import com.google.zxing.BarcodeFormat
import com.google.zxing.LuminanceSource
import com.google.zxing.NotFoundException
import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * [BarcodeDecoder]'s own logic — the symbology allow-list and the tile escalation — proven on
 * **both the JVM and Android**, from pixels rather than from encoded bytes.
 *
 * `BarcodeDecoderTest` in `jvmTest` covers what needs a real image codec: WebP/JPEG/PNG decoding
 * and the committed driver-encoded screenshot. Everything here is codec-free on purpose, because
 * the on-device Android agent runs this same decoder and nothing else exercises it there.
 */
class BarcodeDecoderCrossPlatformTest {

  @Test
  fun `a QR code decodes with the default formats`() {
    val source = BarcodeTestMatrices.sourceFrom(
      BarcodeTestMatrices.barcodePixels(QR_CONTENT, BarcodeFormat.QR_CODE),
    )

    assertThat(BarcodeDecoder.decode(source)).isEqualTo(QR_CONTENT)
  }

  @Test
  fun `a linear barcode is invisible by default and readable only when opted in`() {
    // The allow-list contract stated directly, with no reliance on compression noise: a clean,
    // unambiguous Code 128 that the 1D readers would trivially decode is still not returned,
    // because the default hints never let those readers run.
    val source = BarcodeTestMatrices.sourceFrom(
      BarcodeTestMatrices.barcodePixels(LINEAR_CONTENT, BarcodeFormat.CODE_128),
    )

    assertFailsWith<NotFoundException> { BarcodeDecoder.decode(source) }

    assertThat(
      BarcodeDecoder.decode(
        source,
        BarcodeDecoder.TWO_DIMENSIONAL_FORMATS + BarcodeDecoder.LINEAR_FORMATS,
      ),
    ).isEqualTo(LINEAR_CONTENT)
  }

  @Test
  fun `asking for no formats at all is rejected rather than silently scanning everything`() {
    val source = BarcodeTestMatrices.sourceFrom(
      BarcodeTestMatrices.barcodePixels(QR_CONTENT, BarcodeFormat.QR_CODE),
    )

    assertFailsWith<IllegalArgumentException> {
      BarcodeDecoder.decode(source, formats = emptyList())
    }
  }

  @Test
  fun `a QR code small inside a large screenshot is found by the tile escalation`() {
    // Reports how many regions were scanned, so this asserts the escalation actually ran rather
    // than that the answer happened to be right.
    val inset = BarcodeTestMatrices.inset(
      inner = BarcodeTestMatrices.barcodePixels(QR_CONTENT, BarcodeFormat.QR_CODE, 180, 180),
      canvasWidth = 900,
      canvasHeight = 1400,
    )
    var attempt: BarcodeDecoder.Attempt? = null

    val decoded = BarcodeDecoder.decode(BarcodeTestMatrices.sourceFrom(inset)) { attempt = it }

    assertThat(decoded).isEqualTo(QR_CONTENT)
    assertThat(attempt!!.regionsScanned >= 1).isEqualTo(true)
  }

  @Test
  fun `a source that cannot be cropped fails cleanly instead of throwing from crop`() {
    // LuminanceSource.crop throws UnsupportedOperationException from the base class, so the tile
    // pass has to check isCropSupported first. A blank uncroppable source is the case that would
    // reach it: nothing decodes full-image, and then tiling must not be attempted.
    val blank = UncroppableBlankSource(width = 300, height = 300)

    assertFailsWith<NotFoundException> { BarcodeDecoder.decode(blank) }
  }

  /** Reports `isCropSupported = false`, like ZXing's own base implementation. */
  private class UncroppableBlankSource(width: Int, height: Int) : LuminanceSource(width, height) {
    private val row = ByteArray(width) { WHITE_LUMINANCE }

    override fun getRow(y: Int, row: ByteArray?): ByteArray = this.row.copyOf()

    override fun getMatrix(): ByteArray = ByteArray(width * height) { WHITE_LUMINANCE }

    override fun isCropSupported(): Boolean = false

    private companion object {
      const val WHITE_LUMINANCE: Byte = -1
    }
  }

  private companion object {
    const val QR_CONTENT = "https://example.com/pair"
    const val LINEAR_CONTENT = "0123456789"
  }
}
