package xyz.block.trailblaze.api

import kotlin.test.Test
import kotlin.test.assertEquals

class ImageFormatDetectorTest {

  @Test
  fun `detectFormat returns PNG for PNG magic bytes`() {
    // PNG header: 89 50 4E 47 0D 0A 1A 0A
    val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    assertEquals(TrailblazeImageFormat.PNG, ImageFormatDetector.detectFormat(png))
  }

  @Test
  fun `detectFormat returns JPEG for JPEG magic bytes`() {
    // JPEG header: FF D8 FF E0 (JFIF) or FF D8 FF E1 (EXIF)
    val jpegJfif = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
    assertEquals(TrailblazeImageFormat.JPEG, ImageFormatDetector.detectFormat(jpegJfif))

    val jpegExif = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte())
    assertEquals(TrailblazeImageFormat.JPEG, ImageFormatDetector.detectFormat(jpegExif))
  }

  @Test
  fun `detectFormat returns WEBP for WebP magic bytes`() {
    // WebP header: RIFF????WEBP
    val webp = byteArrayOf(
      0x52, 0x49, 0x46, 0x46, // RIFF
      0x00, 0x00, 0x00, 0x00, // file size (don't care)
      0x57, 0x45, 0x42, 0x50, // WEBP
    )
    assertEquals(TrailblazeImageFormat.WEBP, ImageFormatDetector.detectFormat(webp))
  }

  @Test
  fun `detectFormat falls back to PNG for empty array`() {
    assertEquals(TrailblazeImageFormat.PNG, ImageFormatDetector.detectFormat(ByteArray(0)))
  }

  @Test
  fun `detectFormat falls back to PNG for short array`() {
    assertEquals(TrailblazeImageFormat.PNG, ImageFormatDetector.detectFormat(byteArrayOf(0x01, 0x02)))
  }

  @Test
  fun `detectFormat falls back to PNG for unrecognized format`() {
    val unknown = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B)
    assertEquals(TrailblazeImageFormat.PNG, ImageFormatDetector.detectFormat(unknown))
  }

  @Test
  fun `detectFormat returns WEBP only when both RIFF and WEBP markers present`() {
    // RIFF header but not WEBP at offset 8
    val riffNotWebp = byteArrayOf(
      0x52, 0x49, 0x46, 0x46, // RIFF
      0x00, 0x00, 0x00, 0x00,
      0x41, 0x56, 0x49, 0x20, // "AVI " instead of "WEBP"
    )
    assertEquals(TrailblazeImageFormat.PNG, ImageFormatDetector.detectFormat(riffNotWebp))
  }

  @Test
  fun `detectMimeTypeFromBase64 detects PNG`() {
    // Base64 of PNG header bytes: 89 50 4E 47 0D 0A 1A 0A 00 00 00 0D 49 48 44 52
    val pngBase64 = kotlin.io.encoding.Base64.encode(
      byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52)
    )
    assertEquals("image/png", ImageFormatDetector.detectMimeTypeFromBase64(pngBase64))
  }

  @Test
  fun `detectMimeTypeFromBase64 detects JPEG`() {
    val jpegBase64 = kotlin.io.encoding.Base64.encode(
      byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x00, 0x00, 0x01)
    )
    assertEquals("image/jpeg", ImageFormatDetector.detectMimeTypeFromBase64(jpegBase64))
  }

  @Test
  fun `detectMimeTypeFromBase64 detects WebP`() {
    val webpBase64 = kotlin.io.encoding.Base64.encode(
      byteArrayOf(0x52, 0x49, 0x46, 0x46, 0x24, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50, 0x56, 0x50, 0x38, 0x20)
    )
    assertEquals("image/webp", ImageFormatDetector.detectMimeTypeFromBase64(webpBase64))
  }

  @Test
  fun `detectMimeTypeFromBase64 falls back to PNG for invalid base64`() {
    assertEquals("image/png", ImageFormatDetector.detectMimeTypeFromBase64("!!!not-valid-base64!!!"))
  }

  @Test
  fun `detectMimeTypeFromBase64 falls back to PNG for empty string`() {
    assertEquals("image/png", ImageFormatDetector.detectMimeTypeFromBase64(""))
  }
}
