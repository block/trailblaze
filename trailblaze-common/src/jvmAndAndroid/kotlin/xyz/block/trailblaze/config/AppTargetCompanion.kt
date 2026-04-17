package xyz.block.trailblaze.config

import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.model.AppVersionInfo
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import kotlin.reflect.KClass

/**
 * Interface for behavioral logic that cannot be expressed in YAML.
 *
 * App targets that need custom iOS drivers, version formatting, or
 * platform-specific tool providers implement this interface. The companion
 * is associated with a YAML-backed app target by ID in [BlockAppTargets].
 */
interface AppTargetCompanion {

  /**
   * Additional tool [KClass]es this companion contributes for name resolution.
   * Called during loader bootstrap so the [ToolNameResolver] can map YAML tool
   * name strings to concrete classes.
   */
  fun getAdditionalToolClasses(): Set<KClass<out TrailblazeTool>> = emptySet()

  /**
   * Custom iOS driver factory. Only called when the `.app.yaml` has
   * `has_custom_ios_driver: true`.
   *
   * @param originalIosDriver typed as [Any] because Maestro's IOSDriver is JVM-only
   * @return the original driver or a custom wrapper
   */
  fun getCustomIosDriverFactory(
    trailblazeDeviceId: TrailblazeDeviceId,
    originalIosDriver: Any,
  ): Any = originalIosDriver

  /**
   * Custom version formatting for display in the UI.
   * Return null to use the default formatting.
   */
  fun formatVersionInfo(
    platform: TrailblazeDevicePlatform,
    versionInfo: AppVersionInfo,
  ): String? = null

  /**
   * Custom warning message when the installed app version is below minimum.
   * Return null to use the default message.
   */
  fun getVersionWarningMessage(
    platform: TrailblazeDevicePlatform,
    installedVersion: AppVersionInfo,
    minVersion: String,
  ): String? = null

  /**
   * Custom version acceptability check.
   * Return null to use the default logic from [TrailblazeHostAppTarget.isVersionAcceptable].
   */
  fun isVersionAcceptable(
    platform: TrailblazeDevicePlatform,
    versionInfo: AppVersionInfo,
  ): Boolean? = null
}
