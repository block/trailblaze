package xyz.block.trailblaze.model

import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.model.TrailblazeOnDeviceInstrumentationTarget.Companion.DEFAULT_ANDROID_ON_DEVICE
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import kotlin.reflect.KClass


abstract class TrailblazeHostAppTarget(
  /**
   * Used for artifact naming, CI builds and other persistent identifiers
   *
   * NOTE: Must be lowercase alphanumeric, no spaces or special characters
   */
  val id: String,

  /**
   * Human-readable name for display in the UI
   */
  val displayName: String,
) {

  init {
    // Validation to ensure key matches requirements
    require(id.matches(Regex("^[a-z0-9]+$"))) {
      "ID (${id}) for $displayName must be lowercase alphanumeric, no spaces or special characters"
    }
  }

  abstract fun getPossibleAppIdsForPlatform(platform: TrailblazeDevicePlatform): Set<String>?

  protected abstract fun internalGetCustomToolsForDriver(driverType: TrailblazeDriverType): Set<KClass<out TrailblazeTool>>
  fun getCustomToolsForDriver(driverType: TrailblazeDriverType): Set<KClass<out TrailblazeTool>> =
    internalGetCustomToolsForDriver(driverType)

  /**
   * Override this to exclude specific tools from the default tool set.
   * This is useful when you want to replace a default tool with a custom implementation.
   */
  open fun getExcludedToolsForDriver(driverType: TrailblazeDriverType): Set<KClass<out TrailblazeTool>> =
    emptySet()

  fun getAllCustomToolClassesForSerialization(): Set<KClass<out TrailblazeTool>> =
    TrailblazeDriverType.entries.flatMap { trailblazeDriverType ->
      getCustomToolsForDriver(trailblazeDriverType)
    }.toSet()

  fun internalGetAndroidOnDeviceTarget(): TrailblazeOnDeviceInstrumentationTarget {
    return DEFAULT_ANDROID_ON_DEVICE
  }

  /**
   * We're provided with the original iOS Driver from Maestro
   * Then we are instantiating our custom Square Driver, wrapped around the original
   *
   * @param originalIosDriver is actually of type "IOSDriver" and is provided by Maestro.
   *   NOTE: It is typed as [Any] because it's in KMP code and Maestro is JVM Only.
   * @return Return the original [originalIosDriver] or your custom "IOSDriver"
   */
  open fun getCustomIosDriverFactory(trailblazeDeviceId: TrailblazeDeviceId, originalIosDriver: Any): Any =
    originalIosDriver

  fun getTrailblazeOnDeviceInstrumentationTarget(): TrailblazeOnDeviceInstrumentationTarget =
    internalGetAndroidOnDeviceTarget() ?: TrailblazeOnDeviceInstrumentationTarget.DEFAULT_ANDROID_ON_DEVICE

  /**
   * Returns comprehensive information about this app target as formatted text including:
   * - Driver types with their platforms and custom tool counts
   * - Installed app IDs for all platforms
   */
  fun getAppInfoText(supportedDrivers: Set<TrailblazeDriverType>): String = buildString {
    // Print installed app information
    appendLine("Apps Ids by Platform:")
    appendLine("-".repeat(40))

    TrailblazeDevicePlatform.entries.forEach { platform ->
      val appIds = getPossibleAppIdsForPlatform(platform)
      if (!appIds.isNullOrEmpty()) {
        appendLine("• ${platform.displayName}: ${appIds.joinToString(",")}")
      }
    }

    // Print Android on-device target information
    appendLine("\nAndroid On-Device Target:")
    appendLine("-".repeat(40))
    val androidTarget = getTrailblazeOnDeviceInstrumentationTarget()
    appendLine("• Test App ID: ${androidTarget.testAppId}")
    appendLine("• Test Class: ${androidTarget.fqTestName}")
  }

  data object DefaultTrailblazeHostAppTarget : TrailblazeHostAppTarget(
    id = "none",
    displayName = "None",
  ) {
    override fun getPossibleAppIdsForPlatform(platform: TrailblazeDevicePlatform): Set<String>? = null

    override fun internalGetCustomToolsForDriver(driverType: TrailblazeDriverType): Set<KClass<out TrailblazeTool>> =
      setOf()
  }

  fun getAppIdIfInstalled(
    platform: TrailblazeDevicePlatform,
    installedAppIds: Set<String>
  ): String? {
    val installedAppId = getPossibleAppIdsForPlatform(platform)?.let { expectedAppIds ->
      expectedAppIds.firstOrNull { expectedAppId ->
        installedAppIds.contains(expectedAppId)
      }
    }
    return installedAppId
  }

  /**
   * Formats the version information for display in the UI.
   *
   * Override this in app-specific implementations to provide human-readable version strings.
   * For example, Square might format "6940515" as "6.94 (build 6515)".
   *
   * @param platform The platform (ANDROID or IOS)
   * @param versionInfo The raw version information from the device
   * @return A formatted string for display, or null to use the default formatting
   */
  open fun formatVersionInfo(platform: TrailblazeDevicePlatform, versionInfo: AppVersionInfo): String? = null

  /**
   * Returns the minimum required build number/version code for this app target on the given platform.
   *
   * If the installed app version is below this minimum, a warning should be displayed to the user.
   * This is useful for warning about breaking API changes that require newer app builds.
   *
   * @param platform The platform (ANDROID or IOS)
   * @return The minimum build number as a string, or null if no minimum is required
   */
  open fun getMinBuildVersion(platform: TrailblazeDevicePlatform): String? = null

  /**
   * Returns a warning message to display when the installed app version is below the minimum.
   *
   * @param platform The platform (ANDROID or IOS)
   * @param installedVersion The version info of the installed app
   * @param minVersion The minimum required version
   * @return A warning message, or null to use the default message
   */
  open fun getVersionWarningMessage(
    platform: TrailblazeDevicePlatform,
    installedVersion: AppVersionInfo,
    minVersion: String,
  ): String? = null

  /**
   * Checks if the installed version meets the minimum requirements.
   *
   * @param platform The platform (ANDROID or IOS)
   * @param versionInfo The version info of the installed app
   * @return true if the version is acceptable, false if it's below minimum
   */
  open fun isVersionAcceptable(platform: TrailblazeDevicePlatform, versionInfo: AppVersionInfo): Boolean {
    val minVersion = getMinBuildVersion(platform) ?: return true

    // For iOS, compare buildNumber (SQBuildNumber) if available, otherwise versionCode
    // For Android, compare versionCode
    val installedVersion = when (platform) {
      TrailblazeDevicePlatform.IOS -> versionInfo.buildNumber ?: versionInfo.versionCode
      TrailblazeDevicePlatform.ANDROID -> versionInfo.versionCode
      else -> return true
    }

    return try {
      installedVersion.toLong() >= minVersion.toLong()
    } catch (e: NumberFormatException) {
      // If versions aren't numeric, do string comparison
      installedVersion >= minVersion
    }
  }
}

