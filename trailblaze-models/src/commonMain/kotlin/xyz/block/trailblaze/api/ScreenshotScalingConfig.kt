package xyz.block.trailblaze.api

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
data class ScreenshotScalingConfig(
  val maxDimension1: Int = 1536,
  val maxDimension2: Int = 768,
  val imageFormat: TrailblazeImageFormat = TrailblazeImageFormat.WEBP,
  val compressionQuality: Float = 0.80f,
) {

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
