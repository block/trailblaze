package xyz.block.trailblaze.revyl

/**
 * Default screen dimensions for Revyl cloud devices.
 *
 * These are fallback values used when the session does not report
 * screen dimensions. They represent the native resolution of the
 * default device provisioned by the Revyl backend for each platform.
 */
object RevylDefaults {

  /** iPhone 16 Pro native resolution (logical points x 3). */
  const val IOS_DEFAULT_WIDTH = 1170
  const val IOS_DEFAULT_HEIGHT = 2532

  /** Pixel 7 native resolution. */
  const val ANDROID_DEFAULT_WIDTH = 1080
  const val ANDROID_DEFAULT_HEIGHT = 2400

  /**
   * Returns the default (width, height) pair for the given platform string.
   *
   * @param platform "ios" or "android".
   * @return Default dimensions as a Pair.
   */
  fun dimensionsForPlatform(platform: String): Pair<Int, Int> = when (platform.lowercase()) {
    RevylCliClient.PLATFORM_IOS -> Pair(IOS_DEFAULT_WIDTH, IOS_DEFAULT_HEIGHT)
    else -> Pair(ANDROID_DEFAULT_WIDTH, ANDROID_DEFAULT_HEIGHT)
  }
}
