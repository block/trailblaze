package xyz.block.trailblaze.api

import kotlinx.serialization.Serializable

/**
 * Configuration for screenshot scaling.
 *
 * Screenshots will be scaled down (never up) to fit within the specified dimensions
 * while maintaining aspect ratio.
 *
 * See https://platform.openai.com/docs/guides/images-vision?api-mode=responses
 *
 * @property maxDimension1 Maximum dimension for the longer side
 * @property maxDimension2 Maximum dimension for the shorter side
 * @property imageFormat Format for the screenshot output (PNG, JPEG, or WEBP)
 * @property compressionQuality Compression quality (0.0 to 1.0). Only applicable for lossy formats (JPEG, WEBP).
 */
@Serializable
data class ScreenshotScalingConfig(
  val maxDimension1: Int = 1536,
  val maxDimension2: Int = 768,
  val imageFormat: TrailblazeImageFormat = TrailblazeImageFormat.WEBP,
  val compressionQuality: Float = 0.80f,
) {

  /**
   * Computes the scaled image dimensions that would result from applying this config
   * to an image with the given original dimensions.
   *
   * This is a pure computation mirroring the scaling logic in BufferedImageUtils.scale()
   * and AndroidBitmapUtils.scale(). Images are only scaled down, never up.
   *
   * @return Pair of (scaledWidth, scaledHeight)
   */
  fun computeScaledDimensions(originalWidth: Int, originalHeight: Int): Pair<Int, Int> {
    val targetLong = maxOf(maxDimension1, maxDimension2)
    val targetShort = minOf(maxDimension1, maxDimension2)
    val imageLong = maxOf(originalWidth, originalHeight)
    val imageShort = minOf(originalWidth, originalHeight)

    if (imageLong <= targetLong && imageShort <= targetShort) {
      return Pair(originalWidth, originalHeight)
    }

    val scaleLong = targetLong.toFloat() / imageLong
    val scaleShort = targetShort.toFloat() / imageShort
    val scaleAmount = minOf(scaleLong, scaleShort)

    return Pair(
      (originalWidth * scaleAmount).toInt(),
      (originalHeight * scaleAmount).toInt(),
    )
  }

  companion object {
    /**
     * Default screenshot scaling configuration.
     * Images will be scaled to fit within the max dimensions while maintaining aspect ratio.
     * Uses WebP lossy format with 80% compression quality — ~30% smaller than JPEG at
     * equivalent visual quality. Supported by all LLM vision providers (OpenAI, Anthropic,
     * Google), all modern browsers, Skia (JVM host via Compose Desktop), and Android natively.
     */
    val DEFAULT = ScreenshotScalingConfig()

    /**
     * Alias for [DEFAULT]. On-device in-process execution (e.g. remote device farm) uses the
     * same config as host — WebP at 1536x768. Kept as a named constant for clarity at call
     * sites where the on-device context matters.
     */
    val ON_DEVICE = DEFAULT
  }
}
