package xyz.block.trailblaze.playwright

import com.microsoft.playwright.Playwright
import com.microsoft.playwright.impl.PlaywrightImpl
import xyz.block.trailblaze.devices.ResolvedWebViewport
import xyz.block.trailblaze.devices.WebViewportSpec

/**
 * Resolves a [WebViewportSpec] to a [ResolvedWebViewport] using the running
 * Playwright instance's bundled device registry.
 *
 * Playwright Java doesn't expose `playwright.devices()` on the public [Playwright]
 * interface (unlike the JS / Python bindings); the device descriptors live behind
 * the impl-only [PlaywrightImpl.deviceDescriptors] call. We cast and read the
 * JSON array directly, mirroring the upstream
 * `com.microsoft.playwright.impl.junit.DeviceDescriptor.findByName` helper.
 *
 * **Internal-API dependency.** The shape we read here — a `JsonArray` of objects
 * with `name` + `descriptor` keys, the descriptor itself carrying `viewport`,
 * `deviceScaleFactor`, `userAgent`, `isMobile`, `hasTouch` — is undocumented and
 * tied to the Playwright Java version pinned in `libs.versions.toml`
 * (`playwright = "1.59.0"` at time of writing). A version bump that reshapes
 * `deviceDescriptors()` will silently break preset resolution; the
 * `PlaywrightDeviceRegistryTest` integration test pins this behavior so a shape
 * break fails CI immediately.
 *
 * Caching is unnecessary here — each `PlaywrightBrowserManager` resolves at most
 * once on init and the JSON array is small (a few kB).
 */
internal object PlaywrightDeviceRegistry {

  /**
   * Resolves [spec] against [playwright]'s bundled device registry, or returns
   * a desktop default when [spec] is null. Throws [IllegalArgumentException]
   * when a [WebViewportSpec.Preset] name doesn't match any known device —
   * giving the user a clean "unknown device" error at browser launch instead of
   * silently falling back to the default viewport.
   */
  fun resolve(
    playwright: Playwright,
    spec: WebViewportSpec?,
    defaultWidth: Int,
    defaultHeight: Int,
  ): ResolvedWebViewport = when (spec) {
    null -> ResolvedWebViewport(width = defaultWidth, height = defaultHeight)
    is WebViewportSpec.Dimensions -> ResolvedWebViewport(width = spec.width, height = spec.height)
    is WebViewportSpec.Preset -> resolvePreset(playwright, spec.name)
  }

  private fun resolvePreset(playwright: Playwright, name: String): ResolvedWebViewport {
    val descriptors = (playwright as PlaywrightImpl).deviceDescriptors()
    val match = descriptors.firstOrNull {
      val obj = it.asJsonObject
      obj.get("name")?.asString == name
    }?.asJsonObject?.getAsJsonObject("descriptor")
      ?: throw IllegalArgumentException(
        "Unknown Playwright device preset '$name'. Run a few known options: 'iPhone 14', " +
          "'Pixel 7', 'iPad Pro 11', or pass a raw 'WIDTHxHEIGHT' (e.g. '375x812') instead.",
      )

    // Both "registry is malformed" failures throw IllegalArgumentException so a single
    // catch on the CLI / UI side (the `--emulate <typo>` path) can format them the
    // same way as the "unknown preset" case — see DeviceCreateWebCommand.
    val viewport = match.getAsJsonObject("viewport")
      ?: throw IllegalArgumentException(
        "Device '$name' is missing 'viewport' — Playwright device registry is malformed.",
      )
    val width = viewport.get("width")?.asInt
      ?: throw IllegalArgumentException(
        "Device '$name' is missing viewport.width — Playwright device registry is malformed.",
      )
    val height = viewport.get("height")?.asInt
      ?: throw IllegalArgumentException(
        "Device '$name' is missing viewport.height — Playwright device registry is malformed.",
      )

    return ResolvedWebViewport(
      width = width,
      height = height,
      deviceScaleFactor = match.get("deviceScaleFactor")?.takeIf { !it.isJsonNull }?.asDouble,
      userAgent = match.get("userAgent")?.takeIf { !it.isJsonNull }?.asString,
      isMobile = match.get("isMobile")?.takeIf { !it.isJsonNull }?.asBoolean,
      hasTouch = match.get("hasTouch")?.takeIf { !it.isJsonNull }?.asBoolean,
      presetName = name,
    )
  }
}
