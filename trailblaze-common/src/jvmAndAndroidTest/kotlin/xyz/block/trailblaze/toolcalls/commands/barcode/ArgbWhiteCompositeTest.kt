package xyz.block.trailblaze.toolcalls.commands.barcode

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Runs on **both the JVM and Android** (this source set feeds `jvmTest` and `androidUnitTest`),
 * which is the point: the Android screenshot loader is a `BitmapFactory` call plus this function,
 * and `BitmapFactory` is a throwing stub in an Android unit test. Extracting the arithmetic here
 * is what gives the on-device path real coverage without an emulator.
 */
class ArgbWhiteCompositeTest {

  @Test
  fun `a fully transparent pixel becomes opaque white rather than black`() {
    // The whole reason the function exists. Alpha 0 carries RGB 0, so without compositing a
    // transparent background reads as black and swallows the barcode.
    val pixels = intArrayOf(TRANSPARENT_BLACK)

    compositeOntoWhite(pixels)

    assertThat(pixels.single().toUInt().toString(16)).isEqualTo(OPAQUE_WHITE.toUInt().toString(16))
  }

  @Test
  fun `opaque pixels are left exactly as they were`() {
    val pixels = intArrayOf(OPAQUE_BLACK, OPAQUE_WHITE, OPAQUE_RED)
    val before = pixels.copyOf()

    compositeOntoWhite(pixels)

    assertThat(pixels.toList()).isEqualTo(before.toList())
  }

  @Test
  fun `a half transparent black lands mid grey and stays opaque`() {
    val pixels = intArrayOf(0x80.shl(24) or 0x000000)

    compositeOntoWhite(pixels)

    val alpha = pixels.single() ushr 24
    val red = pixels.single() shr 16 and 0xFF
    assertThat(alpha).isEqualTo(0xFF)
    // 0x80/0xFF of black over white ≈ half. Exact value is integer-truncated, so assert the band
    // rather than a magic number — the contract is "mid grey", not one specific rounding.
    assertThat(red in 0x7A..0x85).isEqualTo(true)
  }

  @Test
  fun `an empty array is a no-op rather than a crash`() {
    val pixels = IntArray(0)

    compositeOntoWhite(pixels)

    assertThat(pixels.size).isEqualTo(0)
  }

  @Test
  fun `a QR code on a transparent background only decodes after compositing`() {
    // The end-to-end statement of the bug: same pixels, and the only difference is the composite.
    // This is what makes the function load-bearing instead of merely correct arithmetic.
    val transparentBackground = BarcodeTestMatrices.qrPixelsOnTransparentBackground(QR_CONTENT)

    assertFailsWith<Exception> {
      BarcodeDecoder.decode(BarcodeTestMatrices.sourceFrom(transparentBackground))
    }

    val composited = transparentBackground.copyPixels().also { compositeOntoWhite(it.pixels) }
    assertThat(BarcodeDecoder.decode(BarcodeTestMatrices.sourceFrom(composited)))
      .isEqualTo(QR_CONTENT)
  }

  private companion object {
    const val QR_CONTENT = "https://example.com/pair"
    const val TRANSPARENT_BLACK = 0x00000000
    val OPAQUE_WHITE = 0xFFFFFFFF.toInt()
    val OPAQUE_BLACK = 0xFF000000.toInt()
    val OPAQUE_RED = 0xFFFF0000.toInt()
  }
}
