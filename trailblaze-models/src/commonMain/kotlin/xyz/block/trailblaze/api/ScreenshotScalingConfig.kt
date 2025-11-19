package xyz.block.trailblaze.api

/**
 * Configuration for screenshot scaling.
 *
 * Screenshots will be scaled down (never up) to fit within the specified dimensions
 * while maintaining aspect ratio.
 *
 * See https://platform.openai.com/docs/guides/images-vision?api-mode=responses
 *
 * @property maxDimension1 Maximum dimension for the longer side (typically 768)
 * @property maxDimension2 Maximum dimension for the shorter side (typically 512)
 * @property imageFormat Format for the screenshot output ("PNG" or "JPEG")
 * @property compressionQuality Compression quality (0.0 to 1.0). Only applicable for JPEG format.
 */
data class ScreenshotScalingConfig(
  val maxDimension1: Int = 1024,
  val maxDimension2: Int = 512,
  val imageFormat: TrailblazeImageFormat = TrailblazeImageFormat.JPEG,
  val compressionQuality: Float = 0.80f,
) {

  companion object {
    /**
     * Default screenshot scaling configuration.
     * Images will be scaled to fit within the max dimensions while maintaining aspect ratio.
     * Uses PNG format with no compression quality setting.
     */
    val DEFAULT = ScreenshotScalingConfig()
  }
}
