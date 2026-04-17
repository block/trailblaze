package xyz.block.trailblaze.devices

enum class TrailblazeDriverType(
  val platform: TrailblazeDevicePlatform,
  /**
   * Whether this driver requires a host machine to operate. Drivers with `requiresHost = false`
   * (e.g., on-device Android drivers) can run autonomously on the device via RPC; drivers with
   * `requiresHost = true` need a host-resident process (Maestro, Playwright, Revyl API, etc.).
   */
  val requiresHost: Boolean,
  /**
   * The YAML key used to reference this specific driver type in `trailblaze-config/` YAML files
   * (targets, toolsets). Case-insensitive. Matches the keys in [DriverTypeKey].
   */
  val yamlKey: String,
) {
  ANDROID_ONDEVICE_ACCESSIBILITY(
    platform = TrailblazeDevicePlatform.ANDROID,
    requiresHost = false,
    yamlKey = "android-ondevice-accessibility",
  ),
  ANDROID_ONDEVICE_INSTRUMENTATION(
    platform = TrailblazeDevicePlatform.ANDROID,
    requiresHost = false,
    yamlKey = "android-ondevice-instrumentation",
  ),
  IOS_HOST(
    platform = TrailblazeDevicePlatform.IOS,
    requiresHost = true,
    yamlKey = "ios-host",
  ),
  PLAYWRIGHT_NATIVE(
    platform = TrailblazeDevicePlatform.WEB,
    requiresHost = true,
    yamlKey = "playwright-native",
  ),
  PLAYWRIGHT_ELECTRON(
    platform = TrailblazeDevicePlatform.WEB,
    requiresHost = true,
    yamlKey = "playwright-electron",
  ),
  REVYL_ANDROID(
    platform = TrailblazeDevicePlatform.ANDROID,
    requiresHost = true,
    yamlKey = "revyl-android",
  ),
  REVYL_IOS(
    platform = TrailblazeDevicePlatform.IOS,
    requiresHost = true,
    yamlKey = "revyl-ios",
  ),
  // COMPOSE intentionally uses WEB platform: Compose Desktop testing reuses the web
  // platform's view hierarchy filtering and device infrastructure. Adding a separate
  // DESKTOP platform would require updating all exhaustive `when` expressions on
  // TrailblazeDevicePlatform across the codebase (e.g., ViewHierarchyFilter.create).
  COMPOSE(
    platform = TrailblazeDevicePlatform.WEB,
    requiresHost = true,
    yamlKey = "compose",
  ),
  ;

  companion object {
    val DEFAULT_ANDROID_ON_DEVICE = ANDROID_ONDEVICE_INSTRUMENTATION

    val ANDROID_ON_DEVICE_DRIVER_TYPES = setOf(
      ANDROID_ONDEVICE_INSTRUMENTATION,
      ANDROID_ONDEVICE_ACCESSIBILITY,
    )

    /** Legacy aliases for removed driver types. */
    private val LEGACY_ALIASES = mapOf(
      "ANDROID_HOST" to ANDROID_ONDEVICE_INSTRUMENTATION,
      "HOST" to ANDROID_ONDEVICE_INSTRUMENTATION,
    )

    fun fromString(value: String): TrailblazeDriverType? =
      entries.find { it.name.equals(value, ignoreCase = true) }
        ?: LEGACY_ALIASES.entries.firstOrNull { it.key.equals(value, ignoreCase = true) }?.value
  }
}
