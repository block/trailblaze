package xyz.block.trailblaze.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.devices.TrailblazeDriverType

/**
 * Schema for `.app.yaml` files — declarative configuration for a
 * [TrailblazeHostAppTarget][xyz.block.trailblaze.model.TrailblazeHostAppTarget].
 *
 * Example:
 * ```yaml
 * id: sample
 * display_name: Sample App
 * has_custom_ios_driver: true
 *
 * platforms:
 *   android:
 *     app_ids:
 *       - com.example.development
 *     tool_sets:
 *       - core_interaction
 *       - memory
 *       - sample_android
 *     tools: [customDebugTool]
 *     excluded_tools: [tapOnPoint]
 *   ios:
 *     app_ids:
 *       - com.example.sample
 *     tool_sets:
 *       - core_interaction
 *       - memory
 *     min_build_version: "6515"
 *   web:
 *     drivers: [playwright-native]
 *     tool_sets:
 *       - web_core
 *       - memory
 * ```
 */
@Serializable
data class AppTargetYamlConfig(
  val id: String,
  @SerialName("display_name") val displayName: String,
  val platforms: Map<String, PlatformConfig>? = null,
  @SerialName("has_custom_ios_driver") val hasCustomIosDriver: Boolean = false,
)

/**
 * Per-platform configuration within a target. Groups app IDs, toolsets, individual tools,
 * exclusions, and version requirements under the platform they apply to.
 */
@Serializable
data class PlatformConfig(
  @SerialName("app_ids") val appIds: List<String>? = null,
  @SerialName("tool_sets") val toolSets: List<String>? = null,
  val tools: List<String>? = null,
  @SerialName("excluded_tools") val excludedTools: List<String>? = null,
  val drivers: List<String>? = null,
  @SerialName("min_build_version") val minBuildVersion: String? = null,
) {

  /**
   * Resolves the driver types for this platform section using explicit [drivers] if set, otherwise
   * the [platformKey].
   */
  fun resolveDriverTypes(platformKey: String): Set<TrailblazeDriverType> =
    if (drivers != null) DriverTypeKey.resolveAll(drivers) else DriverTypeKey.resolve(platformKey)
}
