package xyz.block.trailblaze.config

import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.model.AppVersionInfo
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.toolcalls.ToolName
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

  private data class ToolsByDriver(
    val classes: Map<TrailblazeDriverType, Set<KClass<out TrailblazeTool>>>,
    val yamlNames: Map<TrailblazeDriverType, Set<ToolName>>,
  )

  /**
   * For each driver type, the resolved set of custom tools (both class-backed and
   * YAML-defined). Computed lazily from the YAML's platform sections + companion contributions.
   */
  private val resolvedCustomToolsByDriver: ToolsByDriver by lazy {
    val classes = mutableMapOf<TrailblazeDriverType, MutableSet<KClass<out TrailblazeTool>>>()
    val yamlNames = mutableMapOf<TrailblazeDriverType, MutableSet<ToolName>>()

    config.platforms?.forEach { (platformKey, platformConfig) ->
      val driverTypes = platformConfig.resolveDriverTypes(platformKey)

      // Resolve toolsets — both backings flow through.
      platformConfig.toolSets?.forEach { toolSetId ->
        val toolSet = availableToolSets[toolSetId]
        if (toolSet == null) {
          Console.log("Warning: App target '$id' platform '$platformKey' references unknown toolset '$toolSetId'")
          return@forEach
        }
        for (dt in driverTypes) {
          if (!toolSet.isCompatibleWith(dt)) continue
          classes.getOrPut(dt) { mutableSetOf() }.addAll(toolSet.resolvedToolClasses)
          yamlNames.getOrPut(dt) { mutableSetOf() }.addAll(toolSet.resolvedYamlToolNames)
        }
      }

      // Resolve individual tools — look up by bare name; route to the right bucket.
      // `resolveYamlNameOrNull` returns a typed `ToolName?`, so classification hands back
      // the already-wrapped value and we avoid a re-wrap here (Decision 038's
      // wrap-at-the-boundary pattern).
      platformConfig.tools?.forEach { toolName ->
        val toolClass = toolNameResolver.resolveOrNull(toolName)
        val yamlName = toolNameResolver.resolveYamlNameOrNull(toolName)
        when {
          toolClass != null -> for (dt in driverTypes) {
            classes.getOrPut(dt) { mutableSetOf() }.add(toolClass)
          }
          yamlName != null -> for (dt in driverTypes) {
            yamlNames.getOrPut(dt) { mutableSetOf() }.add(yamlName)
          }
          else -> Console.log(
            "Warning: App target '$id' platform '$platformKey' references unknown tool '$toolName'",
          )
        }
      }
    }

    ToolsByDriver(classes = classes, yamlNames = yamlNames)
  }

  override fun internalGetCustomToolsForDriver(
    driverType: TrailblazeDriverType,
  ): Set<KClass<out TrailblazeTool>> {
    return resolvedCustomToolsByDriver.classes[driverType] ?: emptySet()
  }

  override fun getCustomYamlToolNamesForDriver(
    driverType: TrailblazeDriverType,
  ): Set<ToolName> {
    return resolvedCustomToolsByDriver.yamlNames[driverType] ?: emptySet()
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

  // --- MCP server declarations (Decision 038) ---

  override fun getMcpServers(): List<McpServerConfig> = config.mcpServers ?: emptyList()

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
