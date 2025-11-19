package xyz.block.trailblaze.api

/**
 * Utility for detecting image formats from byte arrays.
 */
object ImageFormatDetector {

  /**
   * Detects the image format from the byte array by examining the file header (magic numbers).
   *
   * @param bytes The byte array to examine
   * @return The detected [TrailblazeImageFormat] (PNG, JPEG, or PNG as fallback)
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

    // Default to PNG if format cannot be determined
    return TrailblazeImageFormat.PNG
  }
}
