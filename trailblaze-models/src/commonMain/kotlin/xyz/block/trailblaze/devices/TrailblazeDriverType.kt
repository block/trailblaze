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
  /**
   * Short identifier users type at the CLI to select this driver, e.g.
   * `trailblaze config android-driver accessibility`. `null` for drivers that aren't
   * user-selectable as a per-platform override (web drivers, Revyl cloud drivers). Kept
   * distinct from [yamlKey] because the CLI form drops the platform prefix — you already
   * know the platform from the config key (`android-driver` vs. `ios-driver`).
   */
  val cliShortName: String?,
) {
  ANDROID_ONDEVICE_ACCESSIBILITY(
    platform = TrailblazeDevicePlatform.ANDROID,
    requiresHost = false,
    yamlKey = "android-ondevice-accessibility",
    cliShortName = "accessibility",
  ),
  ANDROID_ONDEVICE_INSTRUMENTATION(
    platform = TrailblazeDevicePlatform.ANDROID,
    requiresHost = false,
    yamlKey = "android-ondevice-instrumentation",
    cliShortName = "instrumentation",
  ),
  IOS_HOST(
    platform = TrailblazeDevicePlatform.IOS,
    requiresHost = true,
    yamlKey = "ios-host",
    cliShortName = "host",
  ),
  IOS_AXE(
    platform = TrailblazeDevicePlatform.IOS,
    requiresHost = true,
    yamlKey = "ios-axe",
    cliShortName = "axe",
  ),
  PLAYWRIGHT_NATIVE(
    platform = TrailblazeDevicePlatform.WEB,
    requiresHost = true,
    yamlKey = "playwright-native",
    cliShortName = null,
  ),
  PLAYWRIGHT_ELECTRON(
    platform = TrailblazeDevicePlatform.WEB,
    requiresHost = true,
    yamlKey = "playwright-electron",
    cliShortName = null,
  ),
  REVYL_ANDROID(
    platform = TrailblazeDevicePlatform.ANDROID,
    requiresHost = true,
    yamlKey = "revyl-android",
    cliShortName = null,
  ),
  REVYL_IOS(
    platform = TrailblazeDevicePlatform.IOS,
    requiresHost = true,
    yamlKey = "revyl-ios",
    cliShortName = null,
  ),
  // COMPOSE intentionally uses WEB platform: Compose Desktop testing reuses the web
  // platform's view hierarchy filtering and device infrastructure. Adding a separate
  // DESKTOP platform would require updating all exhaustive `when` expressions on
  // TrailblazeDevicePlatform across the codebase (e.g., ViewHierarchyFilter.create).
  COMPOSE(
    platform = TrailblazeDevicePlatform.WEB,
    requiresHost = true,
    yamlKey = "compose",
    cliShortName = null,
  ),
  ;

  companion object {
    val DEFAULT_ANDROID = ANDROID_ONDEVICE_INSTRUMENTATION
    val DEFAULT_IOS = IOS_HOST

    val ANDROID_ON_DEVICE_DRIVER_TYPES = setOf(
      ANDROID_ONDEVICE_INSTRUMENTATION,
      ANDROID_ONDEVICE_ACCESSIBILITY,
    )

    /**
     * The driver type used when the user hasn't set an explicit per-platform override.
     * Returns `null` for platforms that don't have a user-togglable default (e.g. `WEB`).
     */
    fun defaultForPlatform(platform: TrailblazeDevicePlatform): TrailblazeDriverType? =
      when (platform) {
        TrailblazeDevicePlatform.ANDROID -> DEFAULT_ANDROID
        TrailblazeDevicePlatform.IOS -> DEFAULT_IOS
        else -> null
      }

    /**
     * Drivers that the CLI exposes as a per-platform override via `config <platform>-driver`.
     * Determined by [cliShortName] being non-null, so adding a new user-selectable driver to
     * the enum automatically surfaces it in the CLI — no second list to keep in sync.
     */
    fun selectableForPlatform(platform: TrailblazeDevicePlatform): List<TrailblazeDriverType> =
      entries.filter { it.platform == platform && it.cliShortName != null }

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
