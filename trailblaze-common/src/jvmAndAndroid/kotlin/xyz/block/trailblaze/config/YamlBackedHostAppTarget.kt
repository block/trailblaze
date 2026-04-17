package xyz.block.trailblaze.config

import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.model.AppVersionInfo
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.util.Console
import kotlin.reflect.KClass

/**
 * A [TrailblazeHostAppTarget] backed by a parsed `.app.yaml` configuration
 * with an optional [AppTargetCompanion] for behavioral logic.
 *
 * Tool names are resolved lazily on first access to avoid boot-order issues.
 */
class YamlBackedHostAppTarget(
  val config: AppTargetYamlConfig,
  private val toolNameResolver: ToolNameResolver,
  private val availableToolSets: Map<String, ResolvedToolSet> = emptyMap(),
  private val companion: AppTargetCompanion? = null,
) : TrailblazeHostAppTarget(
  id = config.id,
  displayName = config.displayName,
) {

  // --- Platform config lookup ---

  /** Finds the [PlatformConfig] for [platform], trying exact lowercase match then case-insensitive. */
  private fun findPlatformConfig(platform: TrailblazeDevicePlatform): PlatformConfig? {
    val platformConfigs = config.platforms ?: return null
    return platformConfigs[platform.name.lowercase()]
      ?: platformConfigs.entries.firstOrNull { it.key.equals(platform.name, ignoreCase = true) }?.value
  }

  // --- App IDs ---

  override fun getPossibleAppIdsForPlatform(
    platform: TrailblazeDevicePlatform,
  ): Set<String>? = findPlatformConfig(platform)?.appIds?.toSet()

  // --- Custom tools per driver type ---

  /**
   * For each driver type, the resolved set of custom tool classes.
   * Computed lazily from the YAML's platform sections + companion contributions.
   */
  private val resolvedCustomToolsByDriver:
      Map<TrailblazeDriverType, Set<KClass<out TrailblazeTool>>> by lazy {
    val result = mutableMapOf<TrailblazeDriverType, MutableSet<KClass<out TrailblazeTool>>>()

    config.platforms?.forEach { (platformKey, platformConfig) ->
      // Resolve the platform key to driver types
      val driverTypes = platformConfig.resolveDriverTypes(platformKey)

      // Resolve toolsets
      platformConfig.toolSets?.forEach { toolSetId ->
        val toolSet = availableToolSets[toolSetId]
        if (toolSet == null) {
          Console.log("Warning: App target '$id' platform '$platformKey' references unknown toolset '$toolSetId'")
          return@forEach
        }
        for (dt in driverTypes) {
          if (toolSet.isCompatibleWith(dt)) {
            result.getOrPut(dt) { mutableSetOf() }.addAll(toolSet.resolvedToolClasses)
          }
        }
      }

      // Resolve individual tools
      platformConfig.tools?.forEach { toolName ->
        val toolClass = toolNameResolver.resolveOrNull(toolName)
        if (toolClass == null) {
          Console.log("Warning: App target '$id' platform '$platformKey' references unknown tool '$toolName'")
          return@forEach
        }
        for (dt in driverTypes) {
          result.getOrPut(dt) { mutableSetOf() }.add(toolClass)
        }
      }
    }

    result
  }

  override fun internalGetCustomToolsForDriver(
    driverType: TrailblazeDriverType,
  ): Set<KClass<out TrailblazeTool>> {
    return resolvedCustomToolsByDriver[driverType] ?: emptySet()
  }

  // --- Excluded tools ---

  private val resolvedExcludedTools:
      Map<TrailblazeDriverType, Set<KClass<out TrailblazeTool>>> by lazy {
    val result = mutableMapOf<TrailblazeDriverType, MutableSet<KClass<out TrailblazeTool>>>()

    config.platforms?.forEach { (platformKey, platformConfig) ->
      val excludedNames = platformConfig.excludedTools ?: return@forEach
      val driverTypes = platformConfig.resolveDriverTypes(platformKey)
      val toolClasses = excludedNames.mapNotNull { name ->
        try {
          toolNameResolver.resolve(name)
        } catch (_: IllegalArgumentException) { null }
      }.toSet()
      for (dt in driverTypes) {
        result.getOrPut(dt) { mutableSetOf() }.addAll(toolClasses)
      }
    }

    result
  }

  override fun getExcludedToolsForDriver(
    driverType: TrailblazeDriverType,
  ): Set<KClass<out TrailblazeTool>> =
    resolvedExcludedTools[driverType] ?: emptySet()

  // --- Version info ---

  override fun getMinBuildVersion(platform: TrailblazeDevicePlatform): String? =
    findPlatformConfig(platform)?.minBuildVersion

  // --- Companion delegation ---

  override val hasCustomIosDriver: Boolean
    get() = config.hasCustomIosDriver

  override fun getCustomIosDriverFactory(
    trailblazeDeviceId: TrailblazeDeviceId,
    originalIosDriver: Any,
  ): Any = companion?.getCustomIosDriverFactory(trailblazeDeviceId, originalIosDriver)
    ?: super.getCustomIosDriverFactory(trailblazeDeviceId, originalIosDriver)

  override fun formatVersionInfo(
    platform: TrailblazeDevicePlatform,
    versionInfo: AppVersionInfo,
  ): String? = companion?.formatVersionInfo(platform, versionInfo)

  override fun getVersionWarningMessage(
    platform: TrailblazeDevicePlatform,
    installedVersion: AppVersionInfo,
    minVersion: String,
  ): String? = companion?.getVersionWarningMessage(platform, installedVersion, minVersion)

  override fun isVersionAcceptable(
    platform: TrailblazeDevicePlatform,
    versionInfo: AppVersionInfo,
  ): Boolean = companion?.isVersionAcceptable(platform, versionInfo)
    ?: super.isVersionAcceptable(platform, versionInfo)

  override fun toString(): String = "YamlBackedHostAppTarget(id=$id, displayName=$displayName)"
}
