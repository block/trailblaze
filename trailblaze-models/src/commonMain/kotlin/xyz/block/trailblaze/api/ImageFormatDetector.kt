package xyz.block.trailblaze.api

/**
 * Utility for detecting image formats from byte arrays.
 */
object ImageFormatDetector {

  /**
   * Detects the image format from the byte array by examining the file header (magic numbers).
   *
   * @param bytes The byte array to examine
   * @return The detected [TrailblazeImageFormat] (PNG, JPEG, WEBP, or PNG as fallback)
   */
  fun detectFormat(bytes: ByteArray): TrailblazeImageFormat {
    if (bytes.size < 4) return TrailblazeImageFormat.PNG // Default to PNG if not enough bytes

    // Check for PNG signature: 89 50 4E 47 (0x89 'P' 'N' 'G')
    if (bytes[0] == 0x89.toByte() &&
      bytes[1] == 0x50.toByte() &&
      bytes[2] == 0x4E.toByte() &&
      bytes[3] == 0x47.toByte()
    ) {
      return TrailblazeImageFormat.PNG
    }

    // Check for JPEG signature: FF D8 FF
    if (bytes[0] == 0xFF.toByte() &&
      bytes[1] == 0xD8.toByte() &&
      bytes[2] == 0xFF.toByte()
    ) {
      return TrailblazeImageFormat.JPEG
    }

    // Check for WebP signature: RIFF....WEBP (bytes 0-3 = "RIFF", bytes 8-11 = "WEBP")
    if (bytes.size >= 12 &&
      bytes[0] == 0x52.toByte() && // R
      bytes[1] == 0x49.toByte() && // I
      bytes[2] == 0x46.toByte() && // F
      bytes[3] == 0x46.toByte() && // F
      bytes[8] == 0x57.toByte() && // W
      bytes[9] == 0x45.toByte() && // E
      bytes[10] == 0x42.toByte() && // B
      bytes[11] == 0x50.toByte() // P
    ) {
      return TrailblazeImageFormat.WEBP
    }

    // Default to PNG if format cannot be determined
    return TrailblazeImageFormat.PNG
  }

  /**
   * Detects the MIME type from a base64-encoded image string by decoding just enough
   * bytes to check the magic number header.
   */
  fun detectMimeTypeFromBase64(base64Data: String): String {
    return try {
      // Decode just the first 16 bytes (base64 encodes 3 bytes per 4 chars, so 24 chars is enough)
      val prefix = base64Data.take(24)
      val bytes = kotlin.io.encoding.Base64.decode(prefix)
      detectFormat(bytes).mimeType
    } catch (_: Exception) {
      TrailblazeImageFormat.PNG.mimeType // fallback
    }
  }
}
