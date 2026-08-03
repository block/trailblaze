package xyz.block.trailblaze.host.devices

import maestro.DeviceInfo
import xyz.block.trailblaze.devices.TrailblazeAndroidDeviceCategory
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifiersProvider
import xyz.block.trailblaze.devices.TrailblazeDeviceOrientation
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.devices.TrailblazeIosDeviceCategory

class TrailblazeHostDeviceClassifier(
  private val trailblazeDriverType: TrailblazeDriverType,
  private val maestroDeviceInfoProvider: () -> DeviceInfo,
  // Android display density (dpi). When known, Android phone-vs-tablet is decided by
  // smallestWidthDp (density-independent) instead of raw pixels — see [isAndroidTablet]. iOS
  // ignores it. Null (density couldn't be probed) → the legacy pixel heuristic is used.
  private val androidDensityDpi: Int? = null,
  // Set when the provider's iOS dimensions are in points rather than pixels (e.g. the AXe
  // root-frame bounds a non-Maestro connected device reports). Points and pixels overlap across
  // the iPhone/iPad fleet (an iPad's 1024-point side sits inside the iPhone pixel range), so the
  // caller must say which unit it has — the iPhone/iPad split then uses the matching threshold.
  private val iosDimensionsInPoints: Boolean = false,
) : TrailblazeDeviceClassifiersProvider {

  private val trailblazeDeviceClassifiers: List<TrailblazeDeviceClassifier> by lazy {
    val initialMaestroDeviceInfo = maestroDeviceInfoProvider()
    val minPx = minOf(initialMaestroDeviceInfo.widthPixels, initialMaestroDeviceInfo.heightPixels)
    val isLandscape = initialMaestroDeviceInfo.widthPixels > initialMaestroDeviceInfo.heightPixels
    val orientation =
      if (isLandscape) TrailblazeDeviceOrientation.LANDSCAPE else TrailblazeDeviceOrientation.PORTRAIT

    buildList {
      val platform = trailblazeDriverType.platform
      add(platform.asTrailblazeDeviceClassifier())
      when (platform) {
        TrailblazeDevicePlatform.IOS -> {
          // iOS reports large pixel buffers regardless of point-scale, so the pixel threshold
          // separates iPhone from iPad cleanly. Point-derived dimensions use their own boundary.
          val iosTabletMin =
            if (iosDimensionsInPoints) IOS_TABLET_MIN_SHORTEST_SIDE_POINTS else TABLET_MIN_SHORTEST_SIDE_PX
          add(
            when (minPx >= iosTabletMin) {
              true -> TrailblazeIosDeviceCategory.IPAD.asTrailblazeDeviceClassifier()
              false -> TrailblazeIosDeviceCategory.IPHONE.asTrailblazeDeviceClassifier()
            },
          )
        }

        TrailblazeDevicePlatform.ANDROID -> {
          // Android tablet detection MUST be density-independent: two devices with the same pixel
          // buffer (e.g. a 1080px shortest side) can be a high-density phone or a low-density
          // tablet. The canonical Android threshold is smallestWidthDp >= 600 — the same rule the
          // on-device classifier (AndroidTrailblazeDeviceInfoUtil) uses — so host-side plan-time
          // classification agrees with what the device writes into session logs and recording
          // slots. Falls back to the pixel heuristic only when density couldn't be probed.
          add(
            when (isAndroidTablet(minPx, androidDensityDpi)) {
              true -> TrailblazeAndroidDeviceCategory.TABLET.asTrailblazeDeviceClassifier()
              false -> TrailblazeAndroidDeviceCategory.PHONE.asTrailblazeDeviceClassifier()
            },
          )
        }

        else -> {
          // Other platforms not supported at this point
        }
      }
    }
  }

  override fun getDeviceClassifiers(): List<TrailblazeDeviceClassifier> = trailblazeDeviceClassifiers

  companion object {
    /**
     * Shortest screen side, in pixels, at or above which a device is classified as a tablet / iPad.
     * Used for iOS, and as the Android fallback when display density is unknown. Also reused by
     * [HostIosDriverFactory]'s iPad auto-rotate check so the boundary lives in exactly one place.
     */
    const val TABLET_MIN_SHORTEST_SIDE_PX = 1536

    /**
     * Shortest screen side, in points, at or above which an iOS device is an iPad. Only used when
     * the caller says its dimensions are points ([iosDimensionsInPoints]). 600 sits between the
     * largest iPhone (~440pt shortest side, Pro Max) and the smallest iPad (744pt, iPad mini).
     */
    const val IOS_TABLET_MIN_SHORTEST_SIDE_POINTS = 600

    /**
     * smallestWidthDp at or above which an Android device is a tablet. 600dp is the canonical
     * Android window-size-class boundary (≈99.96% of phones are < 600dp in portrait) and matches
     * the on-device `AndroidTrailblazeDeviceInfoUtil` rule.
     */
    const val ANDROID_TABLET_MIN_SMALLEST_WIDTH_DP = 600

    /** Baseline density (mdpi) that defines 1dp = 1px; `DisplayMetrics.DENSITY_DEFAULT` on Android. */
    private const val DENSITY_DPI_BASELINE = 160

    /**
     * Android phone-vs-tablet test. Prefers the density-independent smallestWidthDp >= 600 rule
     * when [densityDpi] is known (`smallestWidthDp = shortestPx * 160 / densityDpi`); otherwise
     * falls back to the raw-pixel [TABLET_MIN_SHORTEST_SIDE_PX] heuristic. Pure + host-JVM safe so
     * it can be unit-tested without a device.
     */
    fun isAndroidTablet(shortestSidePx: Int, densityDpi: Int?): Boolean {
      if (densityDpi != null && densityDpi > 0) {
        val smallestWidthDp = shortestSidePx * DENSITY_DPI_BASELINE / densityDpi
        return smallestWidthDp >= ANDROID_TABLET_MIN_SMALLEST_WIDTH_DP
      }
      return shortestSidePx >= TABLET_MIN_SHORTEST_SIDE_PX
    }
  }
}
