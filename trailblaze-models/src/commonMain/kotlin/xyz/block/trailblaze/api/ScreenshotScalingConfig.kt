package xyz.block.trailblaze.api

import kotlin.concurrent.Volatile
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
     * Fallback used in pure on-device contexts where no host config is forwarded
     * (e.g. on-device-only in-process execution on a remote device farm). When the host
     * drives the on-device agent over RPC, it forwards its effective config explicitly via
     * `GetScreenStateRequest.withScreenshotScalingConfig`, so this constant is bypassed.
     */
    val ON_DEVICE = DEFAULT
  }
}

/**
 * JVM-wide effective screenshot scaling config, populated from user settings at startup
 * and on every settings change. Host-side call sites that previously defaulted to
 * [ScreenshotScalingConfig.DEFAULT] use [effective] instead so a `trailblaze config
 * screenshot-format png` (etc.) takes effect without recompiling.
 *
 * When [override] is null (e.g. unit tests, on-device contexts that never call
 * [setEffectiveDefault]), [effective] falls back to [ScreenshotScalingConfig.DEFAULT].
 */
object EffectiveScreenshotScalingConfig {
  @Volatile private var override: ScreenshotScalingConfig? = null

  fun setEffectiveDefault(config: ScreenshotScalingConfig?) {
    override = config
  }

  val effective: ScreenshotScalingConfig
    get() = override ?: ScreenshotScalingConfig.DEFAULT

  /**
   * Resets the singleton to "no override" (so [effective] returns [ScreenshotScalingConfig.DEFAULT]).
   * Test-only hook so suites that touch `CliConfigHelper.readConfig()` /
   * `TrailblazeSettingsRepo`'s collector — both of which call [setEffectiveDefault] as a side
   * effect — can restore the JVM-wide singleton in `@After` and avoid leaking state into the
   * next test class in the same Gradle test JVM.
   */
  fun clearForTests() {
    override = null
  }
}
