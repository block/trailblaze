package xyz.block.trailblaze.host.devices

import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateResponse

/**
 * What the host learns about a device it drives over RPC: how to categorize it, and how big its
 * screen is.
 *
 * The two live together because one call answers both — the on-device screen-state probe reports
 * its classifiers and its pixel dimensions in the same response. Reading only the classifiers out
 * of that response is how the host came to describe every RPC-driven device as 0x0.
 */
data class HostDeviceProfile(
  val classifiers: List<TrailblazeDeviceClassifier>,
  val widthPixels: Int,
  val heightPixels: Int,
) {

  /**
   * This profile carrying the size the device just reported, or unchanged when that report has no
   * usable size.
   *
   * A session's profile is queried once at start, but a device's size does not stay put: rotating
   * it swaps the axes, and `TrailblazeDeviceInfo` derives orientation from `widthPixels >
   * heightPixels`. A start-of-session snapshot would therefore describe every log and every
   * `ctx.device` read after a `setOrientation` in the orientation the run began in. Every screen
   * state a device reports already carries its current size, so refreshing from those costs nothing.
   *
   * Classifiers are deliberately kept rather than re-read. They answer phone-vs-tablet, which keys
   * on `min(w, h)` and so survives rotation, and a response from an on-device server predating
   * classifier reporting carries none at all — taking them from here would erase them.
   */
  fun withMeasuredSizeFrom(response: GetScreenStateResponse): HostDeviceProfile =
    if (response.isMeasured()) {
      copy(widthPixels = response.deviceWidth, heightPixels = response.deviceHeight)
    } else {
      this
    }

  companion object {
    /**
     * Reported when nothing measured the screen. Not a size: `TrailblazeDeviceInfo` takes
     * non-null dimensions, so an unmeasured device still has to report something.
     */
    const val UNMEASURED = 0

    /**
     * Read a device's own account of itself out of a screen-state response.
     *
     * Two shapes are tolerated because both are real. An on-device server predating classifier
     * reporting sends `deviceClassifiers = null` while still sending true dimensions, so the
     * caller's classifier fallback must not also throw the size away.
     *
     * A partially-measured size (one axis positive, the other not) is discarded rather than
     * passed through, because dimensions are not just informational: `TrailblazeDeviceInfo`
     * derives orientation from `widthPixels > heightPixels`, so a 1080x0 would report a portrait
     * phone as LANDSCAPE. Unmeasured is a worse answer than measured and a better one than
     * confidently wrong.
     */
    fun fromScreenState(response: GetScreenStateResponse): HostDeviceProfile = HostDeviceProfile(
      classifiers = response.deviceClassifiers?.map { TrailblazeDeviceClassifier(it) } ?: emptyList(),
      widthPixels = if (response.isMeasured()) response.deviceWidth else UNMEASURED,
      heightPixels = if (response.isMeasured()) response.deviceHeight else UNMEASURED,
    )

    private fun GetScreenStateResponse.isMeasured() = deviceWidth > 0 && deviceHeight > 0

    /**
     * No device answered — neither axis is known.
     *
     * Callers pair this with a host-side classifier fallback
     * (`HostProbedDeviceClassifiers.forDevice`) whose probe does shell `wm size`, so it looks like
     * a measurement is being taken and thrown away. Borrowing it would be worse than reporting
     * nothing, for two reasons that both survive plumbing it through:
     *
     *  - **`wm size` reports the PANEL, not the current rotation.** Orientation is derived from
     *    `widthPixels > heightPixels`, so a landscape-held portrait panel would come back
     *    confidently PORTRAIT. That is the same class of wrong answer as the 0x0 this type exists
     *    to eliminate, only harder to spot because the numbers look plausible.
     *  - **That probe is cached for the process lifetime and never invalidated**, deliberately —
     *    it only has to answer phone-vs-tablet, which keys on `min(w, h)` and is rotation-stable.
     *    A size read from it could be arbitrarily old by the time a session reads it.
     *
     * So the failure path stays unmeasured on purpose. An honest zero is a value consumers can
     * degrade on; a stale panel size is one they can't tell from a real reading.
     */
    fun unknown() = HostDeviceProfile(
      classifiers = emptyList(),
      widthPixels = UNMEASURED,
      heightPixels = UNMEASURED,
    )
  }
}
