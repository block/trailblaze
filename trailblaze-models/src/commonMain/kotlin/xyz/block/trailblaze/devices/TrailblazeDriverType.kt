package xyz.block.trailblaze.devices

enum class TrailblazeDriverType(
  val platform: TrailblazeDevicePlatform,
  val isHost: Boolean,
) {
  ANDROID_ONDEVICE_ACCESSIBILITY(
    platform = TrailblazeDevicePlatform.ANDROID,
    isHost = false,
  ),
  ANDROID_ONDEVICE_INSTRUMENTATION(
    platform = TrailblazeDevicePlatform.ANDROID,
    isHost = false,
  ),
  ANDROID_HOST(
    platform = TrailblazeDevicePlatform.ANDROID,
    isHost = true,
  ),
  IOS_HOST(
    platform = TrailblazeDevicePlatform.IOS,
    isHost = true,
  ),
  PLAYWRIGHT_NATIVE(
    platform = TrailblazeDevicePlatform.WEB,
    isHost = true,
  ),
  PLAYWRIGHT_ELECTRON(
    platform = TrailblazeDevicePlatform.WEB,
    isHost = true,
  ),
  // COMPOSE intentionally uses WEB platform: Compose Desktop testing reuses the web
  // platform's view hierarchy filtering and device infrastructure. Adding a separate
  // DESKTOP platform would require updating all exhaustive `when` expressions on
  // TrailblazeDevicePlatform across the codebase (e.g., ViewHierarchyFilter.create).
  COMPOSE(
    platform = TrailblazeDevicePlatform.WEB,
    isHost = true,
  ),
  ;

  companion object {
    val ANDROID_ON_DEVICE_DRIVER_TYPES = setOf(
      ANDROID_ONDEVICE_INSTRUMENTATION,
      ANDROID_ONDEVICE_ACCESSIBILITY,
    )
  }
}
