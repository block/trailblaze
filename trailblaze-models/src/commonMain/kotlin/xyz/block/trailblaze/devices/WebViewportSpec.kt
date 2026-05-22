package xyz.block.trailblaze.devices

import kotlinx.serialization.Serializable

/**
 * User-facing string spec for the web browser viewport / device emulation.
 *
 * Two surface forms, both accepted everywhere a viewport can be specified
 * (the `trailblaze device create web --emulate / --viewport` CLI flags, the
 * `web_resize` tool's resolved dimensions, and the desktop app's viewport
 * picker):
 *
 *  1. A Playwright `devices` registry name — e.g. `"iPhone 14"`, `"Pixel 7"`,
 *     `"iPad Pro 11"`. The name is resolved against Playwright's bundled device
 *     registry at browser launch time (see `PlaywrightDeviceRegistry`), which
 *     yields viewport size, deviceScaleFactor, userAgent, isMobile, hasTouch.
 *
 *  2. Raw `WIDTHxHEIGHT` — e.g. `"375x812"`. Sets only the viewport box; the
 *     remaining context options (User-Agent, deviceScaleFactor, isMobile,
 *     hasTouch) follow Trailblaze / Playwright defaults — including the
 *     headed-vs-headless `deviceScaleFactor` heuristic in
 *     `PlaywrightBrowserManager` for raw-dimension specs.
 *
 * Parsing here is purely shape-validation. It DOES NOT confirm that a preset name
 * exists in Playwright's registry — that check happens at browser launch where a
 * Playwright instance is available. A typo is surfaced with a clear error there.
 */
sealed interface WebViewportSpec {

  /** A Playwright `devices` registry preset (e.g. `"iPhone 14"`). */
  data class Preset(val name: String) : WebViewportSpec

  /** A raw viewport dimensions pair. */
  data class Dimensions(val width: Int, val height: Int) : WebViewportSpec

  companion object {
    /**
     * Parses [spec] into either a [Dimensions] (if it matches `<width>x<height>`
     * with positive integers) or a [Preset] (any other non-blank string).
     *
     * Returns `null` for blank input. Throws [IllegalArgumentException] when the
     * input *looks* like raw dimensions (contains `x`, both sides numeric) but
     * either dimension is zero or negative — that's a typo, not a preset name.
     */
    fun parse(spec: String?): WebViewportSpec? {
      val trimmed = spec?.trim().orEmpty()
      if (trimmed.isEmpty()) return null
      val dimensions = tryParseDimensions(trimmed)
      if (dimensions != null) return dimensions
      // Bare numbers like "1280" or "x800" never make sense — surface as an error
      // instead of silently looking the string up as a preset name.
      if (LOOKS_LIKE_RAW_DIMENSIONS.matches(trimmed)) {
        throw IllegalArgumentException(
          "Invalid viewport '$spec'. Expected '<width>x<height>' with positive integers, " +
            "e.g. '375x812' — or a Playwright preset name (e.g. 'iPhone 14').",
        )
      }
      return Preset(trimmed)
    }

    /**
     * Matches both well-formed dimensions and obviously-broken dimension-shaped input
     * (`"0x800"`, `"375x"`, `"x812"`). Used to distinguish "user typo" from
     * "preset name with an 'x' in it".
     */
    private val LOOKS_LIKE_RAW_DIMENSIONS = Regex("^[0-9]*x[0-9]*$", RegexOption.IGNORE_CASE)

    private val RAW_DIMENSIONS = Regex("^([1-9][0-9]*)x([1-9][0-9]*)$", RegexOption.IGNORE_CASE)

    private fun tryParseDimensions(spec: String): Dimensions? {
      val match = RAW_DIMENSIONS.matchEntire(spec) ?: return null
      val (w, h) = match.destructured
      return Dimensions(w.toInt(), h.toInt())
    }
  }
}

/**
 * Concrete, resolved viewport — what `Playwright.Browser.NewContextOptions` is
 * actually configured with. Serializable so it can be logged into session
 * metadata and round-tripped over the daemon RPC if needed.
 *
 * Built either by raw dimensions or by resolving a Playwright preset name; in
 * the latter case [presetName] records the original input so logs and the
 * desktop UI can show "Device 375x812 · iPhone 14" instead of just the size.
 */
@Serializable
data class ResolvedWebViewport(
  val width: Int,
  val height: Int,
  val deviceScaleFactor: Double? = null,
  val userAgent: String? = null,
  val isMobile: Boolean? = null,
  val hasTouch: Boolean? = null,
  /** Original preset name when this was resolved from a Playwright preset; null for raw dimensions. */
  val presetName: String? = null,
)
