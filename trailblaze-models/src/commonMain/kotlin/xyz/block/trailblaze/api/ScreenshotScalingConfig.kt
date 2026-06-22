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

    /**
     * Web/desktop-tuned default.
     *
     * [DEFAULT]'s 768 short-side cap is tuned for portrait phones, where the short side is
     * the device's *width*. Web (and Electron) is captured in landscape, so that same cap
     * clamps the *height* instead and crushes vertical detail — a 2560×1600 dev capture lands
     * at ~1228×768, which is what makes web screenshots look grainy in reports. Web gets a
     * larger short-side budget so landscape height keeps its detail: a 16:10 capture now lands
     * at 1536×960 instead of 1228×768. The long-side cap stays at 1536 (≈ the 1568px long-edge
     * vision guidance), so per-step LLM image token cost only rises modestly. WebP quality is
     * bumped from 0.80 → 0.90 so the encoder stops introducing the blocky compression artifacts
     * that read as "mush" on small UI text — a quality change only, with no effect on dimensions
     * (and therefore none on vision token cost).
     */
    val WEB = ScreenshotScalingConfig(
      maxDimension1 = 1536,
      maxDimension2 = 1024,
      compressionQuality = 0.90f,
    )
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
   * Effective scaling config for the web/Playwright path. When the user has set **no** screenshot
   * overrides ([override] is null), the landscape-friendly [ScreenshotScalingConfig.WEB] default is
   * used instead of the mobile-tuned [ScreenshotScalingConfig.DEFAULT] — web is captured in
   * landscape and DEFAULT's 768 short-side cap crushes its height (see [ScreenshotScalingConfig.WEB]).
   *
   * The decision keys on whether an override was *set* — the host passes `null` to
   * [setEffectiveDefault] when the user customized nothing (see
   * `TrailblazeServerState.screenshotScalingConfigOrNull`) — **not** on value-equality with
   * [ScreenshotScalingConfig.DEFAULT]. So an explicit user config wins unchanged even when it
   * happens to equal the framework defaults (e.g. a downstream distro pinning the defaults).
   */
  val effectiveForWeb: ScreenshotScalingConfig
    get() = override ?: ScreenshotScalingConfig.WEB

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
